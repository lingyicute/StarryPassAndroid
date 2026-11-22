package io.github.lingyicute.starrypass

import io.github.lingyicute.starrypass.data.backupCorruptedFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class CorruptionBackupTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `successive corruptions retain distinct recovery copies and the source`() {
        val source = temporaryFolder.newFile("settings.preferences_pb")
        source.writeText("first damaged file")
        val first = checkNotNull(backupCorruptedFile(source))
        source.writeText("second damaged file")
        val second = checkNotNull(backupCorruptedFile(source))
        assertNotEquals(first, second)
        assertEquals("first damaged file", first.readText())
        assertEquals("second damaged file", second.readText())
        assertEquals("second damaged file", source.readText())
    }

    @Test
    fun `backup failure propagates instead of authorizing replacement`() {
        val source = temporaryFolder.newFolder("unreadable-as-file")
        try {
            backupCorruptedFile(source)
            fail("A failed backup must prevent destructive recovery")
        } catch (_: IOException) {
            assertTrue(source.isDirectory)
        }
    }

    @Test
    fun `an absent source has nothing to back up`() {
        assertNull(backupCorruptedFile(File(temporaryFolder.root, "missing")))
    }
}
