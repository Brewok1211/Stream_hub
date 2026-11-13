package com.idlix

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.getQualityFromString
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.Normalizer

class IdlixProvider : MainAPI() {
    override var mainUrl = "https://z2.idlixku.com"
    override var name = "Idlix"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "$mainUrl/api/movies?page=%d&limit=36&sort=createdAt" to "Latest Movies",
        "$mainUrl/api/series?page=%d&limit=36&sort=createdAt" to "Latest TV Series",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest" to "Latest Update",
        "$mainUrl/api/browse?page=%d&limit=36&sort=popular" to "Popular",
        "$mainUrl/api/browse?page=%d&limit=36&sort=rating" to "Leaderboard",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&network=netflix" to "Netflix",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&network=hbo" to "HBO",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&network=prime-video" to "Prime Video",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&network=disney-plus" to "Disney+",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&network=apple-tv-plus" to "Apple TV+"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (request.data.contains("%d")) request.data.format(page) else request.data
        val res = app.get(url, timeout = 10000L).parsedSafe<ApiResponse>()
            ?: return newHomePageResponse(request.name, emptyList())
            
        val home = res.data.map { item ->
            val title = item.title ?: "UnKnown"
            val poster = item.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }
            if (item.contentType == "movie") {
                val movieurl = "$mainUrl/api/movies/${item.slug}"
                newMovieSearchResponse(title, movieurl, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = item.releaseDate?.substringBefore("-")?.toIntOrNull()
                    this.quality = getSearchQuality(item.quality)
                    this.score = Score.from10(item.voteAverage?.toString())
                }
            } else {
                val seriesurl = "$mainUrl/api/series/${item.slug}"
                newTvSeriesSearchResponse(title, seriesurl, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = item.releaseDate?.substringBefore("-")?.toIntOrNull()
                    this.score = Score.from10(item.voteAverage?.toString())
                    this.quality = getSearchQuality(item.quality)
                }
            }
        }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query, 1)?.items

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val url = "$mainUrl/api/search?q=$query&page=$page&limit=8"
        val res = app.get(url).parsedSafe<SearchApiResponse>() ?: return null
        val items = res.results
        val results = items.mapNotNull { item ->
            val title = item.title ?: "Unknown"
            val poster = item.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }
            val year = (item.releaseDate ?: item.firstAirDate)?.substringBefore("-")?.toIntOrNull()

            val link = when (item.contentType) {
                "movie" -> "$mainUrl/api/movies/${item.slug}"
                "tv_series", "series" -> "$mainUrl/api/series/${item.slug}"
                else -> return@mapNotNull null
            }

            val rating = item.voteAverage?.toString()

            if (item.contentType == "movie") {
                newMovieSearchResponse(title, link, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = year
                    this.quality = getSearchQuality(item.quality)
                    this.score = rating?.let { Score.from10(it) }
                }
            } else {
                newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = year
                    this.score = rating?.let { Score.from10(it) }
                }
            }
        }

        return results.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        val detailApiUrl = url.toIdlixDetailApiUrl()
        val response = app.get(detailApiUrl, timeout = 10000L, referer = mainUrl)

        if (response.code != 200) {
            throw ErrorLoadingException("DETAIL_HTTP_ERR [${response.code}]: ${response.text.take(300)}")
        }

        val data = response.parsedSafe<DetailResponse>()
            ?: throw ErrorLoadingException("DETAIL_PARSE_ERR [${response.code}]: ${response.text.take(300)}")

        val title = data.title ?: "Unknown"
        val poster = data.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
        val backdrop = data.backdropPath?.let { "https://image.tmdb.org/t/p/original$it" }

        val year = (data.releaseDate ?: data.firstAirDate)
            ?.substringBefore("-")
            ?.toIntOrNull()

        val tags = data.genres?.mapNotNull { it.name } ?: emptyList()
        val logourl = "https://image.tmdb.org/t/p/original" + data.logoPath
        val actors = data.cast?.map {
            Actor(it.name ?: "", it.profilePath?.let { p -> "https://image.tmdb.org/t/p/w185$p" })
        } ?: emptyList()

        val trailer = data.trailerUrl
        val rating = data.voteAverage

        val relatedUrl = if (data.seasons != null) {
            "$mainUrl/api/series/${data.slug}/related"
        } else {
            "$mainUrl/api/movies/${data.slug}/related"
        }

        val weburl = if (data.seasons != null) {
            "$mainUrl/series/${data.slug}"
        } else {
            "$mainUrl/movie/${data.slug}"
        }

        val recommendations = try {
            app.get(relatedUrl, referer = mainUrl)
                .parsedSafe<ApiResponse>()?.data?.mapNotNull { item ->

                    val recTitle = item.title ?: return@mapNotNull null
                    val recPoster = item.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }

                    val link = if (item.contentType == "movie") {
                        "$mainUrl/api/movies/${item.slug}"
                    } else {
                        "$mainUrl/api/series/${item.slug}"
                    }

                    if (item.contentType == "movie") {
                        newMovieSearchResponse(recTitle, link, TvType.Movie) {
                            this.posterUrl = recPoster
                            this.year = (item.releaseDate ?: item.firstAirDate)
                                ?.substringBefore("-")
                                ?.toIntOrNull()
                        }
                    } else {
                        newTvSeriesSearchResponse(recTitle, link, TvType.TvSeries) {
                            this.posterUrl = recPoster
                            this.year = (item.releaseDate ?: item.firstAirDate)
                                ?.substringBefore("-")
                                ?.toIntOrNull()
                        }
                    }

                } ?: emptyList()

        } catch (_: Exception) {
            emptyList()
        }

        return if (data.seasons != null) {
            val episodes = mutableListOf<Episode>()

            data.firstSeason?.episodes?.forEach { ep ->
                val epId = ep.id ?: return@forEach
                episodes.add(
                    newEpisode(
                        LoadData(
                            id = epId,
                            type = "episode"
                        ).toJson()
                    ) {
                        this.name = ep.name
                        this.season = data.firstSeason.seasonNumber
                        this.episode = ep.episodeNumber
                        this.description = ep.overview
                        this.runTime = ep.runtime
                        this.score = Score.from10(ep.voteAverage?.toString())
                        addDate(ep.airDate)
                        this.posterUrl = ep.stillPath?.let { "https://image.tmdb.org/t/p/w300$it" }
                    }
                )
            }

            data.seasons.forEach { season ->
                val seasonNum = season.seasonNumber ?: return@forEach
                if (seasonNum == data.firstSeason?.seasonNumber) return@forEach
                val seasonUrl = "$mainUrl/api/series/${data.slug}/season/$seasonNum"

                val seasonData = try {
                    val res = app.get(seasonUrl, referer = mainUrl)
                    res.parsedSafe<SeasonWrapper>()?.season
                } catch (_: Exception) {
                    null
                }

                seasonData?.episodes?.forEach { ep ->
                    val epId = ep.id ?: return@forEach
                    episodes.add(
                        newEpisode(
                            LoadData(
                                id = epId,
                                type = "episode"
                            ).toJson()
                        ) {
                            this.name = ep.name
                            this.season = seasonNum
                            this.episode = ep.episodeNumber
                            this.description = ep.overview
                            this.runTime = ep.runtime
                            this.score = Score.from10(ep.voteAverage?.toString())
                            addDate(ep.airDate)
                            this.posterUrl = ep.stillPath?.let { "https://image.tmdb.org/t/p/w300$it" }
                        }
                    )
                }
            }

            newTvSeriesLoadResponse(title, weburl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.logoUrl = logourl
                this.year = year
                this.plot = data.overview
                this.tags = tags
                this.score = Score.from10(rating?.toString())
                addActors(actors)
                addTrailer(trailer)
                addTMDbId(data.tmdbId?.toString())
                addImdbId(data.imdbId)
                this.recommendations = recommendations
            }
        } else {
            val movieId = data.id ?: ""
            newMovieLoadResponse(
                title,
                weburl,
                TvType.Movie,
                LoadData(
                    id = movieId,
                    type = "movie"
                ).toJson()
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.logoUrl = logourl
                this.year = year
                this.plot = data.overview
                this.tags = tags
                this.score = Score.from10(rating?.toString())
                addActors(actors)
                addTrailer(trailer)
                addTMDbId(data.tmdbId?.toString())
                addImdbId(data.imdbId)
                this.recommendations = recommendations
            }
        }
    }

    private fun String.toIdlixDetailApiUrl(): String {
        val value = trim()
        if (value.isBlank()) return value
        if (value.contains("/api/movies/", true) || value.contains("/api/series/", true)) return value

        fun slugAfter(marker: String): String? = value
            .substringAfter(marker, "")
            .substringBefore("?")
            .substringBefore("#")
            .substringBefore("/")
            .takeIf { it.isNotBlank() }

        slugAfter("/movie/")?.let { return "$mainUrl/api/movies/$it" }
        slugAfter("/series/")?.let { return "$mainUrl/api/series/$it" }

        return value
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val parsed = try {
            parseJson<LoadData>(data)
        } catch (e: Exception) {
            throw ErrorLoadingException("LOAD_DATA_INVALID: data=$data (${e.message})")
        }

        val contentId = parsed.id
        val contentType = parsed.type

        val headers = mapOf(
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl,
            "Accept" to "*/*",
            "Content-Type" to "application/json"
        )

        // 1. Ambil play-info
        val playInfoUrl = "$mainUrl/api/watch/play-info/$contentType/$contentId"
        val playResponse = app.get(playInfoUrl, headers = headers)

        if (playResponse.code != 200) {
            throw ErrorLoadingException("PLAY_INFO_ERR [${playResponse.code}] ($playInfoUrl): ${playResponse.text.take(300)}")
        }

        val playInfo = playResponse.parsedSafe<Res>()
            ?: throw ErrorLoadingException("PLAY_INFO_PARSE_ERR: ${playResponse.text.take(300)}")

        val gateToken = playInfo.gateToken
        if (gateToken.isNullOrBlank()) {
            throw ErrorLoadingException("GATE_TOKEN_EMPTY: ${playResponse.text.take(300)}")
        }

        // 2. Tunggu Anti-Scrape Timer
        val waitTime = (playInfo.unlockAt - playInfo.serverNow).coerceAtLeast(0)
        val totalWait = waitTime / 1000
        var elapsed = 0L
        while (elapsed < totalWait) {
            Log.d(name, "Waiting gate unlock: ${elapsed}s / ${totalWait}s")
            delay(1000)
            elapsed++
        }
        delay(1000) // Buffer 1 detik agar timestamp server benar-benar lolos

        // 3. Claim Session Token
        val claimJson = """{"gateToken":"$gateToken"}"""
        val claimRaw = app.post(
            "$mainUrl/api/watch/session/claim",
            headers = headers,
            cookies = playResponse.cookies,
            requestBody = claimJson.toRequestBody("application/json".toMediaType())
        )
        val claimText = claimRaw.text

        if (claimRaw.code != 200) {
            throw ErrorLoadingException("CLAIM_ERR [${claimRaw.code}]: ${claimText.take(400)}")
        }

        val claimApi = try {
            parseJson<RedeemRes>(claimText)
        } catch (e: Exception) {
            throw ErrorLoadingException("CLAIM_PARSE_ERR (${e.message}): $claimText")
        }

        val claimToken = claimApi.claim
        if (claimToken.isNullOrBlank()) {
            throw ErrorLoadingException("CLAIM_TOKEN_EMPTY: $claimText")
        }

        val redeemUrlRaw = claimApi.redeemUrl
        if (redeemUrlRaw.isNullOrBlank()) {
            throw ErrorLoadingException("REDEEM_URL_EMPTY: $claimText")
        }

        // 4. Gabungkan seluruh Cookie (playResponse + claimRaw)
        val sessionCookies = mutableMapOf<String, String>()
        sessionCookies.putAll(playResponse.cookies)
        sessionCookies.putAll(claimRaw.cookies)

        val fullRedeemUrl = resolveRedeemUrl(redeemUrlRaw)
        val redeemJson = """{"claim":"$claimToken"}"""

        // 5. Redeem Token
        val redeemRaw = app.post(
            fullRedeemUrl,
            headers = headers,
            cookies = sessionCookies,
            requestBody = redeemJson.toRequestBody("application/json".toMediaType())
        )
        val redeemText = redeemRaw.text

        if (redeemRaw.code != 200) {
            // Jika masih gagal 400, seluruh data diagnostik akan langsung muncul di layar tes:
            throw ErrorLoadingException(
                "REDEEM_ERR [${redeemRaw.code}]\n" +
                "URL: $fullRedeemUrl\n" +
                "CLAIM_DATA: $claimText\n" +
                "COOKIES: $sessionCookies\n" +
                "RESP: ${redeemText.take(350)}"
            )
        }

        val iframeResponse = try {
            parseJson<Iframe>(redeemText)
        } catch (e: Exception) {
            throw ErrorLoadingException("REDEEM_PARSE_ERR (${e.message}): $redeemText")
        }

        val rawStreamUrl = iframeResponse.url?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("STREAM_URL_BLANK: $redeemText")

        val streamUrl = resolveUrl(rawStreamUrl)

        try {
            generateM3u8(
                name,
                streamUrl,
                mainUrl
            ).forEach(callback)
        } catch (e: Exception) {
            throw ErrorLoadingException("M3U8_GENERATE_ERR (${e.message}): $streamUrl")
        }

        iframeResponse.subtitles.forEach { subtitle ->
            val path = subtitle.path
            if (!path.isNullOrBlank()) {
                subtitleCallback(
                    newSubtitleFile(
                        subtitle.label ?: subtitle.lang ?: "Subtitle",
                        resolveUrl(path)
                    )
                )
            }
        }

        return true
    }

    private fun resolveRedeemUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.startsWith("/watch/")) {
            return "$mainUrl/api$trimmed"
        }
        return resolveUrl(trimmed)
    }

    private fun resolveUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> "$mainUrl$trimmed"
            else -> "$mainUrl/$trimmed"
        }
    }
}

fun getSearchQuality(check: String?): SearchQuality? {
    val s = check ?: return null
    val u = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase()
    val patterns = listOf(
        Regex("\\b(4k|ds4k|uhd|2160p)\\b", RegexOption.IGNORE_CASE) to SearchQuality.FourK,
        Regex("\\b(hdts|hdcam|hdtc)\\b", RegexOption.IGNORE_CASE) to SearchQuality.HdCam,
        Regex("\\b(camrip|cam[- ]?rip)\\b", RegexOption.IGNORE_CASE) to SearchQuality.CamRip,
        Regex("\\b(cam)\\b", RegexOption.IGNORE_CASE) to SearchQuality.Cam,
        Regex("\\b(web[- ]?dl|webrip|webdl)\\b", RegexOption.IGNORE_CASE) to SearchQuality.WebRip,
        Regex("\\b(bluray|bdrip|blu[- ]?ray)\\b", RegexOption.IGNORE_CASE) to SearchQuality.BlueRay,
        Regex("\\b(1440p|qhd)\\b", RegexOption.IGNORE_CASE) to SearchQuality.BlueRay,
        Regex("\\b(1080p|fullhd)\\b", RegexOption.IGNORE_CASE) to SearchQuality.HD,
        Regex("\\b(720p)\\b", RegexOption.IGNORE_CASE) to SearchQuality.SD,
        Regex("\\b(hdrip|hdtv)\\b", RegexOption.IGNORE_CASE) to SearchQuality.HD,
        Regex("\\b(dvd)\\b", RegexOption.IGNORE_CASE) to SearchQuality.DVD,
        Regex("\\b(hq)\\b", RegexOption.IGNORE_CASE) to SearchQuality.HQ,
        Regex("\\b(rip)\\b", RegexOption.IGNORE_CASE) to SearchQuality.CamRip
    )

    for ((regex, quality) in patterns) if (regex.containsMatchIn(u)) return quality
    return null
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class ApiResponse(
    @JsonProperty("data") val data: List<ApiItem> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SearchApiResponse(
    @JsonProperty("results") val results: List<ApiItem> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ApiItem(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("posterPath") val posterPath: String? = null,
    @JsonProperty("contentType") val contentType: String? = null,
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("releaseDate") val releaseDate: String? = null,
    @JsonProperty("firstAirDate") val firstAirDate: String? = null,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("voteAverage") val voteAverage: Any? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DetailResponse(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("posterPath") val posterPath: String? = null,
    @JsonProperty("backdropPath") val backdropPath: String? = null,
    @JsonProperty("logoPath") val logoPath: String? = null,
    @JsonProperty("releaseDate") val releaseDate: String? = null,
    @JsonProperty("firstAirDate") val firstAirDate: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("voteAverage") val voteAverage: Any? = null,
    @JsonProperty("trailerUrl") val trailerUrl: String? = null,
    @JsonProperty("tmdbId") val tmdbId: Int? = null,
    @JsonProperty("imdbId") val imdbId: String? = null,
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("genres") val genres: List<Genre>? = emptyList(),
    @JsonProperty("cast") val cast: List<Cast>? = emptyList(),
    @JsonProperty("seasons") val seasons: List<Season>? = null,
    @JsonProperty("firstSeason") val firstSeason: Season? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Genre(
    @JsonProperty("name") val name: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Cast(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("profilePath") val profilePath: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SeasonWrapper(
    @JsonProperty("season") val season: Season? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Season(
    @JsonProperty("seasonNumber") val seasonNumber: Int? = null,
    @JsonProperty("episodes") val episodes: List<EpisodeData>? = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EpisodeData(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("episodeNumber") val episodeNumber: Int? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("runtime") val runtime: Int? = null,
    @JsonProperty("voteAverage") val voteAverage: Any? = null,
    @JsonProperty("airDate") val airDate: String? = null,
    @JsonProperty("stillPath") val stillPath: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LoadData(
    @JsonProperty("id") val id: String,
    @JsonProperty("type") val type: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Res(
    @JsonProperty("gateToken") val gateToken: String? = null,
    @JsonProperty("serverNow") val serverNow: Long = 0L,
    @JsonProperty("unlockAt") val unlockAt: Long = 0L,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RedeemRes(
    @JsonProperty("kind") val kind: String? = null,
    @JsonProperty("claim") val claim: String? = null,
    @JsonProperty("redeemUrl") val redeemUrl: String? = null,
    @JsonProperty("videoId") val videoId: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("durationSec") val durationSec: Long? = null,
    @JsonProperty("viewerTier") val viewerTier: String? = null,
    @JsonProperty("maxHeight") val maxHeight: Long? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Iframe(
    @JsonProperty("code") val code: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("expiresAt") val expiresAt: Long? = null,
    @JsonProperty("subtitles") val subtitles: List<Subtitle> = emptyList(),
    @JsonProperty("videoId") val videoId: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Subtitle(
    @JsonProperty("lang") val lang: String? = null,
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("path") val path: String? = null,
)