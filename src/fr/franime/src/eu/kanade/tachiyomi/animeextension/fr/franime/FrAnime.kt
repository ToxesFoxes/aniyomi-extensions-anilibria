package eu.kanade.tachiyomi.animeextension.fr.franime

import eu.kanade.tachiyomi.animeextension.fr.franime.dto.Anime
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.mytvextractor.MytvExtractor
import eu.kanade.tachiyomi.lib.sendvidextractor.SendvidExtractor
import eu.kanade.tachiyomi.lib.sibnetextractor.SibnetExtractor
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.injectLazy
import java.lang.Exception

class FrAnime : AnimeHttpSource() {

    override val name = "FRAnime"

    private val domain = "franime.fr"

    override val baseUrl = "https://$domain"

    private val baseApiUrl = "https://api.$domain/api"
    private val baseApiAnimeUrl = "$baseApiUrl/anime"

    override val lang = "fr"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    private val json: Json by injectLazy()

    private val database by lazy {
        client.newCall(GET("$baseApiUrl/animes/", headers)).execute()
            .use { it.body.string() }
            .let { json.decodeFromString<List<Anime>>(it) }
    }

    // ============================== Popular ===============================
    override fun fetchPopularAnime(page: Int) =
        pagesToAnimesPage(database.sortedByDescending { it.note }, page)

    override fun popularAnimeParse(response: Response) = throw Exception("not used")

    override fun popularAnimeRequest(page: Int) = throw Exception("not used")

    // =============================== Latest ===============================
    override fun fetchLatestUpdates(page: Int) = pagesToAnimesPage(database.reversed(), page)

    override fun latestUpdatesParse(response: Response): AnimesPage = throw Exception("not used")

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("not used")

    // =============================== Search ===============================
    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        val pages = database.filter {
            it.title.contains(query, true) ||
                it.originalTitle.contains(query, true) ||
                it.titlesAlt.en?.contains(query, true) == true ||
                it.titlesAlt.enJp?.contains(query, true) == true ||
                it.titlesAlt.jaJp?.contains(query, true) == true ||
                titleToUrl(it.originalTitle).contains(query)
        }
        return pagesToAnimesPage(pages, page)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = throw Exception("not used")

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw Exception("not used")

    // =========================== Anime Details ============================
    override fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> = Observable.just(anime)

    override fun animeDetailsParse(response: Response): SAnime = throw Exception("not used")

    // ============================== Episodes ==============================
    override fun fetchEpisodeList(anime: SAnime): Observable<List<SEpisode>> {
        val url = (baseUrl + anime.url).toHttpUrl()
        val stem = url.encodedPathSegments.last()
        val language = url.queryParameter("lang") ?: "vo"
        val season = url.queryParameter("s")?.toIntOrNull() ?: 1
        val animeData = database.first { titleToUrl(it.originalTitle) == stem }
        val episodes = animeData.seasons[season - 1].episodes
            .mapIndexedNotNull { index, episode ->
                val players = when (language) {
                    "vo" -> episode.languages.vo
                    else -> episode.languages.vf
                }.players

                if (players.isEmpty()) return@mapIndexedNotNull null

                SEpisode.create().apply {
                    setUrlWithoutDomain(anime.url + "&ep=${index + 1}")
                    name = episode.title
                    episode_number = (index + 1).toFloat()
                }
            }
        return Observable.just(episodes.sortedByDescending { it.episode_number })
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw Exception("not used")

    // ============================ Video Links =============================
    override fun fetchVideoList(episode: SEpisode): Observable<List<Video>> {
        val url = (baseUrl + episode.url).toHttpUrl()
        val seasonNumber = url.queryParameter("s")?.toIntOrNull() ?: 1
        val episodeNumber = url.queryParameter("ep")?.toIntOrNull() ?: 1
        val episodeLang = url.queryParameter("lang") ?: "vo"
        val stem = url.encodedPathSegments.last()
        val animeData = database.first { titleToUrl(it.originalTitle) == stem }
        val episodeData = animeData.seasons[seasonNumber - 1].episodes[episodeNumber - 1]
        val videoBaseUrl = "$baseApiAnimeUrl/${animeData.id}/${seasonNumber - 1}/${episodeNumber - 1}"

        val players = if (episodeLang == "vo") episodeData.languages.vo.players else episodeData.languages.vf.players

        val videos = players.flatMapIndexed { index, playerName ->
            val apiUrl = "$videoBaseUrl/$episodeLang/$index"
            val playerUrl = client.newCall(GET(apiUrl, headers)).execute().body.string()
            when (playerName) {
                "franime_myvi" -> listOf(Video(playerUrl, "FRAnime", playerUrl))
                "myvi" -> MytvExtractor(client).videosFromUrl(playerUrl)
                "sendvid" -> SendvidExtractor(client, headers).videosFromUrl(playerUrl)
                "sibnet" -> SibnetExtractor(client).videosFromUrl(playerUrl)
                else -> emptyList()
            }
        }

        return Observable.just(videos)
    }

    // ============================= Utilities ==============================
    private fun pagesToAnimesPage(pages: List<Anime>, page: Int): Observable<AnimesPage> {
        val chunks = pages.chunked(50)
        val hasNextPage = chunks.size > page
        val entries = pageToSAnimes(chunks.getOrNull(page - 1) ?: emptyList())
        return Observable.just(AnimesPage(entries, hasNextPage))
    }

    private val titleRegex by lazy { Regex("[^A-Za-z0-9 ]") }
    private fun titleToUrl(title: String) = titleRegex.replace(title, "").replace(" ", "-").lowercase()

    private fun pageToSAnimes(page: List<Anime>): List<SAnime> {
        return page.flatMap { anime ->
            anime.seasons.flatMapIndexed { index, season ->
                val seasonTitle = anime.title + if (anime.seasons.size > 1) " S${index + 1}" else ""
                val hasVostfr = season.episodes.any { ep -> ep.languages.vo.players.isNotEmpty() }
                val hasVf = season.episodes.any { ep -> ep.languages.vf.players.isNotEmpty() }

                // I want to die for writing this
                val languages = listOfNotNull(
                    if (hasVostfr) Triple("VOSTFR", "vo", hasVf) else null,
                    if (hasVf) Triple("VF", "vf", hasVostfr) else null,
                )

                languages.map { lang ->
                    SAnime.create().apply {
                        title = seasonTitle + if (lang.third) " (${lang.first})" else ""
                        thumbnail_url = anime.poster
                        genre = anime.genres.joinToString()
                        status = parseStatus(anime.status, anime.seasons.size, index + 1)
                        description = anime.description
                        setUrlWithoutDomain("/anime/${titleToUrl(anime.originalTitle)}?lang=${lang.second}&s=${index + 1}")
                        initialized = true
                    }
                }
            }
        }
    }

    private fun parseStatus(statusString: String?, seasonCount: Int = 1, season: Int = 1): Int {
        if (season < seasonCount) return SAnime.COMPLETED
        return when (statusString?.trim()) {
            "EN COURS" -> SAnime.ONGOING
            "TERMINÉ" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }
}
