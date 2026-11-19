package com.ghostlock.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StringsPairTest {
    @Test
    fun stringsKeysMatchAcrossLocales() {
        val en = parseStringNames("values")
        val zh = parseStringNames("values-zh")
        assertEquals(en, zh)
    }

    @Test
    fun advancedProtectionCopyExistsInBothLocales() {
        assertTrue(readString("values", "install_advanced_protection_hint").isNotBlank())
        assertTrue(readString("values-zh", "install_advanced_protection_hint").isNotBlank())
    }

    private fun parseStringNames(locale: String): Set<String> =
        Regex("""<string\s+name="([^"]+)"""")
            .findAll(stringsFile(locale).readText())
            .map { it.groupValues[1] }
            .toSet()

    private fun readString(locale: String, name: String): String =
        Regex("""<string\s+name="$name"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(stringsFile(locale).readText())
            ?.groupValues?.get(1)
            ?.trim()
            ?: ""

    private fun stringsFile(locale: String): File {
        val fromModuleRoot = File("src/main/res/$locale/strings.xml")
        if (fromModuleRoot.isFile) return fromModuleRoot
        return File("app/src/main/res/$locale/strings.xml")
    }
}
