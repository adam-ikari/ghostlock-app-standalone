package com.ghostlock.app.shizuku

import android.content.Context
import java.io.File

/**
 * Where the ZygiskNext / Vector component packages live for one run.
 *
 * The native root script runs as uid 0 but stays in the launcher's SELinux
 * domain, so it cannot read the app's private data dir. The app therefore
 * publishes the selected zips into a directory the root script can reach and
 * points the native at it through `GHOSTLOCK_LSPD_MODULES`.
 *
 * Publishing is opt-in: with no component packages selected the native skips
 * the whole stack. Installing and activating ZygiskNext/Vector restarts
 * system_server, and an unlucky ordering there drops the device into
 * recovery, so the plain one-tap root path must never enter that code.
 */
object LspdModuleStaging {
    /**
     * The app-private directory the user picks the zips into.
     *
     * It has to stay app-private: the app cannot write to /data/local/tmp
     * (that directory belongs to the shell user), and the root script runs in
     * the shell domain, which reaches app private data through the ksu
     * domain when the module install runs `su -c`.
     */
    private const val PrivateDir = "modules"

    /** Sub-directory of the app's external files dir the root script reads. */
    private const val PublishedDir = "modules-published"

    /**
     * Copies the selected component packages to a directory the root script
     * can read and returns it, or null when there is nothing to install.
     *
     * The packages go to the app's **external** files dir
     * (`/storage/emulated/0/Android/data/<pkg>/files/...`), not its private
     * one: the module install runs in the KernelSU (ksu) domain, and that
     * domain cannot read `/data/data/<pkg>/` (the app's private dir), while
     * the shared storage path is readable from every domain. The app can write
     * both, so the app-private dir stays the staging area and the external
     * one is what the native side is pointed at.
     *
     * Returns null when the user selected no packages, or when the copy
     * failed — in both cases the native must not touch the LSPosed stack.
     */
    fun publish(context: Context): String? {
        val private = File(context.filesDir, PrivateDir)
        val zips = private.listFiles { file -> file.isFile && file.name.endsWith(".zip") }
            ?.filter { it.length() > 0 }
            .orEmpty()
        if (zips.isEmpty()) return null

        val external = context.getExternalFilesDir(null) ?: return null
        val published = File(external, PublishedDir)
        if (!published.isDirectory && !published.mkdirs()) return null
        // Stale packages from an earlier run would be installed again.
        published.listFiles()?.forEach { it.delete() }

        for (zip in zips) {
            val target = File(published, zip.name)
            val copied = runCatching {
                zip.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            }.isSuccess && target.isFile && target.length() == zip.length()
            if (!copied) {
                published.listFiles()?.forEach { it.delete() }
                return null
            }
        }
        return published.absolutePath
    }

    /**
     * The directory the native root script should read, or null when there is
     * nothing to install.
     */
    fun stagedModulesDir(context: Context): String? {
        val private = File(context.filesDir, PrivateDir)
        val hasZip = private.listFiles { file -> file.isFile && file.name.endsWith(".zip") }
            ?.isNotEmpty() == true
        if (!hasZip) return null
        val external = context.getExternalFilesDir(null) ?: return null
        val published = File(external, PublishedDir)
        if (!published.isDirectory) return null
        val hasPublished = published.listFiles { file -> file.isFile && file.name.endsWith(".zip") }
            ?.isNotEmpty() == true
        return if (hasPublished) published.absolutePath else null
    }
}
