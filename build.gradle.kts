plugins {
    // Versions in settings.gradle.kts for standalone, parent classpath for submodule
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("org.jlleitschuh.gradle.ktlint")
}

android {
    namespace = "io.getdesmo.tracesdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        targetSdk = 34

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        // SDK version available at runtime via BuildConfig.SDK_VERSION
        buildConfigField("String", "SDK_VERSION", "\"1.0.0\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
}

// ktlint configuration
ktlint {
    version.set("1.1.1")
    android.set(true)
    outputToConsole.set(true)
    ignoreFailures.set(false) // Fail build on lint errors

    filter { exclude("**/generated/**") }
}

// --- Maven Central publishing + signing (disabled for now) -------------------
// The following block was used for publishing to Maven Central directly.
// Since we're using JitPack / simple AAR builds for now, it is commented out
// to keep things simpler and avoid GPG / Sonatype configuration.
//
// afterEvaluate {
//     publishing {
//         publications {
//             create<MavenPublication>("release") {
//                 from(components["release"])
//                 groupId = "io.getdesmo"
//                 artifactId = "desmo-android-sdk"
//                 version = "0.1.2"
//
//                 pom {
//                     name.set("Desmo Android SDK")
//                     description.set("The Official Android SDK for Desmo, the delivery
// intelligence platform.")
//                     url.set("https://getdesmo.io")
//
//                     licenses {
//                         license {
//                             name.set("MIT License")
//                             url.set("https://opensource.org/licenses/MIT")
//                         }
//                     }
//
//                     developers {
//                         developer {
//                             id.set("desmo")
//                             name.set("Desmo Engineering")
//                             email.set("engineering@getdesmo.io")
//                         }
//                     }
//
//                     scm {
//                         connection.set("scm:git:git://github.com/getdesmo/desmo-android-sdk.git")
//
// developerConnection.set("scm:git:ssh://github.com/getdesmo/desmo-android-sdk.git")
//                         url.set("https://github.com/getdesmo/desmo-android-sdk")
//                     }
//                 }
//             }
//         }
//
//         repositories {
//             maven {
//                 name = "sonatype"
//                 val releasesRepoUrl =
// uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
//                 val snapshotsRepoUrl =
// uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
//                 url = if (version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl else
// releasesRepoUrl
//
//                 credentials {
//                     username = findProperty("ossrhUsername") as String?
//                     password = findProperty("ossrhPassword") as String?
//                 }
//             }
//         }
//     }
// }
//
// signing {
//     val localProperties = Properties()
//     val localPropertiesFile = rootProject.file("local.properties")
//     if (localPropertiesFile.exists()) {
//         localProperties.load(FileInputStream(localPropertiesFile))
//     }
//
//     fun getProp(name: String): String? {
//         return project.findProperty(name) as? String ?: localProperties.getProperty(name)
//     }
//
//     val password = getProp("signing.password")
//     val secretKeyRingFile = getProp("signing.secretKeyRingFile")
//
//     if (password != null && secretKeyRingFile != null) {
//         val keyFile = file(secretKeyRingFile)
//         if (keyFile.exists()) {
//             val key = keyFile.readText()
//             useInMemoryPgpKeys(key, password)
//             sign(publishing.publications)
//         }
//     }
// }

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib")

    // Coroutines for async APIs
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // JSON serialization for requests/responses
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // OkHttp for HTTP networking (wired in Phase 2)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // AndroidX core for permission helpers and system services
    implementation("androidx.core:core-ktx:1.13.1")

    // Room for persistent telemetry queue (offline support)
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
