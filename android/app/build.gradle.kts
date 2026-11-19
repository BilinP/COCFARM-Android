import java.util.Properties

// COCFARM for Android: the same bot (../../farmbot) running on the phone,
// in Python through Chaquopy. The Java side gives it the screen (screen
// capture) and the fingers (an accessibility service).
plugins {
    id("com.android.application")
    id("com.chaquo.python")
}

// This PC's Python 3.10, for preparing the app's Python packages: "python.path=" in
// local.properties, or else Chaquopy looks for "py -3.10" / python3.10 itself.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

// The release key: "release.keystore=" in local.properties names its keystore.properties (storeFile,
// keyAlias), kept outside the repository; its password is in the COCFARM_KEY_PASSWORD environment
// variable, not in any file. Phones only install an update signed with the same key as the app they
// have, so it never changes.
val releasePassword: String? = System.getenv("COCFARM_KEY_PASSWORD")
val releaseKey = localProperties.getProperty("release.keystore")?.let { file(it) }?.takeIf { it.exists() }?.let { path ->
    Properties().apply { path.inputStream().use { load(it) } }
}

// The app's version is COCFARM's (farmbot/__init__.py): updates compare it with GitHub's releases.
val cocfarmVersion = Regex("__version__ = \"([0-9.]+)\"")
    .find(rootProject.file("../farmbot/__init__.py").readText())!!.groupValues[1]

android {
    namespace = "com.cocfarm.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cocfarm.app"
        minSdk = 24  // Python 3.10's OpenCV for Android needs Android 7
        targetSdk = 36
        versionCode = cocfarmVersion.split(".").fold(0) { code, part -> code * 100 + part.toInt() }  // 0.6.1 -> 601
        versionName = cocfarmVersion
    }

    // One app per processor, each about half the size of both together: Python, OpenCV and the
    // text reader are native code, built for each. "universal" has both (phones, and x86_64
    // emulators such as LDPlayer and BlueStacks): it's what versions before 17 Nov 2026 download
    // to update (COCFARM-android.apk), so it's still published. Newer ones download their own
    // (farmbot/update.py's asset by processor, see cocfarm_settings.update_assets()).
    // (Flavors, not an ABI split: Chaquopy packs its Python per flavor, not per split.)
    flavorDimensions += "abi"
    productFlavors {
        create("universal") {
            dimension = "abi"
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        create("arm64") {
            dimension = "abi"
            ndk { abiFilters += listOf("arm64-v8a") }
        }
        create("x86") {
            dimension = "abi"
            ndk { abiFilters += listOf("x86_64") }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        create("release") {
            if (releaseKey != null) {
                storeFile = file(releaseKey.getProperty("storeFile"))
                storePassword = releasePassword
                keyAlias = releaseKey.getProperty("keyAlias")
                keyPassword = releasePassword
            }
        }
    }

    buildTypes {
        release {
            // R8 drops the unused parts of the libraries (Material, ML Kit) and the resources
            // nothing uses. Python calls the app's own classes by name, unseen by R8: kept in
            // proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        // A release build signed with the debug key, to try R8's app on a test phone
        // (one with a debug build on it) without the release key.
        create("shrunk") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
}

// A release build without the key would make an app that can never be updated (every flavor's).
tasks.matching { it.name == "assembleRelease" || (it.name.startsWith("package") && it.name.endsWith("Release")) }.configureEach {
    doFirst {
        check(releaseKey != null) {
            "No release key: set release.keystore= in android/local.properties to the key's keystore.properties"
        }
        check(!releasePassword.isNullOrEmpty()) {
            "No release key password: set the COCFARM_KEY_PASSWORD environment variable (then open a new terminal)"
        }
    }
}

chaquopy {
    defaultConfig {
        version = "3.10"  // the newest Python with an Android build of OpenCV
        localProperties.getProperty("python.path")?.let { buildPython(it) }
        pip {
            install("opencv-python")  // brings NumPy
            install("tomli")  // reads farmbot.toml (tomllib is built in from 3.11)
        }
    }
}

// The bot itself: COCFARM's farmbot package, minus the Windows-only parts
// (listed in bot-excludes.txt, which ../check_imports.py reads too).
val botSources = layout.buildDirectory.dir("botsrc")
val botExcludes = file("bot-excludes.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
val copyBot by tasks.registering(Sync::class) {
    inputs.file("bot-excludes.txt")
    from(rootProject.file("../farmbot")) {
        into("farmbot")
        exclude(botExcludes)
    }
    into(botSources)
}
chaquopy.sourceSets.getByName("main").srcDir(botSources)
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("PythonSources") }.configureEach { dependsOn(copyBot) }

// The button pictures (../../templates), as the Windows app's zip has them: Pictures.java puts
// them in COCFARM's folder, keeping any the player changed. index.txt lists them (assets can't
// be listed quickly). Not redact/: the player's own Clan Castle, never shared.
val pictureAssets = layout.buildDirectory.dir("pictures")
val copyPictures by tasks.registering(Sync::class) {
    from(rootProject.file("../templates")) {
        into("templates")
        exclude("redact/**", "README.md", "**/Thumbs.db", "**/desktop.ini")
    }
    into(pictureAssets)
    doLast {
        val root = pictureAssets.get().asFile.resolve("templates")
        val names = root.walkTopDown().filter { it.isFile && it.name != "index.txt" }
            .map { it.relativeTo(root).invariantSeparatorsPath }.sorted().toList()
        root.resolve("index.txt").writeText(names.joinToString("\n"))
    }
}
android.sourceSets.getByName("main").assets.srcDir(pictureAssets)
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach { dependsOn(copyPictures) }

dependencies {
    // The look: Material 3 cards, switches, sliders, text fields and dialogs.
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.core:core-splashscreen:1.0.1")  // the launch screen, on every Android version
    // Reading words on screen (Windows' OCR on the PC): the model is in the app, so it works offline.
    implementation("com.google.mlkit:text-recognition:16.0.1")
}

// Release builds' lint reads the generated folders too.
tasks.matching { it.name.contains("lint", ignoreCase = true) }.configureEach { dependsOn(copyPictures, copyBot) }
