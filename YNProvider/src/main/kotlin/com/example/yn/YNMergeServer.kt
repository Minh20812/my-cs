package com.example.yn

import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * YNMergeServer v5 — Không tự gọi YouTube API nữa.
 *
 * Flow mới:
 *   1. YNProvider.loadLinks() dùng CloudStream loadExtractor lấy YouTube URL
 *   2. Cache URL vào videoUrlCache
 *   3. Trả về master URL cho ExoPlayer
 *   4. Server đọc từ cache → tạo HLS manifest
 *
 * Không cần InnerTube API, không cần API key, không cần cipher decode.
 */
object YNMergeServer {

    private const val TAG = "YNMergeServer"
    const val PORT = 52848
    const val HOST = "http://127.0.0.1:$PORT"

    // Cache: videoId → YouTube stream URL (set bởi YNProvider.loadLinks)
    val videoUrlCache = ConcurrentHashMap<String, String>()

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
        videoUrlCache.clear()
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

        // Lấy URL từ cache (đã được set bởi loadLinks)
        val videoUrl = videoUrlCache[id]
        if (videoUrl == null) {
            Log.e(TAG, "No cached URL for $id")
            return write500(out, "No video URL cached for: $id")
        }

        Log.i(TAG, "Serving master for $id, videoUrl length=${videoUrl.length}")

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
}
