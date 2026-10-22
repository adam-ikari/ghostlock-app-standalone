package com.ghostlock.app.runtime

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

/**
 * Downloads the ZygiskNext / Vector component packages from the releases their
 * authors publish, into the app's module staging directory.
 *
 * The packages cannot ship inside the APK: ZygiskNext stopped being GPL-3.0 at
 * v4-0.9.2, and its own README forbids redistribution ("including the software
 * as part of another project"). Fetching the release the author publishes keeps
 * the one-tap flow without redistributing anything — the download is the user
 * getting the file from the rights holder.
 *
 * Both projects publish an `update.json` whose `zipUrl` points at a GitHub
 * release asset, so the version follows upstream instead of being pinned here.
 */
object ComponentFetcher {
    data class Component(
        val label: String,
        val updateUrl: String,
        val moduleId: String,
        val fileName: String,
    )

    val Defaults = listOf(
        Component(
            label = "ZygiskNext",
            updateUrl = "https://lsposed.zip/zygisk-next/update.json",
            moduleId = "zygisksu",
            fileName = "zygisknext.zip",
        ),
        Component(
            label = "Vector",
            updateUrl = "https://raw.githubusercontent.com/JingMatrix/Vector/master/zygisk/update.json",
            moduleId = "zygisk_vector",
            fileName = "vector.zip",
        ),
    )

    /** A 2 KB "zip" is an error page or a redirect body, not a module. */
    private const val MinZipBytes = 100_000L

    /** Upper bound for a module package; keeps a hostile/garbled response bounded. */
    private const val MaxZipBytes = 64L * 1024 * 1024

    private const val ConnectTimeoutMs = 15_000
    private const val ReadTimeoutMs = 60_000

    /** The `zipUrl` field of an `update.json`, or null when absent/not https. */
    fun parseZipUrl(json: String): String? =
        Regex("\"zipUrl\"\\s*:\\s*\"([^\"]+)\"")
            .find(json)
            ?.groupValues
            ?.get(1)
            ?.takeIf { it.startsWith("https://") }

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

    /**
     * Fetches one component into `<filesDir>/modules/<fileName>`.
     *
     * Returns true when a verified package is in place. The destination is only
     * replaced after the download verifies, so a failed fetch never destroys a
     * working copy from an earlier run.
     */
    fun fetch(context: Context, component: Component, onLog: (String) -> Unit): Boolean {
        val dir = File(context.filesDir, "modules")
        if (!dir.isDirectory && !dir.mkdirs()) {
            onLog("${component.label}: cannot create ${dir.absolutePath}")
            return false
        }
        /* Never replace a package that is already there: the user may run a
         * build that is newer than the release upstream points at, and a silent
         * downgrade would break a setup that works. */
        val existing = dir.listFiles { file -> file.isFile && file.name.endsWith(".zip") }
            ?.firstOrNull { moduleIdOf(it) == component.moduleId }
        if (existing != null) {
            onLog("${component.label}: already staged (${existing.name}), skipped")
            return true
        }
        val update = runCatching { httpGetText(component.updateUrl) }.getOrElse {
            onLog("${component.label}: update.json failed (${it.message})")
            return false
        }
        val zipUrl = parseZipUrl(update)
        if (zipUrl == null) {
            onLog("${component.label}: update.json has no usable zipUrl")
            return false
        }
        onLog("${component.label}: downloading ${zipUrl.substringAfterLast('/')}")
        val tmp = File(dir, component.fileName + ".part")
        try {
            download(zipUrl, tmp)
            val version = verifyZip(tmp, component.moduleId)
            if (version == null) {
                onLog("${component.label}: rejected download (not module ${component.moduleId}, " +
                    "${tmp.length()} bytes)")
                return false
            }
            val dest = File(dir, component.fileName)
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
            }
            pruneDuplicates(dir, component, dest)
            onLog("${component.label} $version ready: ${dest.name} (${dest.length()} bytes)")
            return true
        } catch (error: IOException) {
            onLog("${component.label}: download failed (${error.message})")
            return false
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /**
     * A zip picked by an older version may carry a different file name; leaving
     * it next to the fresh one would install the same module twice (the native
     * script installs every `*.zip` it finds).
     */
    private fun pruneDuplicates(dir: File, component: Component, keep: File) {
        dir.listFiles { file -> file.isFile && file.name.endsWith(".zip") && file != keep }
            ?.filter { moduleIdOf(it) == component.moduleId }
            ?.forEach { it.delete() }
    }

    private fun httpGetText(url: String): String = openConnection(url).let { connection ->
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun download(url: String, dest: File) {
        val connection = openConnection(url)
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            val declared = connection.contentLengthLong
            if (declared > MaxZipBytes) throw IOException("too large: $declared bytes")
            dest.outputStream().use { out ->
                connection.inputStream.use { input -> input.copyTo(out) }
                out.flush()
            }
            if (dest.length() > MaxZipBytes) throw IOException("too large: ${dest.length()} bytes")
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = ConnectTimeoutMs
            readTimeout = ReadTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "GhostLock")
        }
}
