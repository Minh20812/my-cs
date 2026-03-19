package com.example.yn

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * YNMergeServer — Local HTTP server ghép video YouTube + audio Archive.org.
 *
 * Endpoints:
 *   /master/{id}  → HLS master playlist (video + audio group)
 *   /audio/{id}   → HLS audio playlist bọc file OGG/MP3 từ Archive.org
 *
 * Tại sao cần /audio/{id}.m3u8 riêng?
 *   HLS spec yêu cầu EXT-X-MEDIA URI phải là HLS playlist (.m3u8),
 *   không thể trỏ thẳng tới file .ogg/.mp3.
 *   Server này tạo audio playlist bọc URL Archive.org thành HLS hợp lệ.
 */
class YNMergeServer private constructor(port: Int) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "YNMergeServer"
        const val PORT = 52848
        const val HOST = "http://127.0.0.1:$PORT"

        @Volatile private var instance: YNMergeServer? = null

        fun ensureStarted() {
            if (instance == null) synchronized(this) {
                if (instance == null) {
                    instance = YNMergeServer(PORT).also {
                        it.start(SOCKET_READ_TIMEOUT, false)
                        Log.i(TAG, "YNMergeServer started on port $PORT")
                    }
                }
            }
        }

        fun shutdown() {
            instance?.stop()
            instance = null
        }

        fun masterUrl(videoId: String) = "$HOST/master/$videoId"
    }

    // ── Routing ────────────────────────────────────────────────────

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri ?: return notFound()
        return try {
            when {
                path.startsWith("/master/") -> serveMaster(path)
                path.startsWith("/audio/")  -> serveAudioPlaylist(path)
                else -> notFound()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error serving $path", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT,
                "YNMergeServer error: ${e.message}"
            )
        }
    }

    // ── /master/{id} — HLS Master Playlist ────────────────────────

    private fun serveMaster(path: String): Response {
        val id    = path.removePrefix("/master/")
        val entry = YN_CATALOG.find { it.id == id }
            ?: return newFixedLengthResponse(
                Response.Status.NOT_FOUND, MIME_PLAINTEXT,
                "Video không tồn tại trong catalog: $id"
            )

        val videoUrl = getYouTubeVideoOnlyStream(id)
            ?: return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT,
                "Không lấy được video stream từ YouTube. ID: $id"
            )

        // Audio URI trỏ tới /audio/{id} — endpoint HLS audio playlist hợp lệ
        // KHÔNG trỏ thẳng tới .ogg (HLS spec yêu cầu URI phải là .m3u8)
        val audioPlaylistUrl = "$HOST/audio/$id"

        val manifest = buildString {
            appendLine("#EXTM3U")
            appendLine()
            // Audio rendition: URI là HLS playlist do chính server này serve
            // CODECS="mp4a.40.2" bỏ đi vì audio thực tế là OGG, không phải AAC
            appendLine(
                """#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="yn-audio",LANGUAGE="vi",""" +
                """NAME="Audio",DEFAULT=YES,AUTOSELECT=YES,URI="$audioPlaylistUrl""""
            )
            appendLine()
            // Video stream — bỏ CODECS để tránh mismatch với audio thực tế
            appendLine("""#EXT-X-STREAM-INF:BANDWIDTH=2000000,AUDIO="yn-audio"""")
            appendLine(videoUrl)
        }

        Log.d(TAG, "Master manifest for $id:\n$manifest")
        return newFixedLengthResponse(Response.Status.OK, "application/x-mpegURL", manifest)
    }

    // ── /audio/{id} — HLS Audio Playlist wrapping Archive.org file ─

    /**
     * Tạo HLS audio playlist hợp lệ bọc file OGG/MP3 từ Archive.org.
     *
     * ExoPlayer đọc playlist này và stream audio từ Archive.org trực tiếp.
     * Seek hoạt động bình thường vì Archive.org support HTTP Range requests.
     *
     * Duration dùng giá trị lớn (99999) vì chúng ta không biết chính xác —
     * ExoPlayer sẽ tự detect từ file audio header.
     */
    private fun serveAudioPlaylist(path: String): Response {
        val id    = path.removePrefix("/audio/")
        val entry = YN_CATALOG.find { it.id == id }
            ?: return newFixedLengthResponse(
                Response.Status.NOT_FOUND, MIME_PLAINTEXT,
                "Video không tồn tại trong catalog: $id"
            )

        // Playlist HLS đơn giản — 1 segment duy nhất = toàn bộ file audio
        val playlist = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:3")
            appendLine("#EXT-X-TARGETDURATION:99999")
            appendLine("#EXT-X-MEDIA-SEQUENCE:0")
            appendLine("#EXTINF:99999.0,")
            appendLine(entry.audioUrl)   // URL trực tiếp tới .ogg/.mp3 Archive.org
            appendLine("#EXT-X-ENDLIST")
        }

        Log.d(TAG, "Audio playlist for $id:\n$playlist")
        return newFixedLengthResponse(Response.Status.OK, "application/x-mpegURL", playlist)
    }

    // ── YouTube InnerTube API ──────────────────────────────────────

    private fun getYouTubeVideoOnlyStream(videoId: String): String? {
        val body = """
            {
              "videoId": "$videoId",
              "context": {
                "client": {
                  "clientName": "TVHTML5",
                  "clientVersion": "7.20220325",
                  "hl": "en",
                  "gl": "US"
                }
              }
            }
        """.trimIndent()

        val response = httpPost(
            url         = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            body        = body,
            contentType = "application/json",
            headers     = mapOf(
                "User-Agent" to "Mozilla/5.0 (SMART-TV; Linux; Tizen 5.0) AppleWebKit/537.36",
                "Origin"     to "https://www.youtube.com",
            )
        ) ?: return null

        return try {
            val formats = JSONObject(response)
                .getJSONObject("streamingData")
                .getJSONArray("adaptiveFormats")

            var bestUrl    = ""
            var bestHeight = 0

            for (i in 0 until formats.length()) {
                val f      = formats.getJSONObject(i)
                val mime   = f.optString("mimeType", "")
                val height = f.optInt("height", 0)
                val url    = f.optString("url", "")

                if (mime.startsWith("video/mp4") && url.isNotBlank()) {
                    if (height in 480..720 && height > bestHeight) {
                        bestUrl = url; bestHeight = height
                    } else if (bestHeight == 0) {
                        bestUrl = url
                    }
                }
            }

            bestUrl.ifBlank { null }
        } catch (e: Exception) {
            Log.e(TAG, "YouTube parse error: $videoId", e)
            null
        }
    }

    // ── HTTP Helper ────────────────────────────────────────────────

    private fun httpPost(
        url: String, body: String, contentType: String,
        headers: Map<String, String> = emptyMap(),
    ): String? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput      = true
            connectTimeout = 10_000
            readTimeout    = 15_000
            setRequestProperty("Content-Type", contentType)
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            outputStream.write(body.toByteArray(Charsets.UTF_8))
        }
        if (conn.responseCode == 200) conn.inputStream.bufferedReader().readText() else null
    } catch (e: IOException) {
        Log.e(TAG, "POST $url", e)
        null
    }

    private fun notFound() =
        newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404 Not Found")
}
