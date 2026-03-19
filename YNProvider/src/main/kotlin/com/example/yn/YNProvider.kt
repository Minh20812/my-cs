package com.example.yn

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class YNProvider : MainAPI() {

    override var mainUrl = "https://ynprovider.local"
    override var name    = "YN Provider"
    override var lang    = "vi"
    override val hasMainPage    = true
    override val supportedTypes = setOf(TvType.Movie)

    private fun entryUrl(id: String)   = "$mainUrl/video/$id"
    private fun idFromUrl(url: String) = url.substringAfterLast("/video/")
    private fun thumb(id: String)      = "https://i.ytimg.com/vi/$id/maxresdefault.jpg"

    override val mainPage = mainPageOf("" to "YN Provider")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList())
        val rows = YN_CATALOG.groupBy { it.category }.map { (cat, entries) ->
            HomePageList(cat, entries.map { e ->
                newMovieSearchResponse(e.title, entryUrl(e.id), TvType.Movie) {
                    posterUrl = thumb(e.id)
                }
            }, isHorizontalImages = true)
        }
        return newHomePageResponse(rows, hasNext = false)
    }

    override suspend fun search(query: String) = YN_CATALOG
        .filter { it.title.contains(query, ignoreCase = true) }
        .map { e ->
            newMovieSearchResponse(e.title, entryUrl(e.id), TvType.Movie) { posterUrl = thumb(e.id) }
        }

    override suspend fun load(url: String): LoadResponse? {
        val id    = idFromUrl(url)
        val entry = YN_CATALOG.find { it.id == id } ?: return null
        return newMovieLoadResponse(entry.title, url, TvType.Movie, url) {
            posterUrl = thumb(id)
            plot      = entry.description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val id    = idFromUrl(data)
        val entry = YN_CATALOG.find { it.id == id } ?: return false

        YNMergeServer.ensureStarted()

        // Dùng CloudStream's built-in YouTube extractor
        // loadExtractor tự xử lý cipher, key, mọi thứ
        val ytLinks = mutableListOf<ExtractorLink>()
        loadExtractor(
            url              = "https://www.youtube.com/watch?v=$id",
            referer          = "https://www.youtube.com",
            subtitleCallback = subtitleCallback,
            callback         = { link -> ytLinks.add(link) }
        )

        // Chọn link video chất lượng tốt nhất (ưu tiên 720p)
        val bestVideoUrl = ytLinks
            .filter { it.type == ExtractorLinkType.VIDEO || it.type == ExtractorLinkType.M3U8 }
            .maxByOrNull { it.quality }
            ?.url

        if (bestVideoUrl != null) {
            // Cache URL để MergeServer dùng
            YNMergeServer.videoUrlCache[id] = bestVideoUrl

            // Trả về HLS master playlist ghép video + Archive.org audio
            callback(newExtractorLink(
                source = name,
                name   = "▶ ${entry.title}",
                url    = YNMergeServer.masterUrl(id),
                type   = ExtractorLinkType.M3U8,
            ) {
                quality = Qualities.Unknown.value
                referer = "https://www.youtube.com/watch?v=$id"
            })
            return true
        }

        // Fallback: không lấy được video → chỉ phát audio
        callback(newExtractorLink(
            source = name,
            name   = "🎙️ Audio only",
            url    = entry.audioUrl,
            type   = ExtractorLinkType.VIDEO,
        ) {
            quality = Qualities.Unknown.value
        })
        return true
    }
}
