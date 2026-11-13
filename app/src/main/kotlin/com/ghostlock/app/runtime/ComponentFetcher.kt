package com.ghostlock.app.runtime

import java.io.File
import java.util.zip.ZipFile

/**
 * Describes the component packages the app installs, and how to verify them.
 *
 * The app is fully offline: every component ships inside the APK under
 * `assets/` (`modules/vector.zip`, `modules/rezygisk.zip`, `kernelsu-manager.apk`)
 * and is staged at first run. This object only catalogs the built-in modules
 * and verifies on-disk copies — there is no download path.
 */
object ComponentFetcher {
    data class Component(
        /** Stable id used by the UI/repository; see [VectorKey]. */
        val key: String,
        val label: String,
        val moduleId: String,
        val fileName: String,
    )

    const val VectorKey = "vector"

    /** Path of the Vector manager APK inside the bundled `vector.zip`. */
    const val VectorManagerEntry = "manager.apk"

    val Defaults = listOf(
        Component(
            key = VectorKey,
            label = "Vector",
            moduleId = "zygisk_vector",
            fileName = "vector.zip",
        ),
    )

    /** The component the UI asked for, or null for an unknown key. */
    fun byKey(key: String): Component? = Defaults.firstOrNull { it.key == key }

    /** A 2 KB "zip" is an error page or a redirect body, not a module. */
    private const val MinZipBytes = 100_000L

    /** A manager APK is ~14 MB; anything under 1 MB is a truncated body. */
    private const val MinApkBytes = 1_000_000L

    /**
     * True when [apk] looks like a real package: big enough, and a zip that
     * carries a manifest plus code. Blocks truncated/corrupt bundled copies.
     */
    fun verifyManagerApk(apk: File): Boolean {
        if (!apk.isFile || apk.length() < MinApkBytes) return false
        return runCatching {
            ZipFile(apk).use { archive ->
                archive.getEntry("AndroidManifest.xml") != null &&
                    archive.getEntry("classes.dex") != null
            }
        }.getOrDefault(false)
    }

    /** `id=` out of the module.prop inside [zip], or null when unreadable. */
    fun moduleIdOf(zip: File): String? = runCatching {
        ZipFile(zip).use { archive ->
            val entry = archive.getEntry("module.prop") ?: return@use null
            val props = archive.getInputStream(entry).bufferedReader().readText()
            props.lineSequence()
                .firstOrNull { it.startsWith("id=") }
                ?.removePrefix("id=")
                ?.trim()
        }
    }.getOrNull()

    /**
     * Checks that [zip] is the module [expectedId] and returns its `version=`
     * line. Null means "not a usable package": too small, not a zip, no
     * module.prop, or a different module.
     */
    fun verifyZip(zip: File, expectedId: String): String? {
        if (!zip.isFile || zip.length() < MinZipBytes) return null
        if (moduleIdOf(zip) != expectedId) return null
        return runCatching {
            ZipFile(zip).use { archive ->
                val entry = archive.getEntry("module.prop") ?: return@use null
                val props = archive.getInputStream(entry).bufferedReader().readText()
                props.lineSequence()
                    .firstOrNull { it.startsWith("version=") }
                    ?.removePrefix("version=")
                    ?.trim()
            }
        }.getOrNull()
    }
}