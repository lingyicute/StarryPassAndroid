package io.github.lingyicute.starrypass

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.lingyicute.starrypass.data.PreferencesRepository
import io.github.lingyicute.starrypass.data.SitePreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PresetDomainBindingTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun cleanUp() = scope.cancel()

    private suspend fun repository(raw: String = "[]"): PreferencesRepository {
        val file = temporaryFolder.newFile("bindings.preferences_pb")
        val store = PreferenceDataStoreFactory.create(scope = scope) { file }
        store.edit { it[stringPreferencesKey("site_presets_json")] = raw }
        return PreferencesRepository(store)
    }

    @Test
    fun `invalid legacy domain bindings do not become unbound code guesses`() = runBlocking {
        val repo = repository(
            """[{"id":"legacy","code":"example","autofillDomain":" old_site.com "}]"""
        )
        assertNull(repo.findMatchingPreset("https://example.com/login", null))
        assertEquals("old_site.com", repo.presetsFlow.first().single().autofillDomain)
    }

    @Test
    fun `policy changes and unrelated writes retain invalid domain bindings`() = runBlocking {
        val repo = repository(
            """[{"id":"legacy","code":"example.com","autofillDomain":"http:"}]"""
        )
        repo.savePresetPolicy(SitePreset(code = "example.com", counter = 5))
        repo.incrementPresetCounter("legacy")
        repo.upsertPreset(SitePreset(id = "other", code = "other"))
        repo.deletePreset("other")
        val saved = repo.presetsFlow.first().single()
        assertEquals(6, saved.counter)
        assertEquals("http:", saved.autofillDomain)
        assertNull(repo.findMatchingPreset("example.com", null))
    }

    @Test
    fun `repository writes cannot silently turn an invalid binding into an empty one`() = runBlocking {
        val repo = repository()
        repo.upsertPreset(SitePreset(code = "example.com", autofillDomain = "\uFEFFbad host.com\u00A0"))
        assertEquals("bad host.com", repo.presetsFlow.first().single().autofillDomain)
        assertNull(repo.findMatchingPreset("example.com", null))
    }

    @Test
    fun `only explicitly clearing the domain binding re-enables code matching`() = runBlocking {
        val repo = repository(
            """[{"id":"legacy","code":"example","autofillDomain":"old_site.com"}]"""
        )
        val preset = repo.presetsFlow.first().single()
        repo.upsertPreset(preset.copy(autofillDomain = "\uFEFF\u00A0"))
        assertEquals("", repo.presetsFlow.first().single().autofillDomain)
        assertEquals("legacy", repo.findMatchingPreset("example.com", null)?.id)
    }

    @Test
    fun `valid legacy domains still normalize and explicit packages still match`() = runBlocking {
        val repo = repository(
            """[{"id":"valid","code":"alpha","autofillDomain":"https://WWW.Example.com/login"},{"id":"invalid","code":"beta","autofillDomain":"bad_host.com","autofillPackage":"com.example.app"}]"""
        )
        assertEquals("valid", repo.findMatchingPreset("login.example.com", null)?.id)
        assertEquals("example.com", repo.presetsFlow.first().first { it.id == "valid" }.autofillDomain)
        assertEquals("invalid", repo.findMatchingPreset(null, "com.example.app")?.id)
    }
}
