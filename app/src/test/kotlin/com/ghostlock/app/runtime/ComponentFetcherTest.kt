package com.ghostlock.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Staging must never hand the installer something that is not the module it
 * asked for. These lock the "check before replacing" rules: an error page, a
 * truncated copy or a package with the wrong id has to be rejected, because the
 * native script installs every *.zip it finds in the staging dir.
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
                out.write(ByteArray(64))
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
    fun `manager apk has to carry a manifest and code`() {
        assertTrue(ComponentFetcher.verifyManagerApk(apkLike()))
        assertFalse(ComponentFetcher.verifyManagerApk(apkLike(name = "no-dex.apk", withDex = false)))
        assertFalse(ComponentFetcher.verifyManagerApk(apkLike(name = "no-manifest.apk", withManifest = false)))
        /* A 200 KB "apk" is a truncated copy, not a package. */
        assertFalse(ComponentFetcher.verifyManagerApk(apkLike(name = "small.apk", padding = 200_000)))
        val notZip = temp.newFile("error.apk").apply { writeText("<html>502</html>") }
        assertFalse(ComponentFetcher.verifyManagerApk(notZip))
    }

    @Test
    fun `accepts the expected module and reports its version`() {
        val zip = zipWith("rezygisk", "1.0.0")
        assertEquals("1.0.0", ComponentFetcher.verifyZip(zip, "rezygisk"))
        assertEquals("rezygisk", ComponentFetcher.moduleIdOf(zip))
    }

    @Test
    fun `rejects a different module`() {
        val zip = zipWith("zygisk_vector", "v2.2")
        assertNull(ComponentFetcher.verifyZip(zip, "rezygisk"))
    }

    @Test
    fun `rejects a package that is too small to be a module`() {
        val small = zipWith("rezygisk", "1.0.0", padding = 0)
        assertNull(ComponentFetcher.verifyZip(small, "rezygisk"))
    }

    @Test
    fun `rejects a body that is not a zip at all`() {
        val html = temp.newFile("error.zip")
        html.writeBytes(ByteArray(200_000) { '<'.code.toByte() })
        assertNull(ComponentFetcher.verifyZip(html, "rezygisk"))
        assertNull(ComponentFetcher.moduleIdOf(html))
    }

    @Test
    fun `the bundled components are the ones the root script installs`() {
        assertEquals(
            listOf("rezygisk.zip", "vector.zip"),
            ComponentFetcher.Defaults.map { it.fileName },
        )
        assertEquals(
            listOf("rezygisk", "zygisk_vector"),
            ComponentFetcher.Defaults.map { it.moduleId },
        )
    }
}
