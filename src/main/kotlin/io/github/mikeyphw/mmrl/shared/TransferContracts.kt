package io.github.mikeyphw.mmrl.shared

/** Transport/storage-free policy; consumers own durable compare-and-set and all IO. */
object TransferContractsSchema { const val VERSION = 1 }
enum class TransferFormat(val suffix: String) { ROOT_ZIP(".zip"), XPOSED_APK(".apk"), ACTIONS_ARCHIVE(".zip") }
enum class TransferState { QUEUED, TRANSFERRING, VERIFYING, READY_TO_PUBLISH, PUBLISHING, PUBLISHED, RETRY_WAIT, INTERRUPTED, FAILED, CANCELLED, RECONCILIATION_REQUIRED }
enum class TransferFailure(val retryable: Boolean) {
    OFFLINE(true), TIMEOUT(true), HTTP_TRANSIENT(true), RATE_LIMIT(true),
    HTTP_PERMANENT(false), IDENTITY_CHANGED(false), RANGE_INVALID(false), LENGTH_MISMATCH(false),
    CHECKSUM_MISSING(false), CHECKSUM_MISMATCH(false), EMPTY(false), TOO_LARGE(false),
    DISK_FULL(false), DESTINATION_EXISTS(false), FORMAT_INVALID(false), STORAGE_FAILURE(false),
}

object TransferNames {
    private val safe = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,119}\\.(apk|zip)", RegexOption.IGNORE_CASE)
    fun valid(name: String, format: TransferFormat): Boolean = safe.matches(name) &&
        !name.contains("..") && name.endsWith(format.suffix, ignoreCase = true)
}

/** The operation ID is the stable queue identity. Each retry/recovery has a new app-owned Activity attempt ID. */
data class TransferRequest(
    val operationId: OperationId,
    val sourceIdentity: OperationId,
    val destinationNamespace: OperationId,
    val fileName: String,
    val format: TransferFormat,
    val expectedSize: Long?,
    val expectedDigest: Sha256Digest?,
    val maxBytes: Long,
) {
    init {
        require(TransferNames.valid(fileName, format))
        require(maxBytes in 1..MAX_BYTES && (expectedSize == null || expectedSize in 1..maxBytes))
    }
    companion object { const val MAX_BYTES = 1024L * 1024 * 1024 }
}

/** Never persist the raw signed redirect URL, authorization header or opaque ETag in Activity. */
data class RepresentationIdentity(val sourceIdentity: OperationId, val strongEtagDigest: Sha256Digest, val totalBytes: Long) {
    init { require(totalBytes in 1..TransferRequest.MAX_BYTES) }
    companion object {
        fun fromStrongEtag(source: OperationId, etag: String?, totalBytes: Long): RepresentationIdentity? {
            if (etag == null || etag.length !in 3..512 || etag.first() != '"' || etag.last() != '"' ||
                etag.substring(1, etag.lastIndex).any { it.code !in 33..126 || it == '"' } ||
                totalBytes !in 1..TransferRequest.MAX_BYTES) return null
            return RepresentationIdentity(source, Sha256Digest.calculate(etag.encodeToByteArray()), totalBytes)
        }
    }
}

data class ResumeResponse(
    val status: Int,
    val identity: RepresentationIdentity?,
    val rangeStart: Long?,
    val rangeEndInclusive: Long?,
    val contentLength: Long?,
    val identityEncoding: Boolean,
)
enum class ResumeDecision { APPEND, RESTART, REFUSE }
/** A validated response is bound to the exact request and partial byte count. */
class ResumeProof private constructor(val request: TransferRequest, val representation: RepresentationIdentity, val stagedBytes: Long) {
    companion object {
        fun fromResponse(request: TransferRequest, previous: RepresentationIdentity?, stagedBytes: Long, response: ResumeResponse): ResumeProof? =
            if (previous != null && TransferResumePolicy.decide(request, previous, stagedBytes, response) == ResumeDecision.APPEND)
                ResumeProof(request, previous, stagedBytes) else null
    }
}
object TransferResumePolicy {
    fun decide(request: TransferRequest, previous: RepresentationIdentity?, stagedBytes: Long, response: ResumeResponse): ResumeDecision {
        if (stagedBytes !in 1..request.maxBytes) return ResumeDecision.REFUSE
        if (previous == null || previous.sourceIdentity != request.sourceIdentity || previous.totalBytes > request.maxBytes ||
            request.expectedSize?.let { it != previous.totalBytes } == true || stagedBytes >= previous.totalBytes) return ResumeDecision.RESTART
        if (response.status == 200 || response.status == 416) return ResumeDecision.RESTART
        if (response.status != 206) return ResumeDecision.REFUSE
        if (!response.identityEncoding || response.identity != previous) return ResumeDecision.RESTART
        if (response.rangeStart != stagedBytes || response.rangeEndInclusive != previous.totalBytes - 1 ||
            response.contentLength != previous.totalBytes - stagedBytes) return ResumeDecision.RESTART
        return ResumeDecision.APPEND
    }
}

/** Whole-file observation, not a digest of a partial suffix. The consumer must hash the staged bytes. */
data class TransferObservation(val bytes: Long, val digest: Sha256Digest, val formatConfirmed: Boolean)
class VerifiedTransfer private constructor(val request: TransferRequest, val observation: TransferObservation) {
    companion object {
        fun of(request: TransferRequest, observation: TransferObservation): VerifiedTransfer? =
            if (TransferIntegrity.failure(request, observation) == null) VerifiedTransfer(request, observation) else null
    }
}
object TransferIntegrity {
    /** Caller opens the entire staged file and closes the stream; a suffix-only hash cannot prove the full request. */
    fun observe(request: TransferRequest, completeStream: java.io.InputStream, formatConfirmed: Boolean): TransferObservation {
        val observed = Sha256Digest.calculateBounded(completeStream, request.maxBytes)
        return TransferObservation(observed.bytes, observed.digest, formatConfirmed)
    }
    fun failure(request: TransferRequest, actual: TransferObservation): TransferFailure? = when {
        actual.bytes <= 0 -> TransferFailure.EMPTY
        actual.bytes > request.maxBytes -> TransferFailure.TOO_LARGE
        request.expectedSize != null && request.expectedSize != actual.bytes -> TransferFailure.LENGTH_MISMATCH
        request.expectedDigest == null -> TransferFailure.CHECKSUM_MISSING
        request.expectedDigest != actual.digest -> TransferFailure.CHECKSUM_MISMATCH
        !actual.formatConfirmed -> TransferFailure.FORMAT_INVALID
        else -> null
    }
}

object TransferFailurePolicy {
    fun http(status: Int): TransferFailure? = when (status) {
        200, 206 -> null
        408 -> TransferFailure.TIMEOUT
        429 -> TransferFailure.RATE_LIMIT
        500, 502, 503, 504 -> TransferFailure.HTTP_TRANSIENT
        else -> TransferFailure.HTTP_PERMANENT
    }
}

enum class PublicationDecision { PUBLISH_NO_REPLACE, REUSE_VERIFIED, REFUSE_EXISTING, REFUSE_STORAGE }
/** Consumers inspect under their exclusive destination lock and still use a no-replace atomic move. */
object TransferPublicationPolicy {
    fun decide(verified: VerifiedTransfer, destinationAbsent: Boolean, existing: TransferObservation?, safeRegularFile: Boolean): PublicationDecision {
        if (destinationAbsent) return if (existing == null && safeRegularFile) PublicationDecision.PUBLISH_NO_REPLACE else PublicationDecision.REFUSE_STORAGE
        if (!safeRegularFile) return PublicationDecision.REFUSE_STORAGE
        return if (existing == verified.observation && TransferIntegrity.failure(verified.request, existing) == null)
            PublicationDecision.REUSE_VERIFIED else PublicationDecision.REFUSE_EXISTING
    }
}

/** Bounded automatic retries only for transient read-only transfer failures. */
data class TransferRetryPolicy(val maxAttempts: Int = 3, val baseMillis: Long = 1000, val capMillis: Long = 60_000) {
    init { require(maxAttempts in 1..8 && baseMillis in 1..capMillis && capMillis in 1..300_000) }
    fun delayMillis(attempt: Int, failure: TransferFailure, retryAfterMillis: Long? = null): Long? {
        if (!failure.retryable || attempt !in 1 until maxAttempts) return null
        if (retryAfterMillis != null && retryAfterMillis !in 0..capMillis) return null
        var delay = baseMillis
        repeat(attempt - 1) { delay = minOf(capMillis, delay * 2) }
        return maxOf(delay, retryAfterMillis ?: 0)
    }
}

/** Atomic publication requires exact full read-back, never just successful rename. */
class TransferReceipt private constructor(val request: TransferRequest, val observation: TransferObservation, val publishedAtMillis: Long) {
    companion object {
        fun confirm(verified: VerifiedTransfer, readBack: TransferObservation, atMillis: Long): TransferReceipt? =
            if (atMillis >= 0 && verified.observation == readBack && TransferIntegrity.failure(verified.request, readBack) == null)
                TransferReceipt(verified.request, readBack, atMillis) else null
    }
}

/** Pure immutable state snapshot. Store and update with CAS on revision in the app's DB. */
class TransferRecord private constructor(
    val request: TransferRequest,
    val state: TransferState,
    val revision: Long,
    val attempt: Int,
    val stagedBytes: Long,
    val representation: RepresentationIdentity?,
    val verified: VerifiedTransfer?,
    val receipt: TransferReceipt?,
    val failure: TransferFailure?,
    val retryAtMillis: Long?,
) {
    private fun next(state: TransferState, attempt: Int = this.attempt, bytes: Long = stagedBytes,
                     representation: RepresentationIdentity? = this.representation, verified: VerifiedTransfer? = null,
                     receipt: TransferReceipt? = null, failure: TransferFailure? = null, retryAtMillis: Long? = null): TransferRecord? =
        if (revision == Long.MAX_VALUE) null else TransferRecord(request, state, revision + 1, attempt, bytes,
            representation, verified, receipt, failure, retryAtMillis)

    fun start(expectedRevision: Long, nowMillis: Long, policy: TransferRetryPolicy, resume: ResumeProof? = null): TransferRecord? {
        if (revision != expectedRevision || nowMillis < 0 || attempt >= policy.maxAttempts ||
            state !in setOf(TransferState.QUEUED, TransferState.RETRY_WAIT, TransferState.INTERRUPTED) ||
            (retryAtMillis != null && nowMillis < retryAtMillis)) return null
        if (resume != null && (resume.request != request || resume.representation != representation || resume.stagedBytes != stagedBytes)) return null
        return next(TransferState.TRANSFERRING, attempt + 1, if (resume != null) stagedBytes else 0,
            if (resume != null) representation else null)
    }
    fun progress(expectedRevision: Long, bytes: Long, identity: RepresentationIdentity?): TransferRecord? {
        if (revision != expectedRevision || state != TransferState.TRANSFERRING || bytes !in stagedBytes..request.maxBytes ||
            request.expectedSize?.let { bytes > it } == true ||
            identity?.let { it.sourceIdentity != request.sourceIdentity || bytes > it.totalBytes || it.totalBytes > request.maxBytes ||
                request.expectedSize?.let { expected -> expected != it.totalBytes } == true } == true ||
            (representation != null && representation != identity)) return null
        return next(state, bytes = bytes, representation = identity)
    }
    fun finishTransfer(expectedRevision: Long): TransferRecord? =
        if (revision == expectedRevision && state == TransferState.TRANSFERRING && stagedBytes > 0 &&
            (representation == null || stagedBytes == representation.totalBytes) &&
            (request.expectedSize == null || stagedBytes == request.expectedSize)) next(TransferState.VERIFYING) else null
    fun verify(expectedRevision: Long, observation: TransferObservation): TransferRecord? {
        if (revision != expectedRevision || state != TransferState.VERIFYING) return null
        if (observation.bytes != stagedBytes) return next(TransferState.FAILED, failure = TransferFailure.LENGTH_MISMATCH)
        val result = VerifiedTransfer.of(request, observation) ?: return next(TransferState.FAILED, failure = TransferIntegrity.failure(request, observation))
        return next(TransferState.READY_TO_PUBLISH, verified = result)
    }
    /** The app must acquire an exclusive, no-overwrite destination and persist this transition before IO. */
    fun beginPublication(expectedRevision: Long): TransferRecord? =
        if (revision == expectedRevision && state == TransferState.READY_TO_PUBLISH && verified != null)
            next(TransferState.PUBLISHING, verified = verified) else null
    fun confirmPublication(expectedRevision: Long, actual: TransferObservation, atMillis: Long): TransferRecord? {
        if (revision != expectedRevision || state !in setOf(TransferState.PUBLISHING, TransferState.RECONCILIATION_REQUIRED)) return null
        val proof = verified ?: return null
        val result = TransferReceipt.confirm(proof, actual, atMillis)
        return if (result != null) next(TransferState.PUBLISHED, receipt = result)
        else next(TransferState.RECONCILIATION_REQUIRED, verified = proof)
    }
    fun publicationAbsent(expectedRevision: Long, confirmedAbsent: Boolean): TransferRecord? =
        if (revision == expectedRevision && state == TransferState.RECONCILIATION_REQUIRED && confirmedAbsent)
            next(TransferState.FAILED, failure = TransferFailure.STORAGE_FAILURE) else null
    fun fail(expectedRevision: Long, cause: TransferFailure, nowMillis: Long, policy: TransferRetryPolicy,
             retryAfterMillis: Long? = null): TransferRecord? {
        if (revision != expectedRevision || nowMillis < 0 || state !in setOf(TransferState.TRANSFERRING, TransferState.VERIFYING, TransferState.READY_TO_PUBLISH)) return null
        val delay = if (state == TransferState.TRANSFERRING) policy.delayMillis(attempt, cause, retryAfterMillis) else null
        if (delay == null || nowMillis > Long.MAX_VALUE - delay) return next(TransferState.FAILED, failure = cause)
        return next(TransferState.RETRY_WAIT, failure = cause, retryAtMillis = nowMillis + delay)
    }
    fun cancel(expectedRevision: Long): TransferRecord? =
        if (revision == expectedRevision && state in setOf(TransferState.QUEUED, TransferState.TRANSFERRING,
                TransferState.VERIFYING, TransferState.READY_TO_PUBLISH, TransferState.RETRY_WAIT, TransferState.INTERRUPTED))
            next(TransferState.CANCELLED, bytes = 0, representation = null) else null
    fun recover(expectedRevision: Long): TransferRecord? {
        if (revision != expectedRevision) return null
        return when (state) {
            TransferState.TRANSFERRING, TransferState.VERIFYING, TransferState.READY_TO_PUBLISH -> next(TransferState.INTERRUPTED)
            TransferState.PUBLISHING -> next(TransferState.RECONCILIATION_REQUIRED, verified = verified)
            else -> null
        }
    }
    companion object {
        fun enqueue(request: TransferRequest): TransferRecord = TransferRecord(request, TransferState.QUEUED, 0, 0, 0, null, null, null, null, null)
        /** Rehydrate durable fields with strict validation; never reconstitute publication proof from status alone. */
        fun restore(request: TransferRequest, state: TransferState, revision: Long, attempt: Int, stagedBytes: Long,
                    representation: RepresentationIdentity?, verified: VerifiedTransfer?, receipt: TransferReceipt?,
                    failure: TransferFailure?, retryAtMillis: Long?): TransferRecord? {
            if (revision < attempt.toLong() || attempt !in 0..8 || stagedBytes !in 0..request.maxBytes ||
                request.expectedSize?.let { stagedBytes > it } == true ||
                representation?.let { it.sourceIdentity != request.sourceIdentity || stagedBytes > it.totalBytes || it.totalBytes > request.maxBytes ||
                    request.expectedSize?.let { expected -> expected != it.totalBytes } == true } == true ||
                verified?.let { it.request != request || it.observation.bytes != stagedBytes } == true ||
                receipt?.let { it.request != request || it.observation.bytes != stagedBytes } == true ||
                ((state in setOf(TransferState.READY_TO_PUBLISH, TransferState.PUBLISHING, TransferState.RECONCILIATION_REQUIRED)) != (verified != null)) ||
                ((state == TransferState.PUBLISHED) != (receipt != null)) ||
                ((state in setOf(TransferState.FAILED, TransferState.RETRY_WAIT)) != (failure != null)) ||
                ((state == TransferState.RETRY_WAIT) != (retryAtMillis != null)) || retryAtMillis?.let { it < 0 } == true ||
                (state == TransferState.RETRY_WAIT && failure?.retryable != true) ||
                (state == TransferState.QUEUED && (revision != 0L || attempt != 0 || stagedBytes != 0L || representation != null)) ||
                (state != TransferState.QUEUED && state != TransferState.CANCELLED && attempt == 0) ||
                (state == TransferState.CANCELLED && (stagedBytes != 0L || representation != null)) ||
                (state in setOf(TransferState.VERIFYING, TransferState.READY_TO_PUBLISH, TransferState.PUBLISHING, TransferState.RECONCILIATION_REQUIRED, TransferState.PUBLISHED) && (stagedBytes <= 0 || representation?.let { it.totalBytes != stagedBytes } == true ||
                    request.expectedSize?.let { it != stagedBytes } == true))) return null
            return TransferRecord(request, state, revision, attempt, stagedBytes, representation, verified, receipt, failure, retryAtMillis)
        }
    }
}

/** Projection of the current attempt only. A retry/recovery starts a new linked Activity attempt, not a transition on an old terminal record. */
object TransferActivityProjection {
    fun status(record: TransferRecord): OperationStatus = when (record.state) {
        TransferState.QUEUED -> OperationStatus.QUEUED
        TransferState.TRANSFERRING, TransferState.VERIFYING, TransferState.READY_TO_PUBLISH,
        TransferState.PUBLISHING -> OperationStatus.RUNNING
        TransferState.RETRY_WAIT -> OperationStatus.FAILED
        TransferState.PUBLISHED -> OperationStatus.SUCCEEDED
        TransferState.RECONCILIATION_REQUIRED -> OperationStatus.RECONCILIATION_REQUIRED
        TransferState.INTERRUPTED -> OperationStatus.INTERRUPTED
        TransferState.FAILED -> OperationStatus.FAILED
        TransferState.CANCELLED -> OperationStatus.CANCELLED
    }
    fun phase(record: TransferRecord): OperationPhase = when (record.state) {
        TransferState.QUEUED -> OperationPhase.PENDING
        TransferState.TRANSFERRING -> OperationPhase.TRANSFERRING
        TransferState.VERIFYING, TransferState.READY_TO_PUBLISH -> OperationPhase.VERIFYING
        TransferState.PUBLISHING -> OperationPhase.READING_BACK
        TransferState.RETRY_WAIT -> OperationPhase.FINISHED
        TransferState.RECONCILIATION_REQUIRED -> OperationPhase.WAITING_FOR_USER
        else -> OperationPhase.FINISHED
    }
    fun progress(record: TransferRecord): Int? = when {
        record.state == TransferState.PUBLISHED -> 100
        record.state in setOf(TransferState.QUEUED, TransferState.CANCELLED) -> null
        else -> (record.request.expectedSize ?: record.representation?.totalBytes)?.let { size ->
            (record.stagedBytes * 100 / size).coerceIn(0, 99).toInt()
        }
    }
}

enum class TransferAdmission { ENQUEUE, EXISTING_OPERATION, DUPLICATE_SOURCE, DESTINATION_CONFLICT, CAPACITY }
object TransferQueuePolicy {
    private val active = setOf(TransferState.QUEUED, TransferState.TRANSFERRING, TransferState.VERIFYING,
        TransferState.READY_TO_PUBLISH, TransferState.PUBLISHING, TransferState.RETRY_WAIT, TransferState.INTERRUPTED, TransferState.RECONCILIATION_REQUIRED)
    fun admission(request: TransferRequest, existing: Collection<TransferRecord>, capacity: Int = 128): TransferAdmission {
        require(capacity in 1..1024)
        if (existing.any { it.request.operationId == request.operationId }) return TransferAdmission.EXISTING_OPERATION
        val live = existing.filter { it.state in active || it.state == TransferState.PUBLISHED }
        if (live.any { it.request.sourceIdentity == request.sourceIdentity && it.request.destinationNamespace == request.destinationNamespace &&
                it.request.fileName.equals(request.fileName, true) && it.request.expectedDigest == request.expectedDigest &&
                it.request.expectedSize == request.expectedSize && it.request.format == request.format }) return TransferAdmission.DUPLICATE_SOURCE
        if (live.any { it.request.destinationNamespace == request.destinationNamespace && it.request.fileName.equals(request.fileName, true) }) return TransferAdmission.DESTINATION_CONFLICT
        if (existing.count { it.state in active } >= capacity) return TransferAdmission.CAPACITY
        return TransferAdmission.ENQUEUE
    }
    fun availableSlots(existing: Collection<TransferRecord>): Int = (2 - existing.count {
        it.state in setOf(TransferState.TRANSFERRING, TransferState.VERIFYING, TransferState.READY_TO_PUBLISH, TransferState.PUBLISHING)
    }).coerceAtLeast(0)
}

data class TransferBatchSummary(val total: Int, val published: Int, val failed: Int, val cancelled: Int, val pending: Int) {
    init { require(total >= 0 && published >= 0 && failed >= 0 && cancelled >= 0 && pending >= 0 && published.toLong() + failed + cancelled + pending == total.toLong()) }
    companion object {
        fun of(records: Collection<TransferRecord>): TransferBatchSummary {
            require(records.map { it.request.operationId }.distinct().size == records.size)
            val p = records.count { it.state == TransferState.PUBLISHED }
            val f = records.count { it.state == TransferState.FAILED }
            val c = records.count { it.state == TransferState.CANCELLED }
            return TransferBatchSummary(records.size, p, f, c, records.size - p - f - c)
        }
    }
}
