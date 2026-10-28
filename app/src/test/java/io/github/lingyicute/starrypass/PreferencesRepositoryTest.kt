package io.github.lingyicute.starrypass

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.lingyicute.starrypass.data.PreferencesRepository
import io.github.lingyicute.starrypass.data.SitePreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PreferencesRepositoryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun repository(): PreferencesRepository {
        val file = temporaryFolder.newFile("settings.preferences_pb")
        return PreferencesRepository(PreferenceDataStoreFactory.create(scope = scope) { file })
    }

    @After fun cleanUp() = scope.cancel()

    @Test
    fun `quick save preserves metadata while full editor can explicitly clear it`() = runBlocking {
        val repo = repository()
        val initial = SitePreset(
            id = "saved-id", code = "example", alias = "My site", autofillDomain = "example.com",
            autofillPackage = "com.example.app", length = 16, counter = 1
        )
        repo.upsertPreset(initial)
        repo.savePresetPolicy(SitePreset(code = " example ", modeKey = "pin", length = 6, counter = 8))

        val saved = repo.presetsFlow.first().single()
        assertEquals(initial.id, saved.id)
        assertEquals(initial.alias, saved.alias)
        assertEquals(initial.autofillDomain, saved.autofillDomain)
        assertEquals(initial.autofillPackage, saved.autofillPackage)
        assertEquals("pin", saved.modeKey)
        assertEquals(6, saved.length)
        assertEquals(8, saved.counter)

        repo.upsertPreset(saved.copy(alias = "", autofillDomain = "", autofillPackage = ""))
        val cleared = repo.presetsFlow.first().single()
        assertEquals("", cleared.alias)
        assertEquals("", cleared.autofillDomain)
        assertEquals("", cleared.autofillPackage)
    }

    @Test
    fun `quick save does not overwrite metadata changed concurrently by full editor`() = runBlocking {
        val repo = repository()
        val initial = SitePreset(code = "example", alias = "Old", autofillDomain = "old.example")
        repo.upsertPreset(initial)
        awaitAll(
            async { repo.savePresetPolicy(SitePreset(code = "example", counter = 2)) },
            async { repo.upsertPreset(initial.copy(alias = "New", autofillDomain = "new.example")) }
        )
        val saved = repo.presetsFlow.first().single()
        assertEquals("New", saved.alias)
        assertEquals("new.example", saved.autofillDomain)
        assertEquals(initial.id, saved.id)
    }
}
