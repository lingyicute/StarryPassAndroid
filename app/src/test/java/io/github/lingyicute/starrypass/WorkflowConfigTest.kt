/*
 * StarryPass Android — CI Workflow & Unsigned Release Invariant Tests
 * Copyright (C) 2024-2026 lingyicute
 * Licensed under GNU AGPL-3.0-or-later.
 */
package io.github.lingyicute.starrypass

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WorkflowConfigTest {

    @Test
    fun `nightly workflow publishes unsigned APK with safe tag-ordering invariants`() {
        val repoRoot = File("..").canonicalFile
        val workflowFile = File(repoRoot, ".github/workflows/create-release.yml")
        assertTrue("Missing .github/workflows/create-release.yml", workflowFile.exists())

        val workflow = workflowFile.readText()
        assertTrue(workflow.contains("contents: read"))
        assertTrue(workflow.contains("contents: write"))
        assertTrue(workflow.contains("uses: actions/checkout@v4"))
        assertFalse(workflow.contains("fetch-depth: 0"))
        assertTrue(workflow.contains("bash gradlew --no-daemon testDebugUnitTest assembleRelease"))
        assertTrue(workflow.contains("path: app/build/outputs/apk/release/app-release-unsigned.apk"))
        assertTrue(workflow.contains("""git tag --force nightly "${'$'}GITHUB_SHA""""))
        assertTrue(workflow.contains("git push --force origin refs/tags/nightly"))
        assertTrue(workflow.contains("""gh release edit nightly --notes "${'$'}notes" --target "${'$'}GITHUB_SHA""""))

        val uploadIdx = workflow.indexOf("gh release upload nightly apk/app-release-unsigned.apk --clobber")
        val pushTagIdx = workflow.indexOf("git push --force origin refs/tags/nightly")
        val editReleaseIdx = workflow.indexOf("gh release edit nightly")
        assertTrue("Upload must precede moving the nightly Git tag", uploadIdx in 0 until pushTagIdx)
        assertTrue("Moving the Git tag must precede editing release metadata", pushTagIdx < editReleaseIdx)
    }

    @Test
    fun `release build does not manage signing keys in repository`() {
        val repoRoot = File("..").canonicalFile
        val appGradle = File(repoRoot, "app/build.gradle.kts").readText()
        assertFalse("app/build.gradle.kts must not configure signingConfigs", appGradle.contains("signingConfigs"))
        assertFalse("app/build.gradle.kts must not set signingConfig", appGradle.contains("signingConfig ="))

        val strayKeystores = repoRoot.walkTopDown()
            .filter { it.isFile && (it.extension == "jks" || it.extension == "keystore") }
            .toList()
        assertTrue("Repository must not contain keystore files: $strayKeystores", strayKeystores.isEmpty())
    }
}
