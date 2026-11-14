package io.github.lingyicute.starrypass

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.lingyicute.starrypass.data.PreferencesRepository
import io.github.lingyicute.starrypass.data.PresetRotationResult
import io.github.lingyicute.starrypass.data.SitePreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

class PresetIdentityTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun cleanUp() = scope.cancel()

    private suspend fun repository(raw: String): PreferencesRepository {
        val file = temporaryFolder.newFile("identity.preferences_pb")
        val store = PreferenceDataStoreFactory.create(scope = scope) { file }
        store.edit { it[stringPreferencesKey("site_presets_json")] = raw }
        return PreferencesRepository(store)
    }

    @Test
    fun `missing ids are deterministic across reads and unrelated saves`() = runBlocking {
        val repo = repository("""[{"code":" alpha ","counter":4}]""")
        val expectedId = UUID.nameUUIDFromBytes("alpha".toByteArray(Charsets.UTF_8)).toString()
        assertEquals(expectedId, repo.presetsFlow.first().single().id)
        assertEquals(expectedId, repo.presetsFlow.first().single().id)
        repo.upsertPreset(SitePreset(code = "beta"))
        assertEquals(expectedId, repo.presetsFlow.first().first { it.code == "alpha" }.id)
    }

    @Test
    fun `preset with missing id can be rotated using its displayed id`() = runBlocking {
        val repo = repository("""[{"code":"alpha","counter":4}]""")
        val shown = repo.presetsFlow.first().single()
        val result = repo.incrementPresetCounter(shown.id)
        assertEquals(PresetRotationResult.Updated::class, result::class)
        val updated = (result as PresetRotationResult.Updated).preset
        assertEquals(shown.id, updated.id)
        assertEquals(5, updated.counter)
    }

    @Test
    fun `preset with missing id can be deleted using its displayed id`() = runBlocking {
        val repo = repository("""[{"code":"alpha"}]""")
        repo.deletePreset(repo.presetsFlow.first().single().id)
        assertEquals(emptyList<SitePreset>(), repo.presetsFlow.first())
    }

    @Test
    fun `explicit stored ids retain precedence over generated ids`() = runBlocking {
        val repo = repository("""[{"id":" custom-id ","code":"alpha"},{"id":" ","code":"beta"}]""")
        val presets = repo.presetsFlow.first().associateBy { it.code }
        assertEquals("custom-id", presets.getValue("alpha").id)
        assertEquals(
            UUID.nameUUIDFromBytes("beta".toByteArray(Charsets.UTF_8)).toString(),
            presets.getValue("beta").id
        )
    }
}
