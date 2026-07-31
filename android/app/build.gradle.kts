import java.io.FileInputStream
import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI provides the Firebase config as a base64-encoded secret (same pattern as
// the release keystore below) since google-services.json can't be committed.
// Without the secret (e.g. a fork's CI run, or before a Firebase project
// exists), this is a no-op and the plugin stays unapplied further down.
val ciGoogleServicesJsonBase64 = System.getenv("GOOGLE_SERVICES_JSON_BASE64")
val googleServicesJsonFile = file("google-services.json")
if (ciGoogleServicesJsonBase64 != null && !googleServicesJsonFile.exists()) {
    googleServicesJsonFile.writeBytes(Base64.getDecoder().decode(ciGoogleServicesJsonBase64))
}

// The google-services plugin hard-fails the build if applied without a
// google-services.json present (chapter: notifications, server-pushed via
// FCM) - applying it conditionally keeps local/dev/CI builds working before
// a Firebase project has been created, per the user's explicit go-ahead but
// "not right now" on Pi-side rollout.
val googleServicesJsonPresent = googleServicesJsonFile.exists()
if (googleServicesJsonPresent) {
    apply(plugin = "com.google.gms.google-services")
}

fun computeVersionCode(v: String): Int {
    val parts = v.split(".").map { it.toIntOrNull() ?: 0 }
    val major = parts.getOrElse(0) { 0 }
    val minor = parts.getOrElse(1) { 0 }
    val patch = parts.getOrElse(2) { 0 }
    return major * 10_000 + minor * 100 + patch
}

val appVersionName = (project.findProperty("versionOverride") as String?) ?: "0.1.0"
val appVersionCode = computeVersionCode(appVersionName)

// CI provides a base64-encoded keystore via env var so every release build is
// signed with the same key. Without it (local/dev builds) we fall back to the
// auto-generated debug key so the project still builds out of the box.
val ciKeystoreBase64 = System.getenv("ANDROID_KEYSTORE_BASE64")
val decodedKeystore: File? = ciKeystoreBase64?.let {
    val file = File(layout.buildDirectory.asFile.get(), "ci-release.keystore")
    file.parentFile.mkdirs()
    file.writeBytes(Base64.getDecoder().decode(it))
    file
}

android {
    namespace = "com.lifeos.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lifeos.app"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName

        buildConfigField("String", "UPDATE_REPO", "\"itkbwb/lifeos\"")
        buildConfigField("String", "UPDATE_CHECK_BASE_URL", "\"https://api.github.com\"")
        buildConfigField("boolean", "FCM_CONFIGURED", googleServicesJsonPresent.toString())
    }

    signingConfigs {
        if (decodedKeystore != null) {
            create("release") {
                storeFile = decodedKeystore
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (decodedKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        // Debug builds hit the local dev server's stub instead of the real GitHub
        // API - avoids the update-available dialog hijacking the emulator mid-test
        // (see UpdateChecker; 10.0.2.2 is the emulator's alias for the host machine).
        debug {
            buildConfigField("String", "UPDATE_CHECK_BASE_URL", "\"http://10.0.2.2:8000\"")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes.add("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")

    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    // play-services-basement (pulled in transitively by firebase-messaging)
    // depends on fragment:1.1.0, which trips the release lintVital check
    // against androidx.activity's ActivityResult APIs (needs >=1.3.0).
    implementation("androidx.fragment:fragment-ktx:1.8.3")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Always on the classpath (compiles fine either way) - only the plugin
    // application above is conditional on google-services.json existing.
    // Without it, FirebaseApp.initializeApp() has nothing to configure
    // itself with and fails at runtime; code using it must check
    // BuildConfig.FCM_CONFIGURED first, same pattern as the notification
    // permission check.
    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    implementation("com.google.firebase:firebase-messaging-ktx")

    testImplementation("junit:junit:4.13.2")
}
