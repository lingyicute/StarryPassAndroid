package io.github.lingyicute.starrypass

import io.github.lingyicute.starrypass.autofill.AssistStructureParser
import io.github.lingyicute.starrypass.data.PreferencesRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
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
    fun `recognizes country second-level suffixes missing from the explicit list`() {
        assertEquals("example", PreferencesRepository.extractDomainStem("login.example.org.nz"))
        assertEquals("example", PreferencesRepository.extractDomainStem("shop.example.gov.in"))
        assertEquals("example", PreferencesRepository.extractDomainStem("www.example.go.jp"))
        assertEquals("id", PreferencesRepository.extractDomainStem("login.id.me"))
        assertEquals("example", PreferencesRepository.extractDomainStem("a.example.com"))
        assertEquals("co", PreferencesRepository.extractDomainStem("co.uk"))
    }

    @Test
    fun `keeps IPv4 addresses whole instead of using the last octet as the site code`() {
        assertEquals("192.168.1.1", PreferencesRepository.suggestCodeFromContext("http://192.168.1.1:8080/login", null))
        assertEquals("192.168.1.1", PreferencesRepository.extractDomainStem("192.168.1.1"))
        assertEquals("example", PreferencesRepository.extractDomainStem("1.example.com"))
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
        assertEquals("[::1]", PreferencesRepository.normalizeDomain("::1"))
        assertEquals("[2001:db8::1]", PreferencesRepository.normalizeDomain("2001:db8::1"))
        assertEquals("", PreferencesRepository.normalizeDomain("[]:8080"))
        assertEquals("", PreferencesRepository.normalizeDomain("[::1"))
        assertEquals("example.com", PreferencesRepository.normalizeDomain("https:// example.com :8443/login"))
        assertEquals("gist.github.com", PreferencesRepository.normalizeDomain("blob:https://gist.github.com/1234-5678"))
        assertEquals("example.com", PreferencesRepository.normalizeDomain("view-source:https://www.example.com/login"))
        assertEquals("", PreferencesRepository.normalizeDomain("about:blank"))
        assertEquals("", PreferencesRepository.normalizeDomain("javascript:void(0)"))
        assertEquals("", PreferencesRepository.normalizeDomain("[::1]:notaport"))
        assertEquals("", PreferencesRepository.normalizeDomain("tel:12345"))
        assertEquals("", PreferencesRepository.normalizeDomain("mailto:a@evil.com"))
        assertEquals("", PreferencesRepository.normalizeDomain("cid:part1@evil.com"))
        assertEquals("", PreferencesRepository.normalizeDomain("content://com.android.providers.downloads/document/1"))
        assertEquals("", PreferencesRepository.normalizeDomain("file://localhost/sdcard/login.html"))
        assertEquals("", PreferencesRepository.normalizeDomain("intent://scan/#Intent;scheme=zxing;end"))
        assertEquals("", PreferencesRepository.normalizeDomain("data:text/html,x"))
        assertEquals("", PreferencesRepository.normalizeDomain("https://www./login"))
        assertEquals("", PreferencesRepository.normalizeDomain("example..com"))
        assertEquals("", PreferencesRepository.normalizeDomain("bad host.com"))
        assertEquals("", PreferencesRepository.normalizeDomain("[not-an-ipv6]"))
        assertEquals("", PreferencesRepository.normalizeDomain("127.0.0.1:80:80"))
        assertEquals("", PreferencesRepository.normalizeDomain("cafe.de:80:80"))
        assertEquals("", PreferencesRepository.normalizeDomain(":::"))
        assertEquals("", PreferencesRepository.normalizeDomain("1:2"))
        assertEquals("", PreferencesRepository.normalizeDomain("10.0.0"))
        assertEquals("1.example.com", PreferencesRepository.normalizeDomain("1.example.com"))
        assertEquals("", PreferencesRepository.normalizeDomain("example.com:"))
        assertEquals("", PreferencesRepository.normalizeDomain("[::1]:"))
        assertEquals("", PreferencesRepository.normalizeDomain("http:"))
        assertEquals("", PreferencesRepository.normalizeDomain("magnet:?xt=urn:btih:123"))
        assertEquals("", PreferencesRepository.normalizeDomain("example[1].com"))
        assertEquals("", PreferencesRepository.normalizeDomain("-example.com"))
        assertEquals("", PreferencesRepository.normalizeDomain("example-.com"))
        assertEquals("", PreferencesRepository.normalizeDomain("999.999.999.999"))
        assertEquals("chrome", PreferencesRepository.suggestCodeFromContext("about:blank", "com.android.chrome"))
        assertEquals("[::ffff:192.168.1.1]", PreferencesRepository.extractDomainStem("::ffff:192.168.1.1"))
        assertEquals("github", PreferencesRepository.suggestCodeFromContext(null, " com.github.android "))

        val prevLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("github.com", PreferencesRepository.normalizeDomain("HTTPS://GITHUB.COM/LOGIN"))
            assertEquals("github", PreferencesRepository.suggestCodeFromContext("HTTPS://GITHUB.COM", "COM.GITHUB.ANDROID"))
        } finally {
            Locale.setDefault(prevLocale)
        }
    }

    @Test
    fun `strips leading dot and wildcard so cookie-style domains still match`() {
        assertEquals("example.com", PreferencesRepository.normalizeDomain(".example.com"))
        assertEquals("example.com", PreferencesRepository.normalizeDomain("*.example.com"))
        assertEquals("example.com", PreferencesRepository.normalizeDomain(".*.example.com"))
        assertEquals("example.com", PreferencesRepository.normalizeDomain("*.www.Example.com:443"))
        assertEquals("", PreferencesRepository.normalizeDomain("*"))
        assertEquals("", PreferencesRepository.normalizeDomain("*."))
        assertEquals("github", PreferencesRepository.suggestCodeFromContext("*.github.com", null))
    }

    @Test
    fun `preserves www when it is the registrable domain label and keeps IPv4-mapped IPv6 whole`() {
        assertEquals("www.com", PreferencesRepository.normalizeDomain("https://www.com/login"))
        assertEquals("www.co.uk", PreferencesRepository.normalizeDomain("https://www.co.uk"))
        assertEquals("www.gov.in", PreferencesRepository.normalizeDomain("www.gov.in"))
        assertEquals("www", PreferencesRepository.extractDomainStem("https://www.com"))
        assertEquals("www", PreferencesRepository.extractDomainStem("https://www.co.uk"))
        assertEquals("[::ffff:192.168.1.1]", PreferencesRepository.extractDomainStem("http://[::ffff:192.168.1.1]:8080/login"))
    }

    @Test
    fun `autofill node domain resolver rejects non-network webScheme and normalizes valid domains`() {
        assertEquals("github.com", AssistStructureParser.resolveNodeDomain("www.github.com", "https"))
        assertEquals("github.com", AssistStructureParser.resolveNodeDomain("https://WWW.GitHub.com:443/login", "https"))
        assertNull(AssistStructureParser.resolveNodeDomain("localhost", "file"))
        assertNull(AssistStructureParser.resolveNodeDomain("com.android.providers.downloads", "content"))
        assertNull(AssistStructureParser.resolveNodeDomain("blank", "about"))
        assertNull(AssistStructureParser.resolveNodeDomain("void(0)", "javascript"))
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
