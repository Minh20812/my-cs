version = 1
cloudstream {
    description = "Xem the thao truc tiep - ThapcamTV"
    authors = listOf("community")
    status = 1
    tvTypes = listOf("Live")
    iconUrl = "https://tctv.pro/10cam-logo-app-light.jpg"
}
android {
    namespace = "com.example.thapcam"
    
    buildFeatures {
        buildConfig = true
    }
    
    defaultConfig {
        buildConfigField("String", "API_URL", "\"${System.getenv("API_URL") ?: ""}\"")
    }
}
