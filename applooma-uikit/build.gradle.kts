plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("maven-publish")
    id("signing")
}

group = "com.applooma"
version = "0.2.20"

android {
    namespace = "com.applooma.uikit"
    compileSdk = 34
    defaultConfig { minSdk = 24 }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

dependencies {
    api(project(":applooma-rtc"))
    api(platform("androidx.compose:compose-bom:2024.09.03"))
    api("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.applooma"
            artifactId = "uikit-android"
            version = project.version.toString()
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("AppLooma RTC UIKit for Android")
                description.set("Drop-in Jetpack Compose screens for live streaming, voice rooms and 1-to-1 calls")
                url.set("https://docs.applooma.dev/uikit/android")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("applooma")
                        name.set("AppLooma LLC")
                        email.set("support@applooma.dev")
                    }
                }
                scm {
                    url.set("https://github.com/AppLooma-RTC/Android-SDK")
                    connection.set("scm:git:git://github.com/AppLooma-RTC/Android-SDK.git")
                    developerConnection.set("scm:git:ssh://git@github.com/AppLooma-RTC/Android-SDK.git")
                }
            }
        }
    }
}

// Maven Central rejects unsigned artifacts. The key is supplied in CI through
// environment variables so it never lives in the repository; locally, an unset
// key simply skips signing so a normal build still works.
signing {
    val signingKey = System.getenv("SIGNING_KEY")
    val signingPassphrase = System.getenv("SIGNING_PASSPHRASE")
    if (!signingKey.isNullOrBlank()) {
        useInMemoryPgpKeys(signingKey, signingPassphrase)
        sign(publishing.publications)
    } else {
        logger.lifecycle("SIGNING_KEY is not set - artifacts will not be signed")
    }
}
