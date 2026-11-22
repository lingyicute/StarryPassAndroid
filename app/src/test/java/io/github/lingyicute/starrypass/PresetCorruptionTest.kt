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
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class PresetCorruptionTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @After fun cleanUp() = scope.cancel()

    @Test
    fun `every preset mutation preserves malformed source data for recovery`() = runBlocking {
        val samples = listOf(
            "{truncated", "{}", "null", " ",
            """[{"id":"a","code":"alpha","counter":42},null]""",
            """[{"id":"a","code":"alpha","counter":42},{"id":"broken"}]""",
            """[{"id":"a","code":"alpha","counter":42},{"code":"beta","length":"x"}]""",
            """[{"code":" "}]""",
            """[{"id":"a","code":"alpha"},{"id":"a","code":"beta"}]""",
            """[{"id":"a","code":"alpha"},{"id":"b","code":"alpha"}]"""
        )
        val mutations = listOf<suspend (PreferencesRepository) -> Unit>(
            { it.upsertPreset(SitePreset(code = "new")) },
            { it.savePresetPolicy(SitePreset(code = "alpha", counter = 99)) },
            { it.incrementPresetCounter("a") },
            { it.deletePreset("a") }
        )
        for ((index, raw) in samples.withIndex()) {
            val file = temporaryFolder.newFile("corrupt-$index.preferences_pb")
            val store = PreferenceDataStoreFactory.create(scope = scope) { file }
            val key = stringPreferencesKey("site_presets_json")
            store.edit { it[key] = raw }
            val repo = PreferencesRepository(store)
            for (mutate in mutations) {
                try {
                    mutate(repo)
                    fail("Mutation accepted corrupt sample $index")
                } catch (_: IOException) {
                    assertEquals(raw, store.data.first()[key])
                }
            }
        }
    }
}
