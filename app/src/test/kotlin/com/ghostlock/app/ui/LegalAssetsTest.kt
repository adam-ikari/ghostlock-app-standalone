package com.ghostlock.app.ui

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The legal screens read the repository's own documents out of assets. This
 * catches the failure that actually breaks the feature: the build stops
 * copying them, so the user taps "Terms" and gets an empty page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LegalAssetsTest {
    private val context: Application = RuntimeEnvironment.getApplication()

    private fun assetText(name: String): String =
        context.assets.open("legal/$name").bufferedReader().use { it.readText() }

    @Test
    fun `every legal document ships and is not empty`() {
        for (document in LegalDocument.entries) {
            val text = assetText(document.assetName)
            assertTrue("${document.assetName} is empty", text.length > 100)
        }
    }

    @Test
    fun `terms state the risk and the authorized device scope`() {
        val terms = assetText(LegalDocument.Terms.assetName)
        assertTrue(terms.contains("风险"))
        assertTrue(terms.contains("已取得设备所有者明确书面授权"))
    }

    @Test
    fun `project license is gpl3`() {
        val license = assetText("LICENSE.txt")
        assertTrue(license.contains("GNU GENERAL PUBLIC LICENSE"))
        assertTrue(license.contains("Version 3, 29 June 2007"))
    }

    @Test
    fun `third party notice credits apache licensed code and the bundled ksud`() {
        val notice = assetText("NOTICE.txt")
        assertTrue(notice.contains("CyberMeowfia"))
        assertTrue(notice.contains("libksud.so"))
        assertTrue(notice.contains("KernelSU"))
        assertTrue(assetText("Apache-2.0.txt").contains("Apache License"))
    }

    @Test
    fun `the terms ship a translated copy only where one exists`() {
        assertEquals("LEGAL.md", LegalDocument.Terms.assetFor(isEnglish = false))
        assertEquals("LEGAL.en.md", LegalDocument.Terms.assetFor(isEnglish = true))
        /* License texts are not translated; English stays on the same file. */
        assertEquals("LICENSE.txt", LegalDocument.License.assetFor(isEnglish = true))
        assertTrue(assetText("LEGAL.en.md").contains("use at your own risk", ignoreCase = true))
    }

    @Test
    fun `markup stripping keeps the prose and drops the decorations`() {
        val rendered = stripMarkup("# 标题\n\n**一、风险自担。** 正文 `code`")
        assertTrue(rendered.contains("标题"))
        assertTrue(rendered.contains("一、风险自担。 正文 code"))
        assertFalse(rendered.contains("**"))
        assertFalse(rendered.contains('`'))
        assertEquals("plain line", stripMarkup("plain line"))
    }
}
