plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
    id("signing")
}

group = "com.applooma"
version = "0.4.9"

android {
    namespace = "com.applooma.rtc"
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

    publishing {
        singleVariant("release") {
            // Maven Central requires sources and javadoc alongside the artifact.
            withSourcesJar()
            withJavadocJar()
        }
    }
}

dependencies {
    // api, not implementation: a Java consumer's compiler resolves every type
    // in AppRemoteUser's signatures, internal members included, and failed
    // with "cannot access VideoTrack" when the core was runtime-only.
    api("com.applooma:rtc-core-android:2.12.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // The engine logs through Timber (already on the runtime classpath via the core); compile-only here for enableDebugLogging.
    compileOnly("com.jakewharton.timber:timber:4.7.0")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.applooma"
            artifactId = "rtc-android"
            version = project.version.toString()
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("AppLooma RTC Android SDK")
                description.set("Real-time voice, video, live streaming and audio rooms for Android")
                url.set("https://docs.applooma.dev/sdk/android")
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
