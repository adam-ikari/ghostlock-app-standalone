package com.ghostlock.app.runtime

import android.content.Context
import java.io.File

/**
 * Where the bundled component packages live for one run.
 *
 * The native root script runs as uid 0 but stays in the launcher's SELinux
 * domain, so it cannot read the app's private data dir. The app therefore
 * publishes the bundled zips into a directory the root script can reach and
 * points the native at it through `GHOSTLOCK_LSPD_MODULES`.
 *
 * Publishing is opt-in: with no component packages selected the native skips
 * the whole stack. Installing and activating Vector restarts
 * system_server, and an unlucky ordering there drops the device into
 * recovery, so the plain one-tap root path must never enter that code.
 */
object LspdModuleStaging {
    /** Sub-directory of the app's external files dir the root script reads. */
    private const val PublishedDir = "modules-published"

    /** Asset directory holding the bundled zips. */
    private const val AssetDir = "modules"

    /**
     * Publishes every bundled component zip to a directory the root script can
     * read and returns it, or null when there is nothing to install.
     *
     * The packages go to the app's **external** files dir
     * (`/storage/emulated/0/Android/data/<pkg>/files/...`) because the module
     * install runs in the KernelSU (ksu) domain, and that domain cannot read
     * `/data/data/<pkg>/`. The zips are copied straight from the APK assets:
     * no app-private staging copy is involved.
     *
     * Each run publishes into its own directory: a reinstall gives the app a
     * new uid, and the leftovers of the previous one are still owned by the
     * old uid — writing into them fails, and the whole stage would be
     * skipped for the wrong reason.
     *
     * Returns null when no bundled zip exists, or when a copy failed — in both
     * cases the native must not touch the LSPosed stack.
     */
    fun publish(context: Context): String? {
        val names = runCatching {
            context.assets.list(AssetDir).orEmpty().filter { it.endsWith(".zip") }
        }.getOrDefault(emptyList())
        if (names.isEmpty()) return null
        return publish(context, names) { name, target ->
            runCatching {
                context.assets.open("$AssetDir/$name").use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }.isSuccess
        }
    }

    /**
     * Testable core of [publish]: copies each asset name once into a fresh
     * external `modules-published-<timestamp>` directory.
     */
    internal fun publish(
        context: Context,
        zipNames: List<String>,
        copyAsset: (String, File) -> Boolean,
    ): String? {
        if (zipNames.isEmpty()) return null
        val external = context.getExternalFilesDir(null) ?: return null
        val published = freshPublishedDir(external) ?: return null
        for (name in zipNames) {
            val target = File(published, name)
            if (!copyAsset(name, target)) {
                /* The directory is fresh, so drop all of this attempt — a
                 * partial set must not be handed to the native. */
                published.deleteRecursively()
                return null
            }
        }
        return published.absolutePath
    }

    /** A directory unused by any previous publish, even within one millisecond. */
    private fun freshPublishedDir(external: File): File? {
        val stamp = System.currentTimeMillis()
        repeat(100) { attempt ->
            val name = if (attempt == 0) "$PublishedDir-$stamp" else "$PublishedDir-$stamp-$attempt"
            val candidate = File(external, name)
            if (candidate.mkdirs()) return candidate
        }
        return null
    }
}
