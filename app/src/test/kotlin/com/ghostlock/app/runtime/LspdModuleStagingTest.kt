package com.ghostlock.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LspdModuleStagingTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun publishCopiesAssetZipsOnceIntoFreshExternalDirectory() {
        val copied = mutableListOf<String>()
        val dir = LspdModuleStaging.publish(
            context = context,
            zipNames = listOf("rezygisk.zip", "vector.zip"),
            copyAsset = { name, target -> copied += "$name->${target.name}"; true },
        )
        assertNotNull(dir)
        assertTrue(java.io.File(dir!!).name.startsWith("modules-published-"))
        assertEquals(listOf("rezygisk.zip->rezygisk.zip", "vector.zip->vector.zip"), copied)
    }

    @Test
    fun publishReturnsNullWhenNoAssetZipNames() {
        assertNull(LspdModuleStaging.publish(context, emptyList()) { _, _ -> true })
    }

    @Test
    fun publishRemovesPublishedDirectoryOnCopyFailure() {
        val external = context.getExternalFilesDir(null)!!
        val before = external.listFiles { f -> f.isDirectory && f.name.startsWith("modules-published-") }
            .orEmpty().toSet()

        assertNull(LspdModuleStaging.publish(context, listOf("vector.zip")) { _, _ -> false })

        val after = external.listFiles { f -> f.isDirectory && f.name.startsWith("modules-published-") }
            .orEmpty().toSet()
        assertEquals(before, after)
    }

    @Test
    fun publishUsesTimestampedDirectory() {
        val a = LspdModuleStaging.publish(context, listOf("vector.zip")) { _, _ -> true }
        val b = LspdModuleStaging.publish(context, listOf("vector.zip")) { _, _ -> true }
        assertNotNull(a)
        assertNotNull(b)
        assertNotEquals(a, b)
    }
}
