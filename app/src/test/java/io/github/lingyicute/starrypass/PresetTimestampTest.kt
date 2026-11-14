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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PresetTimestampTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun cleanUp() = scope.cancel()

    private suspend fun repository(raw: String): PreferencesRepository {
        val file = temporaryFolder.newFile("timestamps.preferences_pb")
        val store = PreferenceDataStoreFactory.create(scope = scope) { file }
        store.edit { it[stringPreferencesKey("site_presets_json")] = raw }
        return PreferencesRepository(store)
    }

    @Test
    fun `missing timestamps use an unknown epoch instead of the decoding time`() = runBlocking {
        val repo = repository("""[{"id":"legacy","code":"alpha"}]""")
        assertEquals(0L, repo.presetsFlow.first().single().updatedAtEpochMs)
        assertEquals(0L, repo.presetsFlow.first().single().updatedAtEpochMs)
    }

    @Test
    fun `unknown timestamps do not outrank known edits`() = runBlocking {
        val repo = repository(
            """[{"id":"legacy","code":"alpha"},{"id":"known","code":"beta","updatedAtEpochMs":1}]"""
        )
        assertEquals(listOf("known", "legacy"), repo.presetsFlow.first().map { it.id })
        assertEquals(1L, repo.presetsFlow.first().first().updatedAtEpochMs)
    }

    @Test
    fun `unrelated saves do not silently refresh legacy timestamps`() = runBlocking {
        val repo = repository("""[{"id":"legacy","code":"alpha","counter":2}]""")
        repo.upsertPreset(SitePreset(code = "beta"))
        assertEquals(0L, repo.presetsFlow.first().first { it.id == "legacy" }.updatedAtEpochMs)
        val rotated = repo.incrementPresetCounter("legacy") as PresetRotationResult.Updated
        assertEquals(3, rotated.preset.counter)
        assertTrue(rotated.preset.updatedAtEpochMs > 0L)
    }

    @Test
    fun `explicit timestamps and timestamps of new presets are preserved`() = runBlocking {
        val repo = repository("""[{"id":"known","code":"alpha","updatedAtEpochMs":123}]""")
        assertEquals(123L, repo.presetsFlow.first().single().updatedAtEpochMs)
        repo.upsertPreset(SitePreset(id = "new", code = "beta"))
        assertTrue(repo.presetsFlow.first().first { it.id == "new" }.updatedAtEpochMs > 0L)
        assertEquals(123L, repo.presetsFlow.first().first { it.id == "known" }.updatedAtEpochMs)
    }
}
