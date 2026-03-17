package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

class ThapcamProvider : MainAPI() {

    override var mainUrl = com.example.thapcam.BuildConfig.API_URL.substringBeforeLast("/")
    override var name    = "ThapcamTV"
    override var lang    = "vi"
    override val hasMainPage    = true
    override val supportedTypes = setOf(TvType.Live)

    private val apiUrl = com.example.thapcam.BuildConfig.API_URL

    // ── Data classes (dùng @JsonProperty của cloudstream built-in) ─────────

    data class RequestHeader(
        val key: String,
        val value: String,
    )
    data class StreamLink(
        val id: String,
        val name: String,
        val type: String,
        val url: String,
        val default: Boolean = false,
        val request_headers: List<RequestHeader>? = null,
    )
    data class Stream(
        val id: String,
        val name: String,
        val stream_links: List<StreamLink>,
    )
    data class Content(
        val id: String,
        val name: String,
        val streams: List<Stream>,
    )
    data class Source(
        val id: String,
        val name: String,
        val contents: List<Content>,
    )
    data class ChannelImage(
        val url: String? = null,
    )
    data class OrgMetadata(
        val league: String? = null,
        val team_a: String? = null,
        val team_b: String? = null,
        val thumb: String? = null,
    )
    data class Channel(
        val id: String,
        val name: String,
        val image: ChannelImage? = null,
        val sources: List<Source>,
        val org_metadata: OrgMetadata? = null,
    )
    data class Group(
        val id: String,
        val name: String,
        val channels: List<Channel>,
    )
    data class ThapcamData(
        val name: String,
        val groups: List<Group>,
    )

    // ── Helpers ────────────────────────────────────────────────────────────

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

    private suspend fun fetchData(): ThapcamData {
        val json = app.get(apiUrl, headers = mapOf("User-Agent" to ua)).text
        return parseJson<ThapcamData>(json)
    }

    private suspend fun allChannels() = fetchData().groups.flatMap { it.channels }

    private suspend fun findChannel(id: String) = allChannels().find { it.id == id }

    private fun Channel.poster() = image?.url?.ifBlank { null } ?: org_metadata?.thumb

    private fun channelUrl(id: String) = "$mainUrl/channel/$id"
    private fun channelId(url: String) = url.substringAfterLast("/channel/")

    // ── Main page ──────────────────────────────────────────────────────────

    override val mainPage = mainPageOf(apiUrl to "ThapcamTV")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList())
        val data  = fetchData()
        val pages = data.groups.map { group ->
            val items = group.channels.map { ch ->
                newMovieSearchResponse(ch.name, channelUrl(ch.id), TvType.Live) {
                    posterUrl = ch.poster()
                }
            }
            HomePageList(group.name, items, isHorizontalImages = true)
        }
        return newHomePageResponse(pages, hasNext = false)
    }

    // ── Search ─────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> =
        allChannels()
            .filter { it.name.contains(query, ignoreCase = true) }
            .map { ch ->
                newMovieSearchResponse(ch.name, channelUrl(ch.id), TvType.Live) {
                    posterUrl = ch.poster()
                }
            }

    // ── Load ───────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val ch = findChannel(channelId(url)) ?: return null
        val meta = ch.org_metadata
        val plot = buildString {
            meta?.league?.let { appendLine("Giai dau: $it") }
            if (!meta?.team_a.isNullOrBlank() && !meta?.team_b.isNullOrBlank())
                appendLine("${meta!!.team_a}  vs  ${meta.team_b}")
            val count = ch.sources.sumOf { s -> s.contents.sumOf { c -> c.streams.sumOf { it.stream_links.size } } }
            if (count > 0) appendLine("$count link kha dung")
        }.trim().ifBlank { null }
        return newMovieLoadResponse(ch.name, url, TvType.Live, url) {
            posterUrl = ch.poster()
            this.plot = plot
        }
    }

    // ── Load Links ─────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val ch = findChannel(channelId(data)) ?: return false
        var found = false

        ch.sources.forEach { source ->
            source.contents.forEach { content ->
                content.streams.forEach { stream ->
                    stream.stream_links.forEach { link ->
                        if (link.url.isBlank()) return@forEach

                        val headers = (link.request_headers ?: emptyList())
                            .associate { it.key to it.value }.toMutableMap()
                        if (!headers.containsKey("User-Agent"))
                            headers["User-Agent"] = ua

                        val referer = headers["Referer"] ?: mainUrl
                        val isHls   = link.type.equals("hls", ignoreCase = true) ||
                                      link.url.contains(".m3u8")
                        val label   = listOf(source.name, stream.name, link.name)
                            .filter { it.isNotBlank() }.joinToString(" - ")

                        // FIX: dùng newExtractorLink thay vì constructor deprecated
                        callback(
                            newExtractorLink(
                                source = source.name,
                                name   = label,
                                url    = link.url,
                                type   = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                            ) {
                                this.referer  = referer
                                this.quality  = Qualities.Unknown.value
                                this.headers  = headers
                            }
                        )
                        found = true
                    }
                }
            }
        }
        return found
    }
}
