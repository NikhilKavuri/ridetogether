// Top-level build file. Versions are pinned deliberately conservative:
// every one of these has been stable and widely used for a long time, which
// matters because the first real compile of this project happens in CI.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.gms.google-services") version "4.4.2" apply false
}
