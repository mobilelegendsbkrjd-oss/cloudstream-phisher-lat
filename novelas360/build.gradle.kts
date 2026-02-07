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
    implementation(project(":CloudStream3")) // proyecto local
    implementation("org.jsoup:jsoup:1.15.3")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.8.10")
}

version = 2

cloudstream {
    description = "(Mexican) Novelas Extension"
    language = "mx"
    authors = listOf("bkrjd")

    status = 1 // 0: Down, 1: Ok, 2: Slow, 3: Beta only

    tvTypes = listOf("TvSeries")
    iconUrl = "https://novelas360.com/logo.png"

    isCrossPlatform = false
}
