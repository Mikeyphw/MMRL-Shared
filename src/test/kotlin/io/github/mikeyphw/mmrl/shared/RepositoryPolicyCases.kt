package io.github.mikeyphw.mmrl.shared

object RepositoryPolicyCases {
    private val repo = RepositoryIdentity.parse("Mikeyphw/MMRL")!!
    private val binding = RepositoryIdentity.bind(repo, "Mikeyphw/MMRL", 42)!!
    private val other = RepositoryIdentity.bind(repo, "Mikeyphw/MMRL", 43)!!
    private val digest = Sha256Digest.calculate("asset".encodeToByteArray())
    private val names = CandidateNamePolicy.of(exclude = listOf("symbols"), prefer = listOf("arm64"))!!
    private val policy = RepositorySourcePolicy(binding, TransferFormat.ROOT_ZIP, ReleaseChannel.STABLE_ONLY, names, ActionsAllowlist.of(setOf(14), setOf("next")))
    private val sha = "a".repeat(40)
    private val run = GitHubRunIdentity.of(repo, 20, 2, sha, "push", "next")!!
    private fun release(p: RepositorySourcePolicy = policy, observed: RepositoryBinding = binding, asset: Long = 10,
                        name: String = "module.zip", url: String = "https://github.com/Mikeyphw/MMRL/releases/download/v1/$name",
                        prerelease: Boolean = false, draft: Boolean = false, published: Long = 5, checksum: Sha256Digest? = digest) =
        ReleaseCandidate.of(p, observed, 7, asset, "v1", name, url, draft, prerelease, published, 5, checksum)
    private fun actions(p: RepositorySourcePolicy = policy, observed: RepositoryBinding = binding,
                        workflow: Long = 14, runIdentity: GitHubRunIdentity = run, headRepoId: Long = 42,
                        ownerRun: Long = 20, ownerSha: String = sha, ownerRepoId: Long = 42,
                        expired: Boolean = false, expiry: Long = 500, now: Long = 100,
                        status: String = "completed", conclusion: String = "success",
                        url: String = "https://api.github.com/repos/Mikeyphw/MMRL/actions/artifacts/90/zip") =
        ActionsCandidate.of(p, observed, workflow, runIdentity, status, conclusion, headRepoId, 90, "module-arm64", url,
            ownerRun, ownerSha, ownerRepoId, expired, expiry, now, 6, 5, digest)
    fun repositoryAndRelease() {
        check(RepositoryIdentity.parse("a/b") != null)
        for (value in listOf("https://github.com/a/b", "a/b/c", "../repo", "a_/repo", "-a/repo", "a--b/repo", "a/b%2f", "a/b?token=secret")) check(RepositoryIdentity.parse(value) == null)
        check(RepositoryIdentity.bind(repo, "mikeyphw/mmrl", 42)?.matches(binding) == true)
        check(RepositoryIdentity.bind(repo, "Mikeyphw/Foreign", 42) == null)
        check(RepositoryIdentity.bind(repo, "Mikeyphw/MMRL", 0) == null)
        check(release() != null)
        check(release(observed = other) == null)
        check(release(url = "https://github.com/other/MMRL/releases/download/v1/module.zip") == null)
        check(release(url = "https://github.com/Mikeyphw/MMRL/releases/download/v1/module.zip?token=secret") == null)
        check(release(name = "../bad.zip") == null)
        check(release(name = "module.apk") == null)
        check(release(name = "module-symbols.zip") == null)
        check(release(prerelease = true) == null && release(draft = true) == null)
        check(release(p = policy.copy(channel = ReleaseChannel.INCLUDE_PRERELEASE), prerelease = true) != null)
        check(release(p = policy.copy(format = TransferFormat.XPOSED_APK), name = "module.apk") != null)
        val a = release()!!
        check(a.revalidate(release()!!))
        check(!a.revalidate(release(checksum = null)!!))
        check(!a.revalidate(release(asset = 11)!!))
        check(release(checksum = null)!!.expectedDigest == null)
        check(a.transferSourceIdentity() == release()!!.transferSourceIdentity())
        check(a.transferSourceIdentity() != release(asset = 11)!!.transferSourceIdentity())
    }
    fun actionsAndRedirects() {
        val a = actions()!!
        check(actions(p = policy.copy(actions = null)) == null)
        check(actions(observed = other) == null)
        check(actions(workflow = 15) == null)
        check(actions(runIdentity = GitHubRunIdentity.of(repo, 20, 2, sha, "push", "master")!!) == null)
        check(actions(runIdentity = GitHubRunIdentity.of(repo, 20, 2, sha, "pull_request", "next")!!) == null)
        check(actions(headRepoId = 77) == null && actions(ownerRepoId = 77) == null)
        check(actions(ownerRun = 21) == null && actions(ownerSha = "b".repeat(40)) == null)
        check(actions(expired = true) == null && actions(now = 500) == null)
        check(actions(status = "in_progress") == null && actions(conclusion = "failure") == null)
        check(actions(url = "https://api.github.com/repos/Mikeyphw/Foreign/actions/artifacts/90/zip") == null)
        check(a.revalidate(actions()!!, 100))
        check(!a.revalidate(actions(runIdentity = GitHubRunIdentity.of(repo, 20, 3, sha, "push", "next")!!)!!, 100))
        check(!a.revalidate(actions()!!, 500))
        check(RepositoryRedirectPolicy.allow(a, a.downloadUrl, 0))
        check(RepositoryRedirectPolicy.attachToken(a, a.downloadUrl, 0))
        check(!RepositoryRedirectPolicy.attachToken(a, a.downloadUrl, 1))
        check(!RepositoryRedirectPolicy.attachToken(a, "https://api.github.com/repos/other/repo/actions/artifacts/90/zip", 0))
        check(RepositoryRedirectPolicy.allow(a, "https://objects.githubusercontent.com/blob?signature=secret", 1))
        check(!RepositoryRedirectPolicy.attachToken(a, "https://objects.githubusercontent.com/blob?signature=secret", 1))
        for (url in listOf("http://objects.githubusercontent.com/blob", "https://objects.githubusercontent.com.evil.test/blob", "https://evil.test/blob", "https://user@objects.githubusercontent.com/blob", "https://objects.githubusercontent.com:444/blob", "https://objects.githubusercontent.com/blob#secret", "https://127.0.0.1/blob")) check(!RepositoryRedirectPolicy.allow(a, url, 1))
        check(!RepositoryRedirectPolicy.allow(a, "https://objects.githubusercontent.com/blob", 4))
        val release = release()!!
        check(!RepositoryRedirectPolicy.attachToken(release, release.downloadUrl, 0))
        check(RepositoryRedirectPolicy.allow(release, release.apiAssetUrl, 0))
        check(RepositoryRedirectPolicy.attachToken(release, release.apiAssetUrl, 0))
        check(!RepositoryRedirectPolicy.attachToken(release, release.apiAssetUrl, 1))
        check(!RepositoryRedirectPolicy.allow(a, "https://storage.blob.core.windows.net/path", 1))
        check(RepositoryRedirectPolicy.allow(a, "https://storage.blob.core.windows.net/path?sig=fixture", 1))
        check(!RepositoryRedirectPolicy.allow(a, "https://storage.blob.core.windows.net/path?sig=fixture", 2))
    }
    fun rankAndCache() {
        val a = release(asset = 1, name = "generic.zip")!!
        val b = release(asset = 2, name = "arm64.zip")!!
        val c = release(asset = 3, name = "older.zip", published = 4)!!
        check(RepositoryCandidatePolicy.rank(policy, listOf(c, a, b), 100).map { it.stableIdentity } == listOf(b, a, c).map { it.stableIdentity })
        check(RepositoryCandidatePolicy.rank(policy, emptyList(), 100).isEmpty())
        check(runCatching { RepositoryCandidatePolicy.rank(policy, listOf(a, a), 100) }.isFailure)
        check(RepositoryCandidatePolicy.rank(policy.copy(actions = null), listOf(actions()!!), 100).isEmpty())
        check(RepositoryCandidatePolicy.rank(policy, listOf(actions()!!), 500).isEmpty())
        val stamp = RepositoryCacheStamp(binding, 3, 100, 60)
        check(stamp.status(binding, 3, 159, true) == RepositoryCacheStatus.FRESH)
        check(stamp.status(binding, 3, 160, true) == RepositoryCacheStatus.STALE)
        check(stamp.status(binding, 3, 101, false) == RepositoryCacheStatus.STALE)
        check(stamp.status(binding, 3, 99, true) == RepositoryCacheStatus.STALE)
        check(stamp.status(other, 3, 101, true) == RepositoryCacheStatus.WRONG_IDENTITY)
        check(stamp.status(binding, 4, 101, true) == RepositoryCacheStatus.WRONG_IDENTITY)
        check(RepositoryMetadataLimits.acceptPage(5, 100, 100, 1000, 400))
        check(!RepositoryMetadataLimits.acceptPage(6, 100, 100, 1000, 400))
        check(!RepositoryMetadataLimits.acceptPage(1, Long.MAX_VALUE, 1, 0, 0))
        check(!RepositoryMetadataLimits.acceptPage(1, 100, 101, 0, 0))
        check(!RepositoryMetadataLimits.acceptPage(5, 100, 100, Long.MAX_VALUE, 400))
        check(RepositoryMetadataLimits.readPage("[]".byteInputStream(), 2) == "[]")
        check(runCatching { RepositoryMetadataLimits.readPage("[]".byteInputStream(), 1) }.isFailure)
        check(runCatching { RepositoryMetadataLimits.readPage(byteArrayOf(0xc3.toByte(), 0x28).inputStream()) }.isFailure)
        check(runCatching { RepositoryMetadataLimits.readPage(ByteArray(0).inputStream()) }.isFailure)
        check(runCatching { RepositoryMetadataLimits.readPage(ByteArray(RepositoryMetadataLimits.MAX_PAGE_BYTES + 1).inputStream()) }.isFailure)
        check(RepositoryMetadataLimits.positiveId("9223372036854775807") == Long.MAX_VALUE)
        check(RepositoryMetadataLimits.positiveId("9223372036854775808") == null)
        check(RepositoryMetadataLimits.positiveId("+42") == null)
        check(RepositoryMetadataLimits.digest(digest.prefixed) == digest)
        check(RepositoryMetadataLimits.digest("sha256:invalid") == null)
    }
    @JvmStatic fun main(args: Array<String>) {
        repositoryAndRelease(); println("PASS repository and release identity")
        actionsAndRedirects(); println("PASS Actions and credential boundaries")
        rankAndCache(); println("PASS ranking, cache and metadata limits")
    }
}
