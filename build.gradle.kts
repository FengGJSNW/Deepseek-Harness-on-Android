plugins {
    idea
    id("com.android.application") version "9.4.1" apply false
    id("com.android.library") version "9.4.1" apply false
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}

// Keep IDE indexing on editable sources, away from AVD locks and build/delivery artifacts.
idea {
    module {
        excludeDirs.addAll(files(".android", ".tmp", "dist", "qa", "node_modules", "upstream"))
    }
}
