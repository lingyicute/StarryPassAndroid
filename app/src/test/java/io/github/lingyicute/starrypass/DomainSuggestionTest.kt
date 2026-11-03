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
        assertEquals("amazon", PreferencesRepository.suggestCodeFromContext("www.amazon.com.au", null))
        assertEquals("naver", PreferencesRepository.suggestCodeFromContext("nid.naver.co.kr", null))
        assertEquals("mercadolivre", PreferencesRepository.suggestCodeFromContext("login.mercadolivre.com.br", null))
        assertEquals("protonmail", PreferencesRepository.suggestCodeFromContext(null, "ch.protonmail.android"))
        assertEquals("github", PreferencesRepository.suggestCodeFromContext(null, "com.github.android"))
    }

    @Test
    fun `normalizes domains that carry credentials, query, fragment, port or a trailing dot`() {
        assertEquals("example.com", PreferencesRepository.normalizeDomain("https://User:pw@WWW.Example.com.:8443/a?b#c"))
        assertEquals("example.com", PreferencesRepository.normalizeDomain("example.com?next=/home"))
        assertEquals("example.com", PreferencesRepository.normalizeDomain("example.com#login"))
        assertEquals("sub.example.com", PreferencesRepository.normalizeDomain("sub.example.com./"))
    }

    @Test
    fun `normalizes domains with any URL scheme, protocol-relative prefix or IPv6 literal`() {
        assertEquals("example.com", PreferencesRepository.normalizeDomain("ftp://example.com/files"))
        assertEquals("example.com", PreferencesRepository.normalizeDomain("HTTPS://Example.com"))
        assertEquals("example.com", PreferencesRepository.normalizeDomain("//www.example.com/login"))
        assertEquals("localhost", PreferencesRepository.normalizeDomain("localhost:8080"))
        assertEquals("[::1]", PreferencesRepository.normalizeDomain("http://[::1]:8080/path"))
        assertEquals("[2001:db8::1]", PreferencesRepository.normalizeDomain("[2001:db8::1]"))
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
