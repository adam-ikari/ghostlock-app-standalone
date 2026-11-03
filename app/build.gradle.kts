@file:Suppress("UnstableApiUsage")

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Keep this module's output under the repository-root build/ directory.
layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("app"))

val appName = "GhostLock"
val appVersionName = "1.2"

val gitVersionCode = runCatching {
    providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
    }.standardOutput.asText.get().trim().toInt()
}.getOrElse {
    logger.warn("git rev-list failed (${it.message}); versionCode falls back to 1")
    1
}

/* The commit count is a convenient versionCode, but it moves backwards when the
 * history is rewritten or squashed, and an update install refuses a lower
 * versionCode. VERSION_CODE pins it explicitly; the count stays the fallback
 * for a checkout that has no property. */
val appVersionCode = (project.findProperty("VERSION_CODE") as String?)
    ?.trim()?.toIntOrNull() ?: gitVersionCode

val buildInfoSrc = layout.buildDirectory.dir("generated/source/buildInfo")

val generateBuildInfo = tasks.register("generateBuildInfo") {
    description = "generateBuildInfo"
    val outputDirectory = buildInfoSrc
    outputs.dir(outputDirectory)
    // Always rewrite so the debug UI shows the timestamp of the installed build.
    outputs.upToDateWhen { false }
    doLast {
        val directory = outputDirectory.get().asFile.resolve("com/ghostlock/app")
        directory.mkdirs()
        // CPP-BUILD-02: this task is the only writer of the directory, so any
        // other file is a stale duplicate that must not reach the Kotlin build.
        directory.listFiles()?.forEach { stale -> if (stale.isFile) stale.delete() }
        val timeMillis = System.currentTimeMillis()
        val label = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
            .format(Date(timeMillis))
        directory.resolve("BuildInfo.kt").writeText(
            buildString {
                appendLine("package com.ghostlock.app")
                appendLine()
                appendLine("/** Generated per build; shown only by debug builds. */")
                appendLine("object BuildInfo {")
                appendLine("    const val BUILD_TIME_EPOCH_MILLIS: Long = ${timeMillis}L")
                appendLine("    const val BUILD_TIME_LABEL: String = \"$label\"")
                appendLine("}")
            },
        )
    }
}

android {
    namespace = "com.ghostlock.app"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }
    defaultConfig {
        applicationId = "com.ghostlock.app"
        minSdk = 31
        /* Upstream targets 37 (Android 17 preview). A targetSdk newer than the
         * platform the device runs installs in a restricted state on that
         * platform: the package stays disabled, its components do not resolve
         * and no data directory is created (verified on Android 16 / SDK 36:
         * "Activity class ... does not exist" right after a successful
         * install). Targeting the platform we ship on keeps the app usable
         * there; compileSdk stays at 37 so the newer APIs remain available. */
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }
    androidResources {
        localeFilters += listOf("en", "zh")
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    sourceSets {
        named("main") {
            kotlin.directories.add(buildInfoSrc.get().asFile.absolutePath)
        }
    }
    // Signing inputs come from gradle.properties (committed on purpose: this is a
    // self-use build, and losing the keystore means no update can be installed
    // over an existing copy). local.properties still works for local overrides.
    //
    // CI passes the signing material through the environment, so a usable
    // KEYSTORE_PATH in the environment wins over the committed one — otherwise
    // the upstream release pipeline would sign with the keystore checked into
    // this repository instead of its own secret. An environment keystore that
    // is missing or empty (a CI job without the signing secret) falls back to
    // the committed keystore.
    val localProps = Properties()
    runCatching { localProps.load(project.rootProject.file("local.properties").inputStream()) }
    fun fromEnv(name: String): String? = System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }
    fun fromProject(name: String): String? =
        (project.findProperty(name) as String?)?.trim()?.takeIf { it.isNotEmpty() }
            ?: localProps.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }
    val envKeystore = fromEnv("KEYSTORE_PATH")?.let(::file)?.takeIf { it.isFile && it.length() > 0L }
    val envKeystoreComplete = envKeystore != null &&
        listOf("KEYSTORE_PASS", "KEY_ALIAS", "KEY_PASSWORD").all { fromEnv(it) != null }
    check(envKeystore == null || envKeystoreComplete) {
        "KEYSTORE_PATH is set in the environment but the other signing variables are not; " +
            "set KEYSTORE_PASS, KEY_ALIAS and KEY_PASSWORD, or unset KEYSTORE_PATH to use " +
            "the keystore committed in the repository"
    }
    fun signingSetting(name: String): String? =
        if (envKeystoreComplete) fromEnv(name) else fromProject(name) ?: fromEnv(name)
    val keystorePath = signingSetting("KEYSTORE_PATH")
    val keystorePwd = signingSetting("KEYSTORE_PASS")
    val alias = signingSetting("KEY_ALIAS")
    val pwd = signingSetting("KEY_PASSWORD")
    val keystoreFile = keystorePath?.let(::file)?.takeIf { it.isFile && it.length() > 0L }
    if (keystoreFile != null) {
        signingConfigs {
            create("release") {
                storeFile = keystoreFile
                storePassword = keystorePwd
                keyAlias = alias
                keyPassword = pwd
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }
    buildTypes {
        release {
            optimization.enable = true
            vcsInfo.include = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (keystoreFile != null) "release" else "debug")
        }
        debug {
            signingConfig = signingConfigs.getByName(if (keystoreFile != null) "release" else "debug")
        }
    }
    buildFeatures {
        buildConfig = true
        aidl = true
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    // The native exploit runtime is deliberately shipped only for arm64.
    lint {
        disable += "ChromeOsAbiSupport"
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            excludes += "lib/*/libandroidx.graphics.path.so"
        }
        dex {
            useLegacyPackaging = true
        }
    }
    splits {
        abi {
            isEnable = true
            isUniversalApk = false
            reset()
            include("arm64-v8a")
        }
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) {
        it.packaging.resources.excludes
            .add("**")
    }
}

base {
    archivesName.set("$appName-v$appVersionName($appVersionCode)")
}

kotlin {
    jvmToolchain(21)
}

// The exporter-agreement test compares against the freshly exported .bin set.
tasks.withType<Test>().configureEach {
    dependsOn(":profile-core:exportKernelProfiles")
}

tasks.named("preBuild") {
    dependsOn(generateBuildInfo)
}

/* The in-app legal pages show the repository's own LICENSE / NOTICE /
 * LICENSES/Apache-2.0.txt / LEGAL.md verbatim. Copying them into assets at
 * build time keeps one source of truth: editing the repository text updates
 * the app, with no second copy to drift. */
val legalAssetFiles = listOf(
    rootProject.file("LICENSE") to "legal/LICENSE.txt",
    rootProject.file("NOTICE") to "legal/NOTICE.txt",
    rootProject.file("LICENSES/Apache-2.0.txt") to "legal/Apache-2.0.txt",
    rootProject.file("LEGAL.md") to "legal/LEGAL.md",
    rootProject.file("LEGAL.en.md") to "legal/LEGAL.en.md",
)
val legalAssetsDir = layout.buildDirectory.dir("generated/legalAssets")

val syncLegalAssets = tasks.register<Sync>("syncLegalAssets") {
    into(legalAssetsDir.map { it.dir("legal") })
    legalAssetFiles.forEach { (source, assetPath) ->
        from(source) { rename { assetPath.substringAfterLast('/') } }
    }
}

android.sourceSets.getByName("main").assets.srcDir(legalAssetsDir.get().asFile)
tasks.named("preBuild") {
    dependsOn(syncLegalAssets)
}

/* The arm64 native payload is only needed by the tasks that merge/package the
 * APK/AAB. Keeping it off preBuild means pure JVM unit tests
 * (:app:testDebugUnitTest) no longer build the native binaries, so a developer
 * machine without the NDK / aarch64 Rust target can still run them. */
tasks.matching { task ->
    (task.name.startsWith("merge") && task.name.endsWith("JniLibFolders")) ||
        (task.name.startsWith("merge") && task.name.endsWith("NativeLibs"))
}.configureEach {
    dependsOn(rootProject.tasks.named("prepareGhostlockJniLibs"))
    dependsOn(rootProject.tasks.named("prepareGhostlockExtractJniLibs"))
}

dependencies {
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation:1.12.1")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("com.typesafe:config:1.4.9")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-nav:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.4")
    implementation(project(":profile-core"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
}
