package io.github.lingyicute.starrypass

import io.github.lingyicute.starrypass.autofill.AssistStructureParser
import org.junit.Assert.assertEquals
import org.junit.Test

class AutofillRevalidationTest {
    private val selfPackage = "io.github.lingyicute.starrypass"

    private fun targets(
        hasLiveStructure: Boolean = true,
        liveParseFailed: Boolean = false,
        liveTargets: List<String> = listOf("live-password"),
        liveDomain: String? = "example.com",
        expectedDomain: String? = "example.com",
        livePackage: String? = "com.android.chrome",
        expectedPackage: String? = "com.android.chrome"
    ): List<String> = AssistStructureParser.resolveAuthenticatedTargets(
        hasLiveStructure = hasLiveStructure,
        liveParseFailed = liveParseFailed,
        liveTargets = liveTargets,
        liveWebDomain = liveDomain,
        livePackageName = livePackage,
        expectedWebDomain = expectedDomain,
        expectedPackageName = expectedPackage,
        selfPackageName = selfPackage
    )

    @Test
    fun `failed live web parsing never authorizes stale field ids`() {
        assertEquals(
            emptyList<String>(),
            targets(liveParseFailed = true, liveTargets = emptyList(), liveDomain = null, livePackage = null)
        )
    }

    @Test
    fun `failed live native parsing never authorizes stale field ids`() {
        assertEquals(
            emptyList<String>(),
            targets(
                liveParseFailed = true,
                liveTargets = emptyList(),
                liveDomain = null,
                expectedDomain = null,
                livePackage = null,
                expectedPackage = "com.example.app"
            )
        )
    }

    @Test
    fun `absent live structures cannot authorize stale fields`() {
        assertEquals(emptyList<String>(), targets(hasLiveStructure = false))
    }

    @Test
    fun `neither fallback nor live structures may target StarryPass`() {
        assertEquals(emptyList<String>(), targets(hasLiveStructure = false, expectedPackage = selfPackage))
        assertEquals(emptyList<String>(), targets(livePackage = selfPackage, expectedPackage = selfPackage))
    }

    @Test
    fun `successfully revalidated structures use fresh targets`() {
        assertEquals(listOf("live-password"), targets())
        assertEquals(emptyList<String>(), targets(liveTargets = emptyList()))
    }

    @Test
    fun `successful parsing still rejects origin or package changes`() {
        assertEquals(emptyList<String>(), targets(liveDomain = "other.example"))
        assertEquals(emptyList<String>(), targets(livePackage = "com.other.app"))
    }
    @Test
    fun `missing package identity cannot authorize either live or stale fields`() {
        assertEquals(emptyList<String>(), targets(livePackage = null, expectedPackage = null))
        assertEquals(emptyList<String>(), targets(hasLiveStructure = false, expectedPackage = null))
        assertEquals(emptyList<String>(), targets(livePackage = "", expectedPackage = ""))
    }
}
