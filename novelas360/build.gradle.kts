plugins {
    id("com.android.library")
    id("kotlin-android")
    id("kotlin-kapt")
}

android {
    compileSdk = 33

    defaultConfig {
        minSdk = 21
        targetSdk = 33
    }

    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin")
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.8.10")
    implementation("com.lagradost:cloudstream3:latest-release")
    implementation("org.jsoup:jsoup:1.15.3")
}

version = 2

cloudstream {
    description = "(Mexican) Novelas Extension"
    language = "mx"
    authors = listOf("bkrjd")

    // Status:
    // 0: Down, 1: Ok, 2: Slow, 3: Beta only
    status = 1

    // List of video source types (CloudStream TVType)
    tvTypes = listOf("TvSeries") 

    iconUrl = "https://novelas360.com/logo.png"

    isCrossPlatform = false
}
