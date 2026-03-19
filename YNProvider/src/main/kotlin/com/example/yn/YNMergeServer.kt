package com.example.yn

import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL

object YNMergeServer {

    private const val TAG = "YNMergeServer"
    const val PORT = 52848
    const val HOST = "http://127.0.0.1:$PORT"

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null

    fun masterUrl(id: String) = "$HOST/master/$id"

    fun ensureStarted() {
        if (running) return
        running = true
        Thread({
            try {
                serverSocket = ServerSocket(PORT)
                Log.i(TAG, "YNMergeServer started on port $PORT")
                while (running) {
                    try {
                        val client = serverSocket!!.accept()
                        Thread({ handleClient(client) }, "YN-Client").apply {
                            isDaemon = true; start()
                        }
                    } catch (e: Exception) {
                        if (running) Log.e(TAG, "Accept error", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server error", e)
            }
        }, "YN-Server").apply { isDaemon = true; start() }
    }

    fun shutdown() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.use {
                val input  = it.getInputStream().bufferedReader()
                val output = it.getOutputStream()
                val requestLine = input.readLine() ?: return
                val path = requestLine.split(" ").getOrNull(1) ?: return
                Log.d(TAG, "Request: $path")
                when {
                    path.startsWith("/master/") -> serveMaster(path.removePrefix("/master/"), output)
                    path.startsWith("/audio/")  -> serveAudio(path.removePrefix("/audio/"), output)
                    else -> write404(output)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Client error", e)
        }
    }

    private fun serveMaster(id: String, out: OutputStream) {
        val entry = YN_CATALOG.find { it.id == id } ?: return write404(out)
        val videoUrl = getYouTubeVideoOnlyStream(id)
            ?: return write500(out, "Cannot get YouTube stream: $id")

        val body = buildString {
            appendLine("#EXTM3U")
            appendLine()
            appendLine("""#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="yn-audio",LANGUAGE="vi",NAME="Audio",DEFAULT=YES,AUTOSELECT=YES,URI="$HOST/audio/$id"""")
            appendLine()
            appendLine("""#EXT-X-STREAM-INF:BANDWIDTH=2000000,AUDIO="yn-audio"""")
            appendLine(videoUrl)
        }
        writeResponse(out, 200, "application/x-mpegURL", body)
    }

    private fun serveAudio(id: String, out: OutputStream) {
        val entry = YN_CATALOG.find { it.id == id } ?: return write404(out)
        val body = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:3")
            appendLine("#EXT-X-TARGETDURATION:99999")
            appendLine("#EXT-X-MEDIA-SEQUENCE:0")
            appendLine("#EXTINF:99999.0,")
            appendLine(entry.audioUrl)
            appendLine("#EXT-X-ENDLIST")
        }
        writeResponse(out, 200, "application/x-mpegURL", body)
    }

    // ── YouTube clients với API key đúng ───────────────────────────

    private data class YtClient(
        val name: String,
        val version: String,
        val apiKey: String,
        val userAgent: String,
        val extra: String = ""
    )

    private val YT_CLIENTS = listOf(
        // ANDROID_TESTSUITE — không cần cipher, URL trực tiếp
        YtClient(
            name      = "ANDROID_TESTSUITE",
            version   = "1.9",
            apiKey    = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8",
            userAgent = "com.google.android.youtube/1.9 (Linux; U; Android 11) gzip",
            extra     = """"androidSdkVersion":30,"""
        ),
        // IOS — không cần cipher
        YtClient(
            name      = "IOS",
            version   = "19.29.1",
            apiKey    = "AIzaSyB-63vPrdThhKuerbB2N_l7Kwwcxj6yUAc",
            userAgent = "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X;)",
            extra     = """"deviceModel":"iPhone16,2","osName":"iPhone","osVersion":"17.5.1.21F90","""
        ),
        // ANDROID thường
        YtClient(
            name      = "ANDROID",
            version   = "19.29.37",
            apiKey    = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8",
            userAgent = "com.google.android.youtube/19.29.37 (Linux; U; Android 11) gzip",
            extra     = """"androidSdkVersion":30,"""
        ),
    )

    private fun getYouTubeVideoOnlyStream(videoId: String): String? {
        for (c in YT_CLIENTS) {
            val apiUrl = "https://www.youtube.com/youtubei/v1/player?key=${c.apiKey}&prettyPrint=false"
            val body = """{"videoId":"$videoId","context":{"client":{${c.extra}"clientName":"${c.name}","clientVersion":"${c.version}","hl":"en","gl":"US"}}}"""

            val res = httpPost(apiUrl, body, "application/json", mapOf(
                "User-Agent"               to c.userAgent,
                "X-Youtube-Client-Name"    to c.name,
                "X-Youtube-Client-Version" to c.version,
                "Origin"                   to "https://www.youtube.com",
            )) ?: continue

            val json = try { JSONObject(res) } catch (e: Exception) { continue }

            val status = json.optJSONObject("playabilityStatus")?.optString("status") ?: continue
            Log.i(TAG, "Client ${c.name} → status=$status for $videoId")
            if (status != "OK") continue

            val url = parseVideoOnlyUrl(json)
            if (url != null) {
                Log.i(TAG, "✅ Got stream via ${c.name} for $videoId")
                return url
            }
        }
        Log.e(TAG, "❌ All clients failed for $videoId")
        return null
    }

    private fun parseVideoOnlyUrl(json: JSONObject): String? {
        return try {
            val formats = json
                .getJSONObject("streamingData")
                .getJSONArray("adaptiveFormats")

            var bestUrl = ""; var bestHeight = 0
            for (i in 0 until formats.length()) {
                val f      = formats.getJSONObject(i)
                val mime   = f.optString("mimeType", "")
                val height = f.optInt("height", 0)
                val url    = f.optString("url", "")
                // Chỉ nhận URL trực tiếp (không có cipher)
                if (mime.startsWith("video/mp4")
                    && url.isNotBlank()
                    && !f.has("signatureCipher")
                    && !f.has("cipher")) {
                    if (height in 480..720 && height > bestHeight) {
                        bestUrl = url; bestHeight = height
                    } else if (bestHeight == 0) {
                        bestUrl = url
                    }
                }
            }
            bestUrl.ifBlank { null }
        } catch (e: Exception) {
            Log.e(TAG, "Parse error", e); null
        }
    }

    // ── HTTP Helpers ───────────────────────────────────────────────

    private fun writeResponse(out: OutputStream, code: Int, ct: String, body: String) {
        val bytes  = body.toByteArray(Charsets.UTF_8)
        val status = if (code == 200) "200 OK" else "$code Error"
        val header = "HTTP/1.1 $status\r\nContent-Type: $ct\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun write404(out: OutputStream) = writeResponse(out, 404, "text/plain", "404 Not Found")
    private fun write500(out: OutputStream, msg: String) = writeResponse(out, 500, "text/plain", msg)

    private fun httpPost(url: String, body: String, ct: String, headers: Map<String, String>): String? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            connectTimeout = 10_000; readTimeout = 20_000
            setRequestProperty("Content-Type", ct)
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            outputStream.write(body.toByteArray(Charsets.UTF_8))
        }
        if (c.responseCode == 200) c.inputStream.bufferedReader().readText()
        else { Log.w(TAG, "HTTP ${c.responseCode} for $url"); null }
    } catch (e: IOException) { Log.e(TAG, "POST error: $url", e); null }
}
