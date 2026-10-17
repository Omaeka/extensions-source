package eu.kanade.tachiyomi.extension.es.olympusxyz

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// GET /api/homepage  (rankings = popular, data.new_chapters = últimos capítulos)
@Serializable
class HomepageDto(
    val data: HomepageDataDto,
    val rankings: List<SeriesDto> = emptyList(),
)

@Serializable
class HomepageDataDto(
    @SerialName("new_chapters") val newChapters: List<SeriesDto> = emptyList(),
)

// Item de lista (catálogo, ranking y nuevos capítulos comparten estos campos)
@Serializable
class SeriesDto(
    val id: Int,
    val name: String,
    val slug: String,
    val cover: String? = null,
    val status: StatusDto? = null,
    val type: String? = null,
)

@Serializable
class StatusDto(
    val id: Int? = null,
    val name: String? = null,
)

// GET /api/series/{slug}?type=comic
@Serializable
class DetailDto(
    val data: DetailDataDto,
)

@Serializable
class DetailDataDto(
    val id: Int,
    val name: String,
    val slug: String,
    val summary: String? = null,
    val status: StatusDto? = null,
    val genres: List<GenreDto> = emptyList(),
    val cover: String? = null,
)

@Serializable
class GenreDto(
    val name: String,
)

// GET /api/series/{slug}/chapters?page=N&direction=desc&type=comic
@Serializable
class ChapterListDto(
    val data: List<ChapterDto>,
    val meta: MetaDto,
)

@Serializable
class MetaDto(
    @SerialName("current_page") val currentPage: Int,
    @SerialName("last_page") val lastPage: Int,
)

@Serializable
class ChapterDto(
    val id: Int,
    val name: String,
    @SerialName("published_at") val publishedAt: String? = null,
    val team: TeamDto? = null,
)

@Serializable
class TeamDto(
    val name: String? = null,
)

// GET /api/capitulo/{slug}/{número}?type=comic
@Serializable
class PagesDto(
    val chapter: PagesChapterDto,
)

@Serializable
class PagesChapterDto(
    val pages: List<String> = emptyList(),
)
