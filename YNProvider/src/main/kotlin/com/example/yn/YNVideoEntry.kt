package com.example.yn

/**
 * Mỗi entry = 1 video.
 *
 * [id]       = YouTube video ID = Archive.org item ID
 * [title]    = Tên hiển thị trong CloudStream
 * [category] = Nhóm hiển thị trên trang chủ
 * [audioUrl] = URL trực tiếp tới file audio trên Archive.org
 *
 * Cách lấy audioUrl:
 *   1. Vào https://archive.org/details/VIDEO_ID
 *   2. Xem mục "DOWNLOAD OPTIONS"
 *   3. Copy link "OGG VORBIS download" hoặc "VBR MP3 download"
 *      → thường có dạng: https://archive.org/download/VIDEO_ID/VIDEO_ID.ogg
 */
data class YNVideoEntry(
    val id: String,
    val title: String,
    val description: String = "",
    val category: String = "Youtube Narrator",
    val audioUrl: String,
)

// ════════════════════════════════════════════════════════════
//  DANH SÁCH VIDEO — THÊM VIDEO MỚI VÀO ĐÂY
// ════════════════════════════════════════════════════════════
val YN_CATALOG = listOf(

    YNVideoEntry(
        id          = "6Sb3TAWzSIA",
        title       = "Hunting Down Smugglers | To Catch A Smuggler",
        description = "Follow along with government agencies working to crack down on smuggling operations. Duration: 2:12:18",
        category    = "National Geographic",
        audioUrl    = "https://archive.org/download/6Sb3TAWzSIA/6Sb3TAWzSIA.ogg",
    ),

    YNVideoEntry(
        id          = "O1KESK7UzjY",
        title       = "World War II Wreckages | Drain the Oceans MEGA Episode",
        description = "World War II wreckages discovered across the globe.",
        category    = "National Geographic",
        audioUrl    = "https://archive.org/download/O1KESK7UzjY/O1KESK7UzjY.ogg",
    ),

    YNVideoEntry(
        id          = "irxcd5kGCWY",
        title       = "Allies vs. Japan: Pacific War Battle | Buried Secrets of WWII",
        description = "Full episode — Buried Secrets of WWII.",
        category    = "National Geographic",
        audioUrl    = "https://archive.org/download/irxcd5kGCWY/irxcd5kGCWY.ogg",
    ),

    // ── THÊM VIDEO MỚI Ở ĐÂY ────────────────────────────────
    //
    // YNVideoEntry(
    //     id          = "YOUTUBE_ID",
    //     title       = "Tên video hiển thị",
    //     description = "Mô tả ngắn (tuỳ chọn)",
    //     category    = "Tên nhóm",
    //     audioUrl    = "https://archive.org/download/YOUTUBE_ID/YOUTUBE_ID.ogg",
    // ),
    //
    // ─────────────────────────────────────────────────────────
)
