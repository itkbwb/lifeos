plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    // Only actually applied per-module when a google-services.json exists
    // (see app/build.gradle.kts) - the plugin itself is declared here but
    // stays inert (and doesn't require the Firebase Gradle plugin repo to
    // resolve anything extra) until then, so builds without a Firebase
    // project configured yet keep working.
    id("com.google.gms.google-services") version "4.4.2" apply false
}
