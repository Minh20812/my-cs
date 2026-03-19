version = 1

cloudstream {
    description = "Youtube Narrator Provider - Video YouTube + Audio Archive.org"
    authors = listOf("community")
    status = 1
    tvTypes = listOf("Movie")
    iconUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/f/fc/Natgeo_logo.svg/200px-Natgeo_logo.svg.png"
}

android {
    namespace = "com.example.yn"
}

dependencies {
    val implementation by configurations
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
