package eu.kanade.tachiyomi.extension.es.olympusscanlation

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import kotlin.time.Duration.Companion.seconds

@Source
abstract class OlympusScanlation :
    KeiSource(),
    ConfigurableSource {

    private fun fetchedDomainUrl() {
        if (!preferences.fetchDomainPref()) return
        try {
            val initClient = network.client
            val document = initClient.newCall(GET("https://olympus.pages.dev", headers)).execute().asJsoup()
            val domain = document.selectFirst("meta[property=og:url]")?.attr("content")
                ?: return
            val host = initClient.newCall(GET(domain, headers)).execute().request.url.host
            val newDomain = "https://$host"
            preferences.edit().putString(BASE_URL_PREF, newDomain).apply()
        } catch (_: Exception) {
            return
        }
    }

    private val preferences = getPreferences()

    // La API ahora responde en el mismo dominio del sitio (ya no en panel.<dominio>)
    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder {
        fetchedDomainUrl()
        return rateLimit(1, 2.seconds) {
            it.host == baseUrl.toHttpUrl().host && !it.encodedPath.startsWith("/api")
        }.rateLimit(2, 1.seconds) {
            it.host == baseUrl.toHttpUrl().host && it.encodedPath.startsWith("/api")
        }
    }

    private inline fun <T> tryOrNull(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private var seriesList: List<MangaDto> = emptyList()
    private var lastFetchTime: Long = 0L
    private val seriesListMutex = Mutex()

    // Catálogo completo recorriendo /api/series?page=N (15 por página)
    private suspend fun fetchCatalog(): List<MangaDto> {
        val all = mutableListOf<MangaDto>()
        var page = 1
        while (true) {
            val series = client.get("$baseUrl/api/series?page=$page&direction=asc&type=comic")
                .parseAs<PayloadSeriesDto>().data.series
            all += series.data
            if (!series.hasNextPage()) break
            page++
        }
        return all.filter { it.type == "comic" }
    }

    private suspend fun fetchSeriesList() = seriesListMutex.withLock {
        val now = System.currentTimeMillis()
        if (seriesList.isNotEmpty() && (now - lastFetchTime) < CACHE_DURATION_MS) {
            return@withLock
        }

        // Primero el endpoint antiguo (1 sola llamada); si ya no existe, se recorre el catálogo
        val comics = tryOrNull {
            client.get("$baseUrl/api/series/list").parseAs<PayloadMangaDto>().data
                .filter { it.type == "comic" }
        }?.takeIf { it.isNotEmpty() } ?: fetchCatalog()

        seriesList = comics
        lastFetchTime = now

        val newSlugMap = comics.associate { it.id to it.slug }
        preferences.slugMap += newSlugMap
    }

    private suspend fun slugFor(mangaId: Int): String {
        preferences.slugMap[mangaId]?.let { return it }
        fetchSeriesList()
        return preferences.slugMap[mangaId] ?: throw Exception("No se encontró la serie (id $mangaId)")
    }

    private suspend fun fetchHomepage() = client.get("$baseUrl/api/homepage").parseAs<HomepageDto>()

    override suspend fun getPopularManga(page: Int): MangasPage {
        val ranking = tryOrNull {
            client.get("$baseUrl/api/rankings?page=$page&period=total_ranking").parseAs<RankingDto>()
        }

        val comics: List<MangaDto>
        val hasNextPage: Boolean
        if (ranking != null) {
            comics = ranking.data.filter { it.type == "comic" }
            hasNextPage = ranking.hasNextPage()
        } else if (page == 1) {
            // Respaldo: ranking que viene dentro de /api/homepage (15 series, sin más páginas)
            comics = fetchHomepage().rankings.filter { it.type == "comic" }
            hasNextPage = false
        } else {
            comics = emptyList()
            hasNextPage = false
        }

        val slugMap = preferences.slugMap.toMutableMap()
        val mangaList = comics.map {
            slugMap[it.id] = it.slug
            it.toSManga()
        }
        preferences.slugMap = slugMap
        return MangasPage(mangaList, hasNextPage)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val latest = tryOrNull {
            client.get("$baseUrl/api/new-chapters?page=$page").parseAs<NewChaptersDto>()
        }

        val comics: List<LatestMangaDto>
        val hasNextPage: Boolean
        if (latest != null) {
            comics = latest.data.filter { it.type == "comic" }
            hasNextPage = latest.hasNextPage()
        } else if (page == 1) {
            // Respaldo: new_chapters que viene dentro de /api/homepage (15 series)
            comics = fetchHomepage().data.newChapters.filter { it.type == "comic" }
            hasNextPage = false
        } else {
            comics = emptyList()
            hasNextPage = false
        }

        val slugMap = preferences.slugMap.toMutableMap()
        val mangaList = comics.map {
            slugMap[it.id] = it.slug
            it.toSManga()
        }
        preferences.slugMap = slugMap
        return MangasPage(mangaList, hasNextPage)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank()) {
            // Sin texto: catálogo paginado directo desde la API (15 por página)
            val series = client.get("$baseUrl/api/series?page=$page&direction=asc&type=comic")
                .parseAs<PayloadSeriesDto>().data.series
            val comics = series.data.filter { it.type == "comic" }
            preferences.slugMap += comics.associate { it.id to it.slug }
            return MangasPage(comics.map { it.toSManga() }, series.hasNextPage())
        }

        fetchSeriesList()
        val filteredList = seriesList.filter { it.name.contains(query, ignoreCase = true) }
        val paginatedList = filteredList.drop((page - 1) * 20).take(20)
        val hasNextPage = page * 20 < filteredList.size
        return MangasPage(paginatedList.map { it.toSManga() }, hasNextPage)
    }

    override fun getMangaUrl(manga: SManga): String {
        val slug = preferences.slugMap[manga.url.toInt()] ?: return baseUrl
        return "$baseUrl/series/comic-$slug"
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val mangaId = manga.url
        val slug = slugFor(mangaId.toInt())
        return coroutineScope {
            val details = async {
                if (fetchDetails) {
                    client.get("$baseUrl/api/series/$slug?type=comic")
                        .parseAs<MangaDetailDto>().data.toSMangaDetails()
                } else {
                    manga
                }
            }
            val chapterList = async {
                if (fetchChapters) fetchChapterList(slug, mangaId) else chapters
            }
            SMangaUpdate(details.await(), chapterList.await())
        }
    }

    private suspend fun fetchChapterList(mangaSlug: String, mangaId: String): List<SChapter> {
        val data = fetchChapterPage(mangaSlug, 1)
        val result = data.data.toMutableList()
        var page = 2
        while (data.meta.total > result.size) {
            val newData = fetchChapterPage(mangaSlug, page)
            if (newData.data.isEmpty()) break
            result += newData.data
            page += 1
        }
        return result.map { it.toSChapter(mangaId) }
    }

    private suspend fun fetchChapterPage(mangaSlug: String, page: Int): PayloadChapterDto = client.get("$baseUrl/api/series/$mangaSlug/chapters?page=$page&direction=desc&type=comic")
        .parseAs<PayloadChapterDto>()

    // chapter.url = "{idSerie}/{númeroDeCapítulo}"
    override fun getChapterUrl(chapter: SChapter): String {
        val mangaId = chapter.url.substringBefore("/")
        val chapterName = chapter.url.substringAfter("/")
        val mangaSlug = preferences.slugMap[mangaId.toInt()] ?: return baseUrl
        return "$baseUrl/series/comic-$mangaSlug/capitulo/$chapterName"
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val mangaId = chapter.url.substringBefore("/")
        val chapterName = chapter.url.substringAfter("/")
        val mangaSlug = slugFor(mangaId.toInt())
        return client.get("$baseUrl/api/capitulo/$mangaSlug/$chapterName?type=comic")
            .parseAs<PayloadPagesDto>().chapter.pages.mapIndexed { i, img ->
                Page(i, imageUrl = img)
            }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = FETCH_DOMAIN_PREF
            title = "Buscar dominio automáticamente"
            summary = "Intenta buscar el dominio automáticamente al abrir la fuente."
            setDefaultValue(false)
        }.also { screen.addPreference(it) }
    }

    private fun SharedPreferences.fetchDomainPref() = getBoolean(FETCH_DOMAIN_PREF, false)

    private var slugMapCache: Map<Int, String>? = null
    private var SharedPreferences.slugMap: Map<Int, String>
        get() {
            slugMapCache?.let { return it }
            val json = getString(SLUG_MAP, "{}")!!
            slugMapCache = try {
                json.parseAs<Map<Int, String>>()
            } catch (_: SerializationException) {
                emptyMap()
            }
            return slugMapCache!!
        }
        set(map) {
            slugMapCache = map
            edit().putString(SLUG_MAP, map.toJsonString()).apply()
        }

    companion object {
        private const val BASE_URL_PREF = "overrideBaseUrl"
        private const val FETCH_DOMAIN_PREF = "fetchDomain"
        private const val SLUG_MAP = "slugMap"
        private const val CACHE_DURATION_MS = 60 * 60 * 1000L // 1 hour
    }
}
