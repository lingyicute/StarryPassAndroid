package io.github.lingyicute.starrypass

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.lingyicute.starrypass.data.AppSettings
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
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class PreferencesTypeSafetyTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @After fun cleanUp() = scope.cancel()

    @Test
    fun `unexpected setting types fall back without killing security collectors`() = runBlocking {
        val file = temporaryFolder.newFile("types.preferences_pb")
        val store = PreferenceDataStoreFactory.create(scope = scope) { file }
        store.edit {
            it[intPreferencesKey("theme")] = 42
            it[stringPreferencesKey("dynamic_color")] = "false"
            it[intPreferencesKey("flag_secure")] = 0
            it[stringPreferencesKey("wipe_on_background")] = "false"
            it[stringPreferencesKey("clipboard_auto_clear_sec")] = "off"
            it[intPreferencesKey("colorize_password")] = 0
        }
        val repo = PreferencesRepository(store)
        assertEquals(AppSettings(), repo.settingsFlow.first())
        store.edit { it[intPreferencesKey("clipboard_auto_clear_sec")] = -30 }
        assertEquals(45, repo.settingsFlow.first().clipboardAutoClearSeconds)
    }

    @Test
    fun `unexpected preset type is not replaced by an unrelated save`() = runBlocking {
        val file = temporaryFolder.newFile("preset-types.preferences_pb")
        val store = PreferenceDataStoreFactory.create(scope = scope) { file }
        val key = intPreferencesKey("site_presets_json")
        store.edit { it[key] = 42 }
        val repo = PreferencesRepository(store)
        assertEquals(emptyList<SitePreset>(), repo.presetsFlow.first())
        try {
            repo.upsertPreset(SitePreset(code = "new"))
            fail("Must not overwrite an unexpected stored type")
        } catch (_: IOException) {
            assertEquals(42, store.data.first()[key])
        }
    }
}
