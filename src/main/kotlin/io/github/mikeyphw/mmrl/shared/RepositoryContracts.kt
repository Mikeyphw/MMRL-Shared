package io.github.mikeyphw.mmrl.shared

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

object RepositoryContractsSchema { const val VERSION = 1 }

/** A configured identity must be confirmed against GitHub's canonical numeric repository ID. */
data class RepositoryBinding(val repository: GitHubRepository, val repositoryId: Long) {
    init { require(repositoryId > 0 && RepositoryIdentity.valid(repository)) }
    fun matches(other: RepositoryBinding): Boolean = repositoryId == other.repositoryId &&
        repository.fullName.equals(other.repository.fullName, ignoreCase = true)
}
object RepositoryIdentity {
    private val owner = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?")
    private val name = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")
    fun valid(repository: GitHubRepository): Boolean = owner.matches(repository.owner) &&
        !repository.owner.contains("--") && name.matches(repository.name) && !repository.name.contains("..")
    fun parse(value: String): GitHubRepository? = GitHubRepository.parse(value)?.takeIf(::valid)
    fun bind(requested: GitHubRepository, returnedFullName: String, id: Long): RepositoryBinding? {
        val actual = parse(returnedFullName) ?: return null
        if (!valid(requested) || id <= 0 || !requested.fullName.equals(actual.fullName, true)) return null
        return RepositoryBinding(actual, id)
    }
}

enum class ReleaseChannel { STABLE_ONLY, INCLUDE_PRERELEASE }
/** Literal bounded rules avoid executing arbitrary untrusted regex against API metadata. */
class CandidateNamePolicy private constructor(private val include: List<String>, private val exclude: List<String>, private val prefer: List<String>) {
    fun allows(name: String): Boolean = (include.isEmpty() || include.any { name.contains(it, true) }) && !exclude.any { name.contains(it, true) }
    fun preference(name: String): Int = prefer.indexOfFirst { name.contains(it, true) }.let { if (it < 0) 0 else prefer.size - it }
    companion object {
        private val literal = Regex("[A-Za-z0-9_. -]{1,80}")
        fun of(include: List<String> = emptyList(), exclude: List<String> = emptyList(), prefer: List<String> = emptyList()): CandidateNamePolicy? {
            if (listOf(include, exclude, prefer).any { it.size > 16 || it.any { value -> !literal.matches(value) || value.isBlank() } }) return null
            return CandidateNamePolicy(include.toList(), exclude.toList(), prefer.toList())
        }
    }
}
class ActionsAllowlist private constructor(private val workflows: Set<Long>, private val branches: Set<String>) {
    fun allows(workflowId: Long, branch: String): Boolean = workflowId in workflows && branch in branches
    companion object {
        fun of(workflows: Set<Long>, branches: Set<String>): ActionsAllowlist? {
            if (workflows.isEmpty() || workflows.size > 32 || workflows.any { it <= 0 } || branches.isEmpty() || branches.size > 32 ||
                branches.any { !Regex("[A-Za-z0-9][A-Za-z0-9_.\\-/]{0,150}").matches(it) || it.contains("..") || it.contains("//") || it.endsWith('/') }) return null
            return ActionsAllowlist(workflows.toSet(), branches.toSet())
        }
    }
}
/** Credential-free source policy. APK/ZIP choice describes the desired module, not its publisher trust. */
data class RepositorySourcePolicy(val repository: RepositoryBinding, val format: TransferFormat,
                                  val channel: ReleaseChannel, val names: CandidateNamePolicy,
                                  val actions: ActionsAllowlist? = null) {
    init { require(format == TransferFormat.ROOT_ZIP || format == TransferFormat.XPOSED_APK) }
}

/** Validated metadata only; no assertion of publisher trust or permission to install. */
sealed class RepositoryCandidate {
    abstract val repository: RepositoryBinding
    abstract val stableIdentity: String
    abstract val name: String
    abstract val publishedAtMillis: Long
    abstract val expectedSize: Long
    abstract val expectedDigest: Sha256Digest?
    abstract val downloadUrl: String
    fun transferSourceIdentity(): OperationId = OperationId.parse("github:" + Sha256Digest.calculate(stableIdentity.encodeToByteArray()).hex)!!
}
class ReleaseCandidate private constructor(
    override val repository: RepositoryBinding, val releaseId: Long, val assetId: Long,
    val identity: ReleaseAssetIdentity, val prerelease: Boolean,
    override val publishedAtMillis: Long, override val expectedSize: Long, override val expectedDigest: Sha256Digest?,
) : RepositoryCandidate() {
    override val stableIdentity: String get() = "release:${repository.repositoryId}:$releaseId:$assetId"
    override val name: String get() = identity.name
    override val downloadUrl: String get() = identity.assetUrl
    val apiAssetUrl: String get() = "https://api.github.com/repos/${repository.repository.fullName}/releases/assets/$assetId"
    /** Compare newly fetched asset metadata before use; cached data is not a current download authorization. */
    fun revalidate(current: ReleaseCandidate): Boolean = repository.matches(current.repository) &&
        releaseId == current.releaseId && assetId == current.assetId && identity.tag == current.identity.tag &&
        name == current.name && downloadUrl == current.downloadUrl && prerelease == current.prerelease &&
        expectedSize == current.expectedSize && expectedDigest == current.expectedDigest
    companion object {
        fun of(policy: RepositorySourcePolicy, observedRepository: RepositoryBinding, releaseId: Long, assetId: Long,
               tag: String, assetName: String, assetUrl: String, draft: Boolean, prerelease: Boolean,
               publishedAtMillis: Long, size: Long, digest: Sha256Digest?): ReleaseCandidate? {
            if (!policy.repository.matches(observedRepository) || releaseId <= 0 || assetId <= 0 || draft ||
                (prerelease && policy.channel == ReleaseChannel.STABLE_ONLY) || publishedAtMillis < 0 || size !in 1..TransferRequest.MAX_BYTES ||
                !TransferNames.valid(assetName, policy.format) || !policy.names.allows(assetName)) return null
            val identity = ReleaseAssetIdentity.of(observedRepository.repository, tag, assetName) ?: return null
            if (!identity.matchesAssetUrl(assetUrl)) return null
            return ReleaseCandidate(observedRepository, releaseId, assetId, identity, prerelease, publishedAtMillis, size, digest)
        }
    }
}

/** The outer Actions archive must be verified separately, then inspected for the selected module format. */
class ActionsCandidate private constructor(
    override val repository: RepositoryBinding, val workflowId: Long, val run: GitHubRunIdentity,
    val artifactId: Long, override val name: String, override val publishedAtMillis: Long,
    override val expectedSize: Long, override val expectedDigest: Sha256Digest?, val expiresAtMillis: Long,
    val moduleFormat: TransferFormat,
) : RepositoryCandidate() {
    override val stableIdentity: String get() = "actions:${repository.repositoryId}:$workflowId:${run.runId}:${run.attempt}:${run.commitSha}:$artifactId"
    override val downloadUrl: String get() = "https://api.github.com/repos/${repository.repository.fullName}/actions/artifacts/$artifactId/zip"
    fun revalidate(current: ActionsCandidate, nowMillis: Long): Boolean =
        repository.matches(current.repository) && workflowId == current.workflowId &&
            run.repository.fullName.equals(current.run.repository.fullName, true) &&
            run.matchesCurrentRun(current.run.runId, current.run.attempt, current.run.commitSha, current.run.event, current.run.branch, "completed", "success") &&
            artifactId == current.artifactId && name == current.name && downloadUrl == current.downloadUrl &&
            expectedSize == current.expectedSize && expectedDigest == current.expectedDigest && moduleFormat == current.moduleFormat &&
            nowMillis in 0 until minOf(expiresAtMillis, current.expiresAtMillis)
    companion object {
        private val artifactName = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,119}")
        fun of(policy: RepositorySourcePolicy, observedRepository: RepositoryBinding, workflowId: Long,
               run: GitHubRunIdentity, runStatus: String, runConclusion: String, headRepositoryId: Long,
               artifactId: Long, artifactName: String, artifactUrl: String, artifactRunId: Long, artifactCommit: String,
               artifactRepositoryId: Long, expired: Boolean, expiresAtMillis: Long, nowMillis: Long,
               publishedAtMillis: Long, size: Long, digest: Sha256Digest?): ActionsCandidate? {
            if (!policy.repository.matches(observedRepository) || !run.repository.fullName.equals(observedRepository.repository.fullName, true) ||
                headRepositoryId != observedRepository.repositoryId || artifactRepositoryId != observedRepository.repositoryId ||
                policy.actions?.allows(workflowId, run.branch) != true || run.event != "push" ||
                runStatus != "completed" || runConclusion != "success" || !run.matchesArtifactOwner(artifactRunId, artifactCommit) ||
                artifactId <= 0 || expired || nowMillis < 0 || expiresAtMillis <= nowMillis || publishedAtMillis !in 0..nowMillis ||
                size !in 1..TransferRequest.MAX_BYTES || !this.artifactName.matches(artifactName) || artifactName.contains("..") ||
                !policy.names.allows(artifactName)) return null
            val candidate = ActionsCandidate(observedRepository, workflowId, run, artifactId, artifactName,
                publishedAtMillis, size, digest, expiresAtMillis, policy.format)
            return candidate.takeIf { it.downloadUrl == artifactUrl }
        }
    }
}

object RepositoryCandidatePolicy {
    /** Reapply the current policy on cached and freshly obtained candidates; no automatic installation. */
    fun rank(policy: RepositorySourcePolicy, candidates: List<RepositoryCandidate>, nowMillis: Long): List<RepositoryCandidate> {
        require(candidates.size <= RepositoryMetadataLimits.MAX_CANDIDATES && nowMillis >= 0)
        val admitted = candidates.filter { candidate -> policy.repository.matches(candidate.repository) &&
            policy.names.allows(candidate.name) && candidate.publishedAtMillis <= nowMillis && when (candidate) {
                is ReleaseCandidate -> TransferNames.valid(candidate.name, policy.format) &&
                    (!candidate.prerelease || policy.channel == ReleaseChannel.INCLUDE_PRERELEASE)
                is ActionsCandidate -> candidate.moduleFormat == policy.format && candidate.expiresAtMillis > nowMillis &&
                    policy.actions?.allows(candidate.workflowId, candidate.run.branch) == true
            }
        }
        // Reject duplicate conflicting metadata rather than accepting whichever page happened to arrive first.
        require(admitted.map { it.stableIdentity }.distinct().size == admitted.size)
        return admitted.sortedWith(compareByDescending<RepositoryCandidate> { it.publishedAtMillis }
            .thenByDescending { policy.names.preference(it.name) }.thenBy { it.stableIdentity })
    }
}
object RepositoryMetadataLimits {
    const val MAX_PAGE_BYTES = 2 * 1024 * 1024
    const val MAX_PAGES = 5
    const val MAX_ITEMS_PER_PAGE = 100
    const val MAX_CANDIDATES = MAX_PAGES * MAX_ITEMS_PER_PAGE
    const val MAX_TOTAL_BYTES = MAX_PAGE_BYTES * MAX_PAGES
    fun acceptPage(page: Int, bytes: Long, items: Int, totalBytesBeforePage: Long, itemsBeforePage: Int): Boolean =
        page in 1..MAX_PAGES && bytes in 1..MAX_PAGE_BYTES.toLong() && items in 0..MAX_ITEMS_PER_PAGE &&
            totalBytesBeforePage in 0..MAX_TOTAL_BYTES.toLong() - bytes && itemsBeforePage in 0..MAX_CANDIDATES - items
    /** Bound bytes before parsing; reject malformed UTF-8 rather than substituting characters. */
    fun readPage(input: InputStream, remainingBudget: Long = MAX_PAGE_BYTES.toLong()): String {
        require(remainingBudget in 1..MAX_TOTAL_BYTES.toLong())
        val limit = minOf(remainingBudget, MAX_PAGE_BYTES.toLong())
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            if (count !in 1..buffer.size || count.toLong() > limit - total) throw IOException("Invalid or oversized repository metadata")
            out.write(buffer, 0, count)
            total += count
        }
        if (total == 0L) throw IOException("Empty repository metadata")
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(out.toByteArray())).toString()
    }
    fun positiveId(raw: String): Long? = raw.takeIf { it.length in 1..19 && it.first() in '1'..'9' && it.all { c -> c in '0'..'9' } }?.toLongOrNull()
    fun digest(raw: String): Sha256Digest? = raw.takeIf { it.length in 64..71 }?.let(Sha256Digest::parse)
}

enum class RepositoryCacheStatus { FRESH, STALE, WRONG_IDENTITY }
/** Generation is a CAS token owned by the consumer, not a timestamp-based stale-response guess. */
data class RepositoryCacheStamp(val repository: RepositoryBinding, val generation: Long, val fetchedAtMillis: Long, val ttlMillis: Long) {
    init { require(generation >= 0 && fetchedAtMillis >= 0 && ttlMillis in 1..86_400_000) }
    fun status(expected: RepositoryBinding, expectedGeneration: Long, nowMillis: Long, online: Boolean): RepositoryCacheStatus = when {
        !repository.matches(expected) || generation != expectedGeneration -> RepositoryCacheStatus.WRONG_IDENTITY
        !online || nowMillis < fetchedAtMillis || nowMillis - fetchedAtMillis >= ttlMillis -> RepositoryCacheStatus.STALE
        else -> RepositoryCacheStatus.FRESH
    }
}

/** No credential is forwarded to a redirected URL. HTTP client must disable auto-redirect and enforce the decision per hop. */
object RepositoryRedirectPolicy {
    private val cdnHosts = setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com")
    private fun secureUri(value: String): URI? {
        if (value.length !in 1..8192 || value.any { it.code <= 32 || it.code == 127 || it == '\\' }) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        return uri.takeIf { it.scheme == "https" && it.host != null && it.rawUserInfo == null && it.port == -1 && it.rawFragment == null }
    }
    fun attachToken(candidate: RepositoryCandidate, url: String, redirectCount: Int): Boolean =
        redirectCount == 0 && secureUri(url)?.host == "api.github.com" && when (candidate) {
            is ActionsCandidate -> url == candidate.downloadUrl
            is ReleaseCandidate -> url == candidate.apiAssetUrl
        }
    fun allow(candidate: RepositoryCandidate, url: String, redirectCount: Int): Boolean {
        if (redirectCount !in 0..3) return false
        val uri = secureUri(url) ?: return false
        if (redirectCount == 0) return url == candidate.downloadUrl || (candidate is ReleaseCandidate && url == candidate.apiAssetUrl)
        // Actions' signed Azure blob destinations are provider-scoped and must never receive a GitHub token.
        val host = uri.host.lowercase(Locale.ROOT)
        return host in cdnHosts || (candidate is ActionsCandidate && redirectCount == 1 &&
            Regex("[a-z0-9]{3,24}\\.blob\\.core\\.windows\\.net").matches(host) && uri.rawQuery?.contains("sig=") == true)
    }
}
