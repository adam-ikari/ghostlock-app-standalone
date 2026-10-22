package com.ghostlock.app.runtime

import org.junit.Assert.assertEquals
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
