import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

/**
 * Every version of the app must be signed with the same key, or Android refuses the update and
 * the app has to be reinstalled, which erases its data (pairings and the show list). Point
 * `clicker.signing` in local.properties at a properties file kept outside the repository:
 *
 *   storeFile=/path/to/clicker-signing.keystore
 *   storePassword=...
 *   keyAlias=...
 *   keyPassword=...
 *
 * Without it, builds use the standard Android debug key, which is fine for trying the app.
 */
val signing: Properties? = run {
    val local = rootProject.file("local.properties").takeIf { it.isFile }?.let { f ->
        Properties().apply { f.inputStream().use(::load) }
    }
    val path = local?.getProperty("clicker.signing") ?: System.getenv("CLICKER_SIGNING")
    path?.let(::file)?.takeIf { it.isFile }?.let { f -> Properties().apply { f.inputStream().use(::load) } }
}

android {
    namespace = "dev.clicker.remote"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.clicker.remote"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    signingConfigs {
        if (signing != null) {
            create("clicker") {
                storeFile = file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        val clickerKey = signingConfigs.findByName("clicker")
        debug {
            if (clickerKey != null) signingConfig = clickerKey
        }
        release {
            isMinifyEnabled = false
            if (clickerKey != null) signingConfig = clickerKey
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
}

ksp {
    // Keeps a copy of each database version so future upgrades can be checked and migrated.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // LG webOS control (WebSocket) and show data downloads
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Apple TV Companion protocol crypto: SRP math uses java.math, the rest is BouncyCastle
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")

    // Show list database (SQLite)
    val room = "2.6.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
