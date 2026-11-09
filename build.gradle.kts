plugins {
    idea
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}

// Keep IDE indexing on editable sources, away from AVD locks and build/delivery artifacts.
idea {
    module {
        excludeDirs.addAll(files(".android", ".tmp", "dist", "qa", "node_modules", "upstream"))
    }
}
