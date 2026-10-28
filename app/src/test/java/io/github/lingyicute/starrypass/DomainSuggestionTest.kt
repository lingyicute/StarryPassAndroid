package io.github.lingyicute.starrypass

import io.github.lingyicute.starrypass.data.PreferencesRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class DomainSuggestionTest {

    @Test
    fun `extracts clean site codes from domains and Android package names`() {
        assertEquals("github", PreferencesRepository.suggestCodeFromContext("https://github.com/login", null))
        assertEquals("github", PreferencesRepository.suggestCodeFromContext("accounts.github.com", null))
        assertEquals("bbc", PreferencesRepository.suggestCodeFromContext("id.bbc.co.uk", null))
        assertEquals("bilibili", PreferencesRepository.suggestCodeFromContext("passport.bilibili.com.cn", null))
        assertEquals("protonmail", PreferencesRepository.suggestCodeFromContext(null, "ch.protonmail.android"))
        assertEquals("github", PreferencesRepository.suggestCodeFromContext(null, "com.github.android"))
    }

    @Test
    fun `bilingual string resources have symmetric keys, clean CJK-Latin spacing, and no legacy wording`() {
        val enFile = File("src/main/res/values/strings.xml")
        val zhFile = File("src/main/res/values-zh/strings.xml")
        assertTrue(enFile.exists())
        assertTrue(zhFile.exists())

        fun parseStrings(file: File): Map<String, String> {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val nodes = doc.getElementsByTagName("string")
            val map = linkedMapOf<String, String>()
            for (i in 0 until nodes.length) {
                val el = nodes.item(i)
                val name = el.attributes.getNamedItem("name").nodeValue
                map[name] = el.textContent
            }
            return map
        }

        val enMap = parseStrings(enFile)
        val zhMap = parseStrings(zhFile)

        assertEquals("EN and ZH string keys must match 1:1", enMap.keys, zhMap.keys)

        for ((key, value) in enMap) {
            assertFalse("EN string '$key' contains '%%': $value", value.contains("%%"))
        }

        // Between Chinese characters and ASCII letters/digits/symbols there must be whitespace or Chinese punctuation
        val adjacentCjkAsciiRegex = Regex("""[\u4e00-\u9fff][\x21-\x7e]|[\x21-\x7e][\u4e00-\u9fff]""")
        for ((key, value) in zhMap) {
            assertFalse("ZH string '$key' contains '%%': $value", value.contains("%%"))
            assertFalse("ZH string '$key' still contains legacy wording: $value", value.contains("\u539f\u7248"))
            val badMatch = adjacentCjkAsciiRegex.find(value)
            assertTrue(
                "ZH string '$key' has CJK directly touching ASCII ('${badMatch?.value}') without space or Chinese punctuation: $value",
                badMatch == null
            )
        }
    }
}
