package io.github.mikeyphw.mmrl.shared

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class SharedCoreTest {
    private val repository = GitHubRepository.parse("Mikeyphw/MMRL-LSPosed")!!

    @Test fun githubRepositoryStrict() {
        assertEquals("Mikeyphw/MMRL-LSPosed", repository.fullName)
        for (invalid in listOf("", "owner", "a/b/c", "a/..", "a/b..c", "a/b?token=abc", "a/%2e", "a/é", " a/b")) {
            assertNull(invalid, GitHubRepository.parse(invalid))
        }
    }

    @Test fun releaseIdentityBindsExactUrl() {
        val asset = ReleaseAssetIdentity.of(repository, "v0.2.0", "app-debug.apk")!!
        assertTrue(asset.matchesAssetUrl("https://github.com/Mikeyphw/MMRL-LSPosed/releases/download/v0.2.0/app-debug.apk"))
        assertFalse(asset.matchesAssetUrl("http://github.com/Mikeyphw/MMRL-LSPosed/releases/download/v0.2.0/app-debug.apk"))
        assertFalse(asset.matchesAssetUrl(asset.assetUrl + "?download=1"))
        assertFalse(asset.matchesAssetUrl(asset.assetUrl.replace("MMRL-LSPosed", "MMRL")))
        assertTrue(asset.matchesReleasePage("https://github.com/Mikeyphw/MMRL-LSPosed/releases/tag/v0.2.0"))
        assertNull(ReleaseAssetIdentity.of(repository, "v..2", "app.zip"))
        assertNull(ReleaseAssetIdentity.of(repository, "v2", "../evil.apk"))
        assertNull(ReleaseAssetIdentity.of(repository, "v2", "app.exe"))
    }

    @Test fun digestIsExactAndVerifiable() {
        val message = "abc".toByteArray()
        val value = Sha256Digest.calculate(message)
        assertEquals("sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", value.prefixed)
        assertTrue(value.verifies(message))
        assertTrue(value.verifies(ByteArrayInputStream(message)))
        assertFalse(value.verifies("abcd".toByteArray()))
        assertEquals(value, Sha256Digest.parse(value.prefixed))
        assertNull(Sha256Digest.parse("SHA256:" + value.hex))
        assertNull(Sha256Digest.parse(value.hex.uppercase()))
        assertNull(Sha256Digest.parse(value.hex.dropLast(1)))
    }

    @Test fun githubRunRequiresExactAttemptAndSource() {
        val id = GitHubRunIdentity.of(repository, 100, 2, "a".repeat(40), "push", "main")!!
        assertTrue(id.matchesCurrentRun(100, 2, "a".repeat(40), "push", "main", "completed", "success"))
        assertFalse(id.matchesCurrentRun(100, 1, "a".repeat(40), "push", "main", "completed", "success"))
        assertTrue(id.matchesArtifactOwner(100, "a".repeat(40)))
        assertFalse(id.matchesArtifactOwner(101, "a".repeat(40)))
        assertNull(GitHubRunIdentity.of(repository, -1, 2, "a".repeat(40), "push", "main"))
        assertNull(GitHubRunIdentity.of(repository, 10, 2, "a".repeat(40), "push", "../branch"))
    }

    @Test fun stableOrderingDoesNotDependOnLocale() {
        assertTrue(StableModuleOrder.compare("alpha", "a", "Beta", "b") < 0)
        assertTrue(StableModuleOrder.compare("Tool", "aaa", "tool", "zzz") < 0)
        assertTrue(StableModuleOrder.compare("tool", "x", "Tool", "x") > 0)
        assertEquals(0, StableModuleOrder.compare("item", "x", "item", "x"))
    }
}
