package com.ghostlock.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The fetch path must never hand the installer something that is not the module
 * it asked for. These lock the "check before replacing" rules: an error page, a
 * truncated download or a package with the wrong id has to be rejected, because
 * the native script installs every *.zip it finds in the staging dir.
 */
class ComponentFetcherTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun zipWith(id: String, version: String, padding: Int = 120_000): File {
        val file = temp.newFile("$id.zip")
        ZipOutputStream(file.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("module.prop"))
            out.write("id=$id\nname=test\nversion=$version\n".toByteArray())
            out.closeEntry()
            out.putNextEntry(ZipEntry("payload.bin"))
            /* Incompressible, so the archive really exceeds the size floor; a
             * zero-filled entry would deflate to a few hundred bytes. */
            val blob = ByteArray(padding)
            java.util.Random(7L).nextBytes(blob)
            out.write(blob)
            out.closeEntry()
        }
        return file
    }

    private fun apkLike(
        name: String = "manager.apk",
        withManifest: Boolean = true,
        withDex: Boolean = true,
        padding: Int = 1_200_000,
    ): File {
        val file = temp.newFile(name)
        ZipOutputStream(file.outputStream()).use { out ->
            if (withManifest) {
                out.putNextEntry(ZipEntry("AndroidManifest.xml"))
                out.write("<manifest/>".toByteArray())
                out.closeEntry()
            }
            if (withDex) {
                out.putNextEntry(ZipEntry("classes.dex"))
                out.write("dex\n036\n".toByteArray())
                out.closeEntry()
            }
            out.putNextEntry(ZipEntry("resources.arsc"))
            val blob = ByteArray(padding)
            java.util.Random(11L).nextBytes(blob)
            out.write(blob)
            out.closeEntry()
        }
        return file
    }

    @Test
    fun `release apk asset wins over the other assets`() {
        val json = """
            {
              "tag_name": "v3.3.1",
              "assets": [
                {"browser_download_url": "https://github.com/tiann/KernelSU/releases/download/v3.3.1/KernelSU_v3.3.1_32680-release.apk"},
                {"browser_download_url": "https://github.com/tiann/KernelSU/releases/download/v3.3.1/KernelSU_v3.3.1_32680-release.apk.sig"}
              ]
            }
        """.trimIndent()
        assertEquals(
            "https://github.com/tiann/KernelSU/releases/download/v3.3.1/KernelSU_v3.3.1_32680-release.apk",
            ComponentFetcher.parseManagerApkUrl(json),
        )
    }

    @Test
    fun `apk url parsing refuses a release without an apk`() {
        assertNull(ComponentFetcher.parseManagerApkUrl("""{"assets": []}"""))
        assertNull(
            ComponentFetcher.parseManagerApkUrl(
                """{"assets": [{"browser_download_url": "https://github.com/x/y/releases/download/v1/y.img"}]}""",
            ),
        )
        /* A plain-http asset is not something to hand to an installer. */
        assertNull(
            ComponentFetcher.parseManagerApkUrl(
                """{"assets": [{"browser_download_url": "http://x/y-release.apk"}]}""",
            ),
        )
    }

    @Test
    fun `manager apk has to carry a manifest and code`() {
        assertTrue(ComponentFetcher.verifyManagerApk(apkLike()))
        assertFalse(ComponentFetcher.verifyManagerApk(apkLike(name = "no-dex.apk", withDex = false)))
        assertFalse(ComponentFetcher.verifyManagerApk(apkLike(name = "no-manifest.apk", withManifest = false)))
        /* A 200 KB "apk" is a truncated body, not a package. */
        assertFalse(ComponentFetcher.verifyManagerApk(apkLike(name = "small.apk", padding = 200_000)))
        val notZip = temp.newFile("error.apk").apply { writeText("<html>502</html>") }
        assertFalse(ComponentFetcher.verifyManagerApk(notZip))
    }

    @Test
    fun `prefix style mirror gets the upstream url appended`() {
        assertEquals(
            "https://m.example/https://github.com/a/b.zip",
            ComponentFetcher.withMirror("https://github.com/a/b.zip", "https://m.example/"),
        )
        assertEquals(
            "https://m.example/https://github.com/a/b.zip",
            ComponentFetcher.withMirror("https://github.com/a/b.zip", "  https://m.example  "),
        )
    }

    @Test
    fun `placeholder style mirror substitutes the url`() {
        assertEquals(
            "https://m.example/?u=https://github.com/a/b.zip",
            ComponentFetcher.withMirror("https://github.com/a/b.zip", "https://m.example/?u={url}"),
        )
    }

    @Test
    fun `empty or unsafe mirror leaves the url alone`() {
        assertEquals("https://github.com/a/b.zip", ComponentFetcher.withMirror("https://github.com/a/b.zip", ""))
        assertEquals("https://github.com/a/b.zip", ComponentFetcher.withMirror("https://github.com/a/b.zip", "  "))
        /* http proxy would leak the download; stay on the upstream instead. */
        assertEquals(
            "https://github.com/a/b.zip",
            ComponentFetcher.withMirror("https://github.com/a/b.zip", "http://m.example/"),
        )
    }

    @Test
    fun `parses the zipUrl of an update json`() {
        val json = """{"version":"v1.5.2","versionCode":864,
            "zipUrl":"https://github.com/LSPosed/ZygiskNext/releases/download/v1.5.2/x.zip"}"""
        assertEquals(
            "https://github.com/LSPosed/ZygiskNext/releases/download/v1.5.2/x.zip",
            ComponentFetcher.parseZipUrl(json),
        )
    }

    @Test
    fun `ignores a non-https zipUrl`() {
        assertNull(ComponentFetcher.parseZipUrl("""{"zipUrl":"http://example.com/x.zip"}"""))
    }

    @Test
    fun `rejects an update json without zipUrl`() {
        assertNull(ComponentFetcher.parseZipUrl("""{"version":"v1"}"""))
    }

    @Test
    fun `accepts the expected module and reports its version`() {
        val zip = zipWith("zygisksu", "1.5.2 (864-a4a72ce-release)")
        assertEquals("1.5.2 (864-a4a72ce-release)", ComponentFetcher.verifyZip(zip, "zygisksu"))
        assertEquals("zygisksu", ComponentFetcher.moduleIdOf(zip))
    }

    @Test
    fun `rejects a different module`() {
        val zip = zipWith("zygisk_vector", "v2.2")
        assertNull(ComponentFetcher.verifyZip(zip, "zygisksu"))
    }

    @Test
    fun `rejects a download that is too small to be a module`() {
        val small = zipWith("zygisksu", "1.5.2", padding = 0)
        assertNull(ComponentFetcher.verifyZip(small, "zygisksu"))
    }

    @Test
    fun `rejects a body that is not a zip at all`() {
        val html = temp.newFile("error.zip")
        html.writeBytes(ByteArray(200_000) { '<'.code.toByte() })
        assertNull(ComponentFetcher.verifyZip(html, "zygisksu"))
        assertNull(ComponentFetcher.moduleIdOf(html))
    }
}
