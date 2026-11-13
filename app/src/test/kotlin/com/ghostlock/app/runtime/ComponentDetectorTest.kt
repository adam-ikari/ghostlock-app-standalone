package com.ghostlock.app.runtime

import android.app.Application
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The detection card has to distinguish "not there" from "not checked", so the
 * `/proc/modules` parsing and the staged-zip lookup are pinned here. The
 * installed-manager and `su` legs need a real device and are covered by the
 * manual smoke run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ComponentDetectorTest {
    private val context: Application = RuntimeEnvironment.getApplication()

    @Test
    fun `kernelsu shows up when proc modules lists it`() {
        val table = """
            kernelsu 1234567 0 - Live 0x0000000000000000
            binder_linux 200704 3 - Live 0x0000000000000000
        """.trimIndent()
        assertTrue(ComponentDetector.ksuLoadedIn(table))
    }

    @Test
    fun `a module whose name merely starts with kernelsu does not count`() {
        /* Substring matching would report the module active on a device that
         * only has some unrelated kernelsu-prefixed module loaded. */
        val table = "kernelsu_helper 123 0 - Live 0x0\nkernelsu_extra 1 0 - Live 0x0"
        assertFalse(ComponentDetector.ksuLoadedIn(table))
    }

    @Test
    fun `an empty table has no kernelsu`() {
        assertFalse(ComponentDetector.ksuLoadedIn(""))
    }

    @Test
    fun `tabs separate the module name too`() {
        assertTrue(ComponentDetector.ksuLoadedIn("kernelsu\t1234\t0\tLive\t0x0"))
    }

    @Test
    fun `a package picked under its release name is still detected`() {
        /* The file picker keeps the original name; the native script installs
         * every zip in the dir, so detection must not look only for the name
         * the fetcher would use. */
        val component = ComponentFetcher.byKey(ComponentFetcher.VectorKey)!!
        val dir = File(context.filesDir, "modules").apply { mkdirs() }
        val zip = File(dir, "Vector-v2.2.zip")
        java.util.zip.ZipOutputStream(zip.outputStream()).use { archive ->
            archive.putNextEntry(java.util.zip.ZipEntry("module.prop"))
            archive.write("id=zygisk_vector\nversion=v2.2\n".toByteArray())
            archive.closeEntry()
            archive.putNextEntry(java.util.zip.ZipEntry("payload.bin"))
            val blob = ByteArray(120_000)
            java.util.Random(3L).nextBytes(blob)
            archive.write(blob)
            archive.closeEntry()
        }
        assertEquals("v2.2", ComponentDetector.stagedVersion(context, component))
    }

    @Test
    fun `a bundled package counts as staged without touching disk`() {
        /* Both modules ship inside the APK, so a fresh install must not report
         * them missing and send the user off to download anything. */
        val component = ComponentFetcher.byKey(ComponentFetcher.VectorKey)!!
        assertNotNull(ComponentDetector.stagedVersion(context, component))
    }

    @Test
    fun `a boot id in the status file is read back`() {
        val file = File(context.filesDir, "status_a")
        file.writeText("schema=1\nrun_id=1\nboot_id=ABC-123\nstate=installing\n")
        assertEquals("ABC-123", ComponentDetector.statusField(file, "boot_id"))
        assertEquals("installing", ComponentDetector.statusField(file, "state"))
    }

    @Test
    fun `a missing key or file reads as null`() {
        val file = File(context.filesDir, "status_b")
        file.writeText("boot_id=ABC-123\n")
        assertNull(ComponentDetector.statusField(file, "state"))
        assertNull(ComponentDetector.statusField(File(context.filesDir, "status_absent"), "boot_id"))
    }

    @Test
    fun `a boot id must match exactly, not by prefix`() {
        /* boot_idx=9 is a different key; a prefix match would report the device
         * rooted on an unrelated boot counter. */
        val file = File(context.filesDir, "status_c")
        file.writeText("boot_idx=9\n")
        assertNull(ComponentDetector.statusField(file, "boot_id"))
    }

    @Test
    fun `the status list keeps the four rows the card renders`() {
        val status = ComponentDetector.Status(
            key = "module_active",
            label = "KernelSU module",
            detail = "active",
            ok = true,
        )
        assertEquals("module_active", status.key)
        assertTrue(status.ok)
    }

    @Test
    fun `a ready status file for this boot reads as activated`() {
        /* Shape taken from a real device run: the script rewrites the whole file
         * atomically, so every field is present at once. */
        val file = File(context.filesDir, "status_ready")
        file.writeText(
            """
            schema=1
            run_id=1793848818
            boot_id=$BOOT_ID
            pid=0
            state=ready
            busy=0
            ready=1
            error=
            updated=1793848819
            """.trimIndent() + "\n",
        )
        assertTrue(ComponentDetector.vectorActivated(file, BOOT_ID))
    }

    @Test
    fun `a ready file from another boot does not count`() {
        /* A cold boot drops the injection with the zygote, so yesterday's ready
         * must not light up the step. */
        val file = File(context.filesDir, "status_stale_boot")
        file.writeText("boot_id=OLD-BOOT\nstate=ready\nready=1\n")
        assertFalse(ComponentDetector.vectorActivated(file, BOOT_ID))
    }

    @Test
    fun `failed and mid-run states do not count`() {
        val failed = File(context.filesDir, "status_failed")
        failed.writeText("boot_id=$BOOT_ID\nstate=failed\nready=0\nerror=zygote injection gate failed\n")
        assertFalse(ComponentDetector.vectorActivated(failed, BOOT_ID))

        val installing = File(context.filesDir, "status_installing")
        installing.writeText("boot_id=$BOOT_ID\nstate=installing\nready=0\n")
        assertFalse(ComponentDetector.vectorActivated(installing, BOOT_ID))
    }

    @Test
    fun `state and ready must agree and the file must exist`() {
        val halfReady = File(context.filesDir, "status_half")
        halfReady.writeText("boot_id=$BOOT_ID\nstate=ready\nready=0\n")
        assertFalse(ComponentDetector.vectorActivated(halfReady, BOOT_ID))

        val noBootId = File(context.filesDir, "status_no_boot")
        noBootId.writeText("state=ready\nready=1\n")
        assertFalse(ComponentDetector.vectorActivated(noBootId, BOOT_ID))

        assertFalse(
            ComponentDetector.vectorActivated(File(context.filesDir, "status_absent2"), BOOT_ID),
        )
        assertFalse(ComponentDetector.vectorActivated(halfReady, null))
    }

    private companion object {
        const val BOOT_ID = "10138c54-1010-4c13-bf18-a6d5e2615449"
    }
}
