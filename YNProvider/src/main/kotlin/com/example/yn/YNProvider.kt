package com.example.yn

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class YNProvider : MainAPI() {

    override var mainUrl = "https://www.youtube.com"
    override var name    = "YN Provider"
    override var lang    = "vi"
    override val hasMainPage    = true
    override val supportedTypes = setOf(TvType.Movie)

    // ── URL helpers ────────────────────────────────────────────────

    /** URL nội bộ dùng làm key — không phải URL phát thật */
    private fun entryUrl(id: String)   = "yn://video/$id"
    private fun idFromUrl(url: String) = url.removePrefix("yn://video/")

    /** Thumbnail YouTube chất lượng cao */
    private fun thumb(id: String) = "https://i.ytimg.com/vi/$id/maxresdefault.jpg"

    // ── Trang chủ ──────────────────────────────────────────────────

    override val mainPage = mainPageOf("" to "YN Provider")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList())

        // Nhóm video theo category, mỗi nhóm là 1 hàng ngang
        val rows = YN_CATALOG
            .groupBy { it.category }
            .map { (category, entries) ->
                HomePageList(
                    name               = category,
                    list               = entries.map { e ->
                        newMovieSearchResponse(e.title, entryUrl(e.id), TvType.Movie) {
                            posterUrl = thumb(e.id)
                        }
                    },
                    isHorizontalImages = true,
                )
            }

        return newHomePageResponse(rows, hasNext = false)
    }

    // ── Tìm kiếm ──────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> =
        YN_CATALOG
            .filter { it.title.contains(query, ignoreCase = true) }
            .map { e ->
                newMovieSearchResponse(e.title, entryUrl(e.id), TvType.Movie) {
                    posterUrl = thumb(e.id)
                }
            }

    // ── Chi tiết ──────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val id    = idFromUrl(url)
        val entry = YN_CATALOG.find { it.id == id } ?: return null

        return newMovieLoadResponse(entry.title, url, TvType.Movie, url) {
            posterUrl = thumb(id)
            plot      = buildString {
                if (entry.description.isNotBlank()) {
                    appendLine(entry.description)
                    appendLine()
                }
                appendLine("📺 YouTube ID : ${entry.id}")
                appendLine("🎙️ Audio      : Archive.org")
            }.trim()
        }
    }

    // ── Link phát ─────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val id    = idFromUrl(data)
        val entry = YN_CATALOG.find { it.id == id } ?: return false

        // Đảm bảo local server đang chạy
        YNMergeServer.ensureStarted()

        // Trả về HLS master playlist do YNMergeServer tạo
        // → ExoPlayer tự ghép video YouTube + audio Archive.org
        callback(
            newExtractorLink(
                source = name,
                name   = "▶ ${entry.title}",
                url    = YNMergeServer.masterUrl(id),
                type   = ExtractorLinkType.M3U8,
            ) {
                quality = Qualities.HD.value
                referer = "https://www.youtube.com/watch?v=$id"
            }
        )

        return true
    }
}
