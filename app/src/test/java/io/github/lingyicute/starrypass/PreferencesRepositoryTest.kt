package io.github.lingyicute.starrypass

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.lingyicute.starrypass.data.AppSettings
import io.github.lingyicute.starrypass.data.PreferencesRepository
import io.github.lingyicute.starrypass.data.PresetRotationResult
import io.github.lingyicute.starrypass.data.SitePreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class PreferencesRepositoryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun repository(): PreferencesRepository {
        val file = temporaryFolder.newFile("settings.preferences_pb")
        return PreferencesRepository(PreferenceDataStoreFactory.create(scope = scope) { file })
    }

    @After fun cleanUp() = scope.cancel()

    @Test
    fun `unreadable datastore falls back to defaults instead of failing collectors`() = runBlocking {
        val failing = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("disk unavailable") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                throw IOException("disk unavailable")
        }
        val repo = PreferencesRepository(failing)

        assertEquals(AppSettings(), repo.settingsFlow.first())
        assertEquals(emptyList<SitePreset>(), repo.presetsFlow.first())
    }

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
    fun `rotation reaches 9999 once and then rejects without modifying the preset`() = runBlocking {
        val repo = repository()
        val preset = SitePreset(code = "example", counter = 9998)
        repo.upsertPreset(preset)
        val first = repo.incrementPresetCounter(preset.id) as PresetRotationResult.Updated
        assertEquals(9999, first.preset.counter)
        assertEquals(PresetRotationResult.LimitReached, repo.incrementPresetCounter(preset.id))
        assertEquals(first.preset, repo.presetsFlow.first().single())
        assertEquals(PresetRotationResult.NotFound, repo.incrementPresetCounter("missing"))
    }

    @Test
    fun `concurrent rotations at the boundary have exactly one success`() = runBlocking {
        val repo = repository()
        val preset = SitePreset(code = "example", counter = 9998)
        repo.upsertPreset(preset)
        val results = awaitAll(
            async(Dispatchers.Default) { repo.incrementPresetCounter(preset.id) },
            async(Dispatchers.Default) { repo.incrementPresetCounter(preset.id) }
        )
        assertEquals(1, results.count { it is PresetRotationResult.Updated })
        assertEquals(1, results.count { it == PresetRotationResult.LimitReached })
        assertEquals(9999, repo.presetsFlow.first().single().counter)
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

    @Test
    fun `renaming a preset onto an existing code replaces that entry instead of duplicating it`() = runBlocking {
        val repo = repository()
        val first = SitePreset(code = "alpha", alias = "A")
        val second = SitePreset(code = "beta", alias = "B")
        repo.upsertPreset(first)
        repo.upsertPreset(second)

        repo.upsertPreset(second.copy(code = "alpha"))

        val saved = repo.presetsFlow.first().single()
        assertEquals(second.id, saved.id)
        assertEquals("alpha", saved.code)
        assertEquals("B", saved.alias)
    }

    @Test
    fun `renaming a preset to a free code keeps the other presets`() = runBlocking {
        val repo = repository()
        val first = SitePreset(code = "alpha")
        val second = SitePreset(code = "beta")
        repo.upsertPreset(first)
        repo.upsertPreset(second)

        repo.upsertPreset(first.copy(code = "gamma"))

        assertEquals(setOf("gamma", "beta"), repo.presetsFlow.first().map { it.code }.toSet())
    }

    @Test
    fun `autofill matching prefers the most specific saved domain over a newer parent domain`() = runBlocking {
        val repo = repository()
        val specific = SitePreset(code = "admin-console", autofillDomain = "admin.example.com")
        val parent = SitePreset(code = "example", autofillDomain = "example.com")
        repo.upsertPreset(specific)
        repo.upsertPreset(parent)

        assertEquals(specific.id, repo.findMatchingPreset("admin.example.com", null)?.id)
        assertEquals(specific.id, repo.findMatchingPreset("eu.admin.example.com", null)?.id)
        assertEquals(parent.id, repo.findMatchingPreset("shop.example.com", null)?.id)
    }

    @Test
    fun `one unreadable stored preset does not hide or erase the others`() = runBlocking {
        val file = temporaryFolder.newFile("damaged.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        dataStore.edit {
            it[stringPreferencesKey("site_presets_json")] =
                """[{"id":"a","code":"alpha"},{"id":"broken"},{"id":"b","code":"beta","length":"x"}]"""
        }
        val repo = PreferencesRepository(dataStore)

        assertEquals(listOf("alpha"), repo.presetsFlow.first().map { it.code })

        repo.upsertPreset(SitePreset(code = "gamma"))

        assertEquals(setOf("alpha", "gamma"), repo.presetsFlow.first().map { it.code }.toSet())
    }

    @Test
    fun `autofill matching normalises domains stored in an older format`() = runBlocking {
        val file = temporaryFolder.newFile("legacy.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        dataStore.edit {
            it[stringPreferencesKey("site_presets_json")] =
                """[{"id":"a","code":"mail","autofillDomain":".Example.com."}]"""
        }
        val repo = PreferencesRepository(dataStore)

        assertEquals("a", repo.findMatchingPreset("login.example.com", null)?.id)
    }

    @Test
    fun `autofill matching uses web domain instead of host app package when both are present`() = runBlocking {
        val repo = repository()
        val exactDomainCode = SitePreset(id = "exact", code = "gist.github.com")
        val stemCode = SitePreset(id = "stem", code = "github", counter = 3)
        val pkgBound = SitePreset(id = "pkg", code = "corp-sso", autofillPackage = "com.example.corp")
        repo.upsertPreset(exactDomainCode)
        repo.upsertPreset(stemCode)
        repo.upsertPreset(pkgBound)

        assertEquals("exact", repo.findMatchingPreset("https://gist.github.com/login", null)?.id)
        assertEquals("stem", repo.findMatchingPreset("https://github.com/login", "com.example.corp")?.id)
        assertEquals(null, repo.findMatchingPreset("https://unrelated.example/login", "com.example.corp")?.id)
        assertEquals("pkg", repo.findMatchingPreset(null, "com.example.corp")?.id)
        assertEquals("stem", repo.findMatchingPreset(null, "com.github.android")?.id)
    }

    @Test
    fun `explicit package bindings remain case-sensitive`() = runBlocking {
        val repo = repository()
        repo.upsertPreset(SitePreset(id = "upper", code = "upper-app", autofillPackage = "Com.Example.App"))
        repo.upsertPreset(SitePreset(id = "lower", code = "lower-app", autofillPackage = "com.example.app"))

        assertEquals("upper", repo.findMatchingPreset(null, "Com.Example.App")?.id)
        assertEquals("lower", repo.findMatchingPreset(null, "com.example.app")?.id)
        assertEquals(null, repo.findMatchingPreset(null, "COM.EXAMPLE.APP")?.id)
    }

    @Test
    fun `browser package binding cannot follow the browser onto another website`() = runBlocking {
        val repo = repository()
        repo.upsertPreset(SitePreset(id = "browser", code = "chrome", autofillPackage = "com.android.chrome"))
        repo.upsertPreset(SitePreset(id = "site", code = "shop", autofillDomain = "shop.example.com"))

        assertEquals("site", repo.findMatchingPreset("https://shop.example.com/login", "com.android.chrome")?.id)
        assertEquals(null, repo.findMatchingPreset("https://other.example.com/login", "com.android.chrome")?.id)
        assertEquals("browser", repo.findMatchingPreset(null, "com.android.chrome")?.id)
    }

    @Test
    fun `autofill matching does not guess a preset from the browser package on a web page`() = runBlocking {
        val repo = repository()
        repo.upsertPreset(SitePreset(id = "browser", code = "chrome"))

        assertEquals(null, repo.findMatchingPreset("https://unknown.example/login", "com.android.chrome")?.id)
        assertEquals("browser", repo.findMatchingPreset(null, "com.android.chrome")?.id)
    }

    @Test
    fun `autofill matching does not offer the platform preset on a user-content page`() = runBlocking {
        val repo = repository()
        repo.upsertPreset(SitePreset(id = "platform", code = "github"))

        assertEquals(null, repo.findMatchingPreset("https://someone.github.io/login", null)?.id)
        assertEquals("platform", repo.findMatchingPreset("https://gist.github.com/login", null)?.id)
    }

    @Test
    fun `decodePresets skips blank codes or duplicate ids and clamps out-of-range fields`() = runBlocking {
        val file = temporaryFolder.newFile("sanitised.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        dataStore.edit {
            it[stringPreferencesKey("site_presets_json")] =
                """[{"id":"dup","code":"  "},{"id":"dup","code":" alpha ","length":999,"counter":-5},{"id":"dup","code":"beta"}]"""
        }
        val repo = PreferencesRepository(dataStore)

        val loaded = repo.presetsFlow.first().single()
        assertEquals("dup", loaded.id)
        assertEquals("alpha", loaded.code)
        assertEquals(64, loaded.length)
        assertEquals(1, loaded.counter)
    }

    @Test
    fun `decodePresets normalises autofillDomain and skips duplicate site codes`() = runBlocking {
        val file = temporaryFolder.newFile("dup_codes.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        dataStore.edit {
            it[stringPreferencesKey("site_presets_json")] =
                """[{"id":"id1","code":" github ","autofillDomain":"https://WWW.GitHub.com/login"},{"id":"id2","code":"github","autofillDomain":"gist.github.com"}]"""
        }
        val repo = PreferencesRepository(dataStore)

        val loaded = repo.presetsFlow.first().single()
        assertEquals("id1", loaded.id)
        assertEquals("github", loaded.code)
        assertEquals("github.com", loaded.autofillDomain)
    }

    @Test
    fun `decodePresets does not reserve the id of an entry rejected for a duplicate code`() = runBlocking {
        val file = temporaryFolder.newFile("reserved_id.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        dataStore.edit {
            it[stringPreferencesKey("site_presets_json")] =
                """[{"id":"id1","code":"alpha","counter":3},{"id":"id2","code":"alpha"},{"id":"id2","code":"beta","counter":5}]"""
        }
        val repo = PreferencesRepository(dataStore)

        val loaded = repo.presetsFlow.first().associateBy { it.code }
        assertEquals(setOf("alpha", "beta"), loaded.keys)
        assertEquals("id1", loaded.getValue("alpha").id)
        assertEquals("id2", loaded.getValue("beta").id)
        assertEquals(5, loaded.getValue("beta").counter)
    }

    @Test
    fun `ecmaTrim sanitizes non-ASCII whitespace in alias and package and matches parent domain codes`() = runBlocking {
        val repo = repository()
        val preset = SitePreset(
            id = "\uFEFFid-domain\u00A0",
            code = "github.com",
            alias = "\u00A0\u3000\uFEFF",
            autofillPackage = "\u00A0com.github.android\u3000"
        )
        repo.upsertPreset(preset)

        val saved = repo.presetsFlow.first().single()
        assertEquals("id-domain", saved.id)
        assertEquals("", saved.alias)
        assertEquals("github.com", saved.displayName)
        assertEquals("com.github.android", saved.autofillPackage)
        assertEquals(saved.id, repo.findMatchingPreset("https://sso.github.com/login", null)?.id)
        assertEquals(saved.id, repo.findMatchingPreset(null, "\uFEFFcom.github.android\u00A0")?.id)

        // Renaming via the original untrimmed preset reference must update in place rather than drop the edit.
        repo.upsertPreset(preset.copy(code = "gitlab.com"))
        val renamed = repo.presetsFlow.first().single()
        assertEquals("id-domain", renamed.id)
        assertEquals("gitlab.com", renamed.code)
    }

    @Test
    fun `matches IPv6 and non-dotted host codes and keeps blank stored ids deterministic`() = runBlocking {
        val file = temporaryFolder.newFile("ipv6_and_blank_id.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        dataStore.edit {
            it[stringPreferencesKey("site_presets_json")] =
                """[{"id":"  ","code":"::1","counter":2},{"id":"local","code":"http://localhost:8080"}]"""
        }
        val repo = PreferencesRepository(dataStore)

        val ipv6Preset = repo.findMatchingPreset("http://[::1]:8080/login", null)!!
        assertEquals("::1", ipv6Preset.code)
        assertEquals(ipv6Preset.id, repo.presetsFlow.first().first { it.code == "::1" }.id)

        val rotated = repo.incrementPresetCounter(ipv6Preset.id) as PresetRotationResult.Updated
        assertEquals(3, rotated.preset.counter)

        val localhostPreset = repo.findMatchingPreset("http://localhost:3000/login", null)!!
        assertEquals("local", localhostPreset.id)
    }

    @Test
    fun `purpose-qualified site code does not shadow stem preset during domain autofill matching`() = runBlocking {
        val repo = repository()
        val mainPreset = SitePreset(id = "main", code = "github")
        val tokenPreset = SitePreset(id = "token", code = "github.com/token")
        repo.upsertPreset(mainPreset)
        repo.upsertPreset(tokenPreset)

        assertEquals("main", repo.findMatchingPreset("https://github.com/login", null)?.id)
    }

    @Test
    fun `public suffixes, user-content suffixes, and single-label hosts only match exact domains`() = runBlocking {
        val repo = repository()
        repo.upsertPreset(SitePreset(id = "gh-pages", code = "github.io", autofillDomain = "github.io"))
        repo.upsertPreset(SitePreset(id = "uk-suffix", code = "co.uk", autofillDomain = "co.uk"))
        repo.upsertPreset(SitePreset(id = "tld", code = "tld", autofillDomain = "com"))
        repo.upsertPreset(SitePreset(id = "local", code = "local", autofillDomain = "localhost"))
        repo.upsertPreset(SitePreset(id = "tenant", code = "tenant", autofillDomain = "someone.github.io"))

        assertEquals("gh-pages", repo.findMatchingPreset("https://github.io/", null)?.id)
        assertEquals(null, repo.findMatchingPreset("https://other.github.io/login", null)?.id)
        assertEquals("tenant", repo.findMatchingPreset("https://docs.someone.github.io/login", null)?.id)
        assertEquals("uk-suffix", repo.findMatchingPreset("https://co.uk/", null)?.id)
        assertEquals(null, repo.findMatchingPreset("https://shop.co.uk/login", null)?.id)
        assertEquals(null, repo.findMatchingPreset("https://example.com/login", null)?.id)
        assertEquals("local", repo.findMatchingPreset("http://localhost:8080/login", null)?.id)
        assertEquals(null, repo.findMatchingPreset("http://evil.localhost:8080/login", null)?.id)
    }
}
