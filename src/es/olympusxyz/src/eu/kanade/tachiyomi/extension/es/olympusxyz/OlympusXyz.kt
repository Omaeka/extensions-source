package eu.kanade.tachiyomi.extension.es.olympusxyz

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class OlympusXyz : HttpSource(), ConfigurableSource {

    override val name = "Olympus Scanlation"
    override val lang = "es"
    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // La API vive en el mismo dominio que el sitio: {baseUrl}/api/...
    override val baseUrl: String
        get() = preferences.getString(PREF_BASE_URL, DEFAULT_BASE_URL)!!.trim().trimEnd('/')

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .rateLimit(4)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    // ---------- helpers ----------

    private fun apiHeaders() = headersBuilder()
        .set("Accept", "application/json")
        .set("Referer", "$baseUrl/")
        .build()

    private fun apiRequest(url: HttpUrl): Request = GET(url, apiHeaders())

    private fun apiRequest(path: String): Request = apiRequest("$baseUrl$path".toHttpUrl())

    private inline fun <reified T> Response.parseJson(): T = use {
        if (!it.isSuccessful) throw Exception("HTTP error ${it.code}")
        json.decodeFromString(serializer<T>(), it.body.string())
    }

    // url de manga: /series/comic-{slug}   (la API usa {slug} sin el "comic-")
    private fun slugFrom(url: String) = url.substringAfter("/series/comic-").substringBefore("/")

    private fun parseStatus(status: String?): Int = when {
        status == null -> SManga.UNKNOWN
        status.contains("Activo", true) -> SManga.ONGOING
        status.contains("Finalizado", true) -> SManga.COMPLETED
        status.contains("Pausado", true) || status.contains("Hiatus", true) -> SManga.ON_HIATUS
        status.contains("Cancelado", true) || status.contains("Abandonado", true) -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    private fun parseDate(date: String?): Long {
        if (date == null) return 0L
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(date.take(19))?.time
        }.getOrNull() ?: 0L
    }

    private fun SeriesDto.toSManga(): SManga {
        val dto = this
        return SManga.create().apply {
            title = dto.name.trim()
            url = "/series/comic-${dto.slug}"
            thumbnail_url = dto.cover
            status = parseStatus(dto.status?.name)
        }
    }

    private fun ChapterDto.toSChapter(slug: String): SChapter {
        val dto = this
        return SChapter.create().apply {
            url = "/series/comic-$slug/capitulo/${dto.name}"
            name = "Capítulo ${dto.name}"
            chapter_number = dto.name.toFloatOrNull() ?: -1f
            date_upload = parseDate(dto.publishedAt)
            scanlator = dto.team?.name?.trim()
        }
    }

    // ---------- popular (ranking del inicio, 15 series) ----------

    override fun popularMangaRequest(page: Int): Request = apiRequest("/api/homepage")

    override fun popularMangaParse(response: Response): MangasPage {
        val home = response.parseJson<HomepageDto>()
        val mangas = home.rankings
            .filter { it.type == null || it.type == "comic" }
            .map { it.toSManga() }
        return MangasPage(mangas, false)
    }

    // ---------- últimos capítulos (inicio, 15 series) ----------

    override fun latestUpdatesRequest(page: Int): Request = apiRequest("/api/homepage")

    override fun latestUpdatesParse(response: Response): MangasPage {
        val home = response.parseJson<HomepageDto>()
        val mangas = home.data.newChapters
            .filter { it.type == null || it.type == "comic" }
            .map { it.toSManga() }
        return MangasPage(mangas, false)
    }

    // ---------- catálogo completo + búsqueda ----------

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val order = filters.filterIsInstance<OrderFilter>().firstOrNull()
        val direction = if (order?.state == 1) "desc" else "asc"

        val url = "$baseUrl/api/series".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("direction", direction)
            .addQueryParameter("type", "comic")
        if (query.isNotBlank()) {
            url.addQueryParameter(SEARCH_PARAM, query.trim())
        }
        return apiRequest(url.build())
    }

    // Tolerante: acepta {data:{series:{data:[..],last_page}}} o {data:[..],meta:{last_page}}
    override fun searchMangaParse(response: Response): MangasPage {
        if (!response.isSuccessful) {
            response.close()
            throw Exception("HTTP error ${response.code}")
        }
        val root = json.parseToJsonElement(response.use { it.body.string() }).jsonObject
        val data = root["data"]
        val seriesObj = (data as? JsonObject)?.get("series") as? JsonObject

        val items: JsonArray?
        val current: Int?
        val last: Int?
        if (seriesObj != null) {
            items = seriesObj["data"] as? JsonArray
            current = (seriesObj["current_page"] as? JsonPrimitive)?.intOrNull
            last = (seriesObj["last_page"] as? JsonPrimitive)?.intOrNull
        } else {
            items = data as? JsonArray
            val meta = root["meta"] as? JsonObject
            current = (meta?.get("current_page") as? JsonPrimitive)?.intOrNull
            last = (meta?.get("last_page") as? JsonPrimitive)?.intOrNull
        }

        val list = items
            ?.let { json.decodeFromJsonElement(serializer<List<SeriesDto>>(), it) }
            .orEmpty()
        val mangas = list
            .filter { it.type == null || it.type == "comic" }
            .map { it.toSManga() }
        return MangasPage(mangas, (current ?: 1) < (last ?: 1))
    }

    // ---------- detalle ----------

    override fun mangaDetailsRequest(manga: SManga): Request =
        apiRequest("/api/series/${slugFrom(manga.url)}?type=comic")

    override fun mangaDetailsParse(response: Response): SManga {
        val dto = response.parseJson<DetailDto>().data
        return SManga.create().apply {
            title = dto.name.trim()
            thumbnail_url = dto.cover
            description = dto.summary?.trim()
            genre = dto.genres.joinToString { it.name.trim() }
            status = parseStatus(dto.status?.name)
        }
    }

    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url

    // ---------- capítulos (40 por página, se piden todas) ----------

    override fun chapterListRequest(manga: SManga): Request =
        apiRequest("/api/series/${slugFrom(manga.url)}/chapters?page=1&direction=desc&type=comic")

    override fun chapterListParse(response: Response): List<SChapter> {
        val firstUrl = response.request.url
        val slug = firstUrl.pathSegments[2]
        val first = response.parseJson<ChapterListDto>()
        val chapters = first.data.toMutableList()

        for (p in 2..first.meta.lastPage) {
            val pageUrl = firstUrl.newBuilder().setQueryParameter("page", p.toString()).build()
            val next = client.newCall(apiRequest(pageUrl)).execute().parseJson<ChapterListDto>()
            chapters += next.data
        }
        return chapters.map { it.toSChapter(slug) }
    }

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    // ---------- páginas ----------

    override fun pageListRequest(chapter: SChapter): Request {
        val slug = slugFrom(chapter.url)
        val number = chapter.url.substringAfterLast("/capitulo/")
        return apiRequest("/api/capitulo/$slug/$number?type=comic")
    }

    override fun pageListParse(response: Response): List<Page> {
        val hideCredits = preferences.getBoolean(PREF_HIDE_CREDITS, true)
        return response.parseJson<PagesDto>().chapter.pages
            .filter { !(hideCredits && it.contains("zonaolympus", ignoreCase = true)) }
            .mapIndexed { i, url -> Page(i, imageUrl = url) }
    }

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request {
        val imageHeaders = headersBuilder().set("Referer", "$baseUrl/").build()
        return GET(page.imageUrl!!, imageHeaders)
    }

    // ---------- filtros ----------

    private class OrderFilter : Filter.Select<String>("Orden", arrayOf("A → Z", "Z → A"))

    override fun getFilterList() = FilterList(OrderFilter())

    // ---------- ajustes ----------

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_BASE_URL
            title = "URL base personalizada"
            summary = "Cámbiala si el sitio cambia de dominio. Por defecto: $DEFAULT_BASE_URL"
            dialogTitle = "URL base"
            setDefaultValue(DEFAULT_BASE_URL)
            setOnPreferenceChangeListener { _, newValue ->
                newValue.toString().trim().toHttpUrlOrNull() != null
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_HIDE_CREDITS
            title = "Ocultar página de créditos"
            summary = "Quita la última imagen con el aviso del sitio (zzz-zonaolympus)."
            setDefaultValue(true)
        }.also(screen::addPreference)
    }

    companion object {
        private const val DEFAULT_BASE_URL = "https://olympusxyz.com"
        private const val PREF_BASE_URL = "base_url"
        private const val PREF_HIDE_CREDITS = "hide_credits"

        // Parámetro de búsqueda del catálogo. NO verificado: si la búsqueda por texto
        // no filtra, cambia solo este valor por el que use el sitio.
        private const val SEARCH_PARAM = "name"
    }
}
