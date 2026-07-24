plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

group = "com.liolive"
version = "0.1.0"

android {
    namespace = "com.liolive.rtc"
    compileSdk = 34

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    publishing { singleVariant("release") }
}

dependencies {
    // Media engine (internal implementation detail)
    api("io.livekit:livekit-android:2.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.liolive"
            artifactId = "rtc-android"
            version = project.version.toString()
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("Lio Live RTC Android SDK")
                description.set("Real-time voice, video, live streaming and audio rooms by AppLooma LLC")
                url.set("https://applooma.dev/dashboard/docs")
            }
        }
    }
}
