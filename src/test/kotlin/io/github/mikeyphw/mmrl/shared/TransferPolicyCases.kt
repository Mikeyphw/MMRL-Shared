package io.github.mikeyphw.mmrl.shared

/** Shared production policy tests, also executable without Gradle using the host compiler. */
object TransferPolicyCases {
    private fun id(value: String) = OperationId.parse(value)!!
    private val digest = Sha256Digest.calculate("complete".encodeToByteArray())
    private val request = TransferRequest(id("download-1"), id("source-1"), id("dest"), "module.zip", TransferFormat.ROOT_ZIP, 8, digest, 64)
    private val actual = TransferObservation(8, digest, true)
    private val retry = TransferRetryPolicy()
    private val identity = RepresentationIdentity.fromStrongEtag(request.sourceIdentity, "\"strong\"", 8)!!
    private fun partial() = TransferRecord.enqueue(request).start(0, 0, retry)!!.progress(1, 3, identity)!!
    private fun ready(): TransferRecord {
        val complete = partial().progress(2, 8, identity)!!
        return complete.finishTransfer(3)!!.verify(4, actual)!!
    }
    fun integrity() {
        check(VerifiedTransfer.of(request, actual) != null)
        check(TransferIntegrity.observe(request, "complete".byteInputStream(), true) == actual)
        check(TransferIntegrity.failure(request, TransferIntegrity.observe(request, "partial".byteInputStream(), true)) == TransferFailure.LENGTH_MISMATCH)
        check(runCatching { TransferIntegrity.observe(request, ByteArray(65).inputStream(), true) }.isFailure)
        check(TransferIntegrity.failure(request, actual.copy(bytes = 0)) == TransferFailure.EMPTY)
        check(TransferIntegrity.failure(request, actual.copy(bytes = 65)) == TransferFailure.TOO_LARGE)
        check(TransferIntegrity.failure(request, actual.copy(bytes = 7)) == TransferFailure.LENGTH_MISMATCH)
        check(TransferIntegrity.failure(request.copy(expectedDigest = null), actual) == TransferFailure.CHECKSUM_MISSING)
        check(TransferIntegrity.failure(request, actual.copy(digest = Sha256Digest.calculate("changed".encodeToByteArray()))) == TransferFailure.CHECKSUM_MISMATCH)
        check(TransferIntegrity.failure(request, actual.copy(formatConfirmed = false)) == TransferFailure.FORMAT_INVALID)
        check(runCatching { request.copy(fileName = "../module.zip") }.isFailure)
        check(runCatching { request.copy(fileName = "module.apk") }.isFailure)
        check(runCatching { request.copy(maxBytes = Long.MAX_VALUE) }.isFailure)
    }
    fun lifecycle() {
        val queue = TransferRecord.enqueue(request)
        check(queue.start(1, 0, retry) == null)
        check(queue.finishTransfer(0) == null)
        val p = partial()
        check(p.finishTransfer(2) == null)
        check(p.progress(2, 2, identity) == null)
        check(p.progress(2, 9, identity) == null)
        check(p.progress(2, 4, null) == null)
        check(p.cancel(1) == null)
        check(p.cancel(2)!!.state == TransferState.CANCELLED)
        check(TransferActivityProjection.progress(p.cancel(2)!!) == null) // retain prior Activity progress
        val stage = ready()
        check(stage.state == TransferState.READY_TO_PUBLISH && stage.receipt == null)
        val publishing = stage.beginPublication(stage.revision)!!
        check(publishing.cancel(publishing.revision) == null)
        check(publishing.fail(publishing.revision, TransferFailure.DISK_FULL, 0, retry) == null)
        val uncertain = publishing.recover(publishing.revision)!!
        check(uncertain.state == TransferState.RECONCILIATION_REQUIRED && uncertain.receipt == null)
        check(TransferActivityProjection.status(uncertain) == OperationStatus.RECONCILIATION_REQUIRED)
        check(TransferActivityProjection.progress(uncertain) == 99)
        check(uncertain.start(uncertain.revision, 0, retry) == null)
        check(uncertain.publicationAbsent(uncertain.revision, false) == null)
        val refused = uncertain.confirmPublication(uncertain.revision, actual.copy(bytes = 7), 10)!!
        check(refused.state == TransferState.RECONCILIATION_REQUIRED && refused.receipt == null)
        val done = refused.confirmPublication(refused.revision, actual, 11)!!
        check(done.state == TransferState.PUBLISHED && done.receipt?.observation == actual)
        check(TransferActivityProjection.status(done) == OperationStatus.SUCCEEDED && TransferActivityProjection.progress(done) == 100)
        check(done.cancel(done.revision) == null && done.recover(done.revision) == null)
        check(uncertain.publicationAbsent(uncertain.revision, true)!!.failure == TransferFailure.STORAGE_FAILURE)
        val verified = stage.verified!!
        check(TransferPublicationPolicy.decide(verified, true, null, true) == PublicationDecision.PUBLISH_NO_REPLACE)
        check(TransferPublicationPolicy.decide(verified, false, actual, true) == PublicationDecision.REUSE_VERIFIED)
        check(TransferPublicationPolicy.decide(verified, false, actual.copy(bytes = 7), true) == PublicationDecision.REFUSE_EXISTING)
        check(TransferPublicationPolicy.decide(verified, false, actual, false) == PublicationDecision.REFUSE_STORAGE)
    }
    fun resumeAndRetry() {
        val response = ResumeResponse(206, identity, 3, 7, 5, true)
        check(TransferResumePolicy.decide(request, identity, 3, response) == ResumeDecision.APPEND)
        for (bad in listOf(response.copy(rangeStart = 2), response.copy(rangeEndInclusive = 6), response.copy(contentLength = 4), response.copy(identityEncoding = false), response.copy(identity = null), response.copy(status = 200), response.copy(status = 416))) {
            check(TransferResumePolicy.decide(request, identity, 3, bad) == ResumeDecision.RESTART)
        }
        check(TransferResumePolicy.decide(request, identity, 3, response.copy(status = 401)) == ResumeDecision.REFUSE)
        check(RepresentationIdentity.fromStrongEtag(request.sourceIdentity, "W/\"weak\"", 8) == null)
        check(RepresentationIdentity.fromStrongEtag(request.sourceIdentity, "\"bad\nvalue\"", 8) == null)
        val p = partial(); val interrupted = p.recover(p.revision)!!
        check(interrupted.state == TransferState.INTERRUPTED)
        check(interrupted.start(interrupted.revision, 0, retry)!!.stagedBytes == 0L)
        val proof = ResumeProof.fromResponse(request, identity, 3, response)!!
        val resumed = interrupted.start(interrupted.revision, 0, retry, proof)!!
        check(resumed.stagedBytes == 3L && resumed.attempt == 2)
        val wrong = ResumeProof.fromResponse(request.copy(operationId = id("another")), identity, 3, response)!!
        check(interrupted.start(interrupted.revision, 0, retry, wrong) == null)
        val failed = p.fail(p.revision, TransferFailure.TIMEOUT, 10, retry)!!
        check(failed.state == TransferState.RETRY_WAIT && failed.retryAtMillis == 1010L)
        check(TransferActivityProjection.status(failed) == OperationStatus.FAILED)
        check(TransferActivityProjection.phase(failed) == OperationPhase.FINISHED)
        check(failed.start(failed.revision, 1009, retry) == null)
        check(failed.start(failed.revision, 1010, retry) != null)
        check(p.fail(p.revision, TransferFailure.DISK_FULL, 10, retry)!!.state == TransferState.FAILED)
        check(retry.delayMillis(3, TransferFailure.TIMEOUT) == null)
        check(retry.delayMillis(2, TransferFailure.TIMEOUT) == 2000L)
        check(retry.delayMillis(1, TransferFailure.RATE_LIMIT, 4000) == 4000L)
        check(retry.delayMillis(1, TransferFailure.RATE_LIMIT, Long.MAX_VALUE) == null)
        check(p.fail(p.revision, TransferFailure.TIMEOUT, Long.MAX_VALUE, retry)!!.state == TransferState.FAILED)
        check(TransferFailurePolicy.http(429) == TransferFailure.RATE_LIMIT)
        check(TransferFailurePolicy.http(503) == TransferFailure.HTTP_TRANSIENT)
        check(TransferFailurePolicy.http(401) == TransferFailure.HTTP_PERMANENT)
    }
    fun durableQueue() {
        val p = partial()
        check(TransferRecord.restore(request, p.state, p.revision, p.attempt, p.stagedBytes, p.representation, p.verified, p.receipt, p.failure, p.retryAtMillis) != null)
        check(TransferRecord.restore(request, TransferState.PUBLISHED, 4, 1, 8, identity, null, null, null, null) == null)
        check(TransferRecord.restore(request, TransferState.VERIFYING, 4, 1, 3, identity, null, null, null, null) == null)
        check(TransferRecord.restore(request, TransferState.RETRY_WAIT, 4, 1, 3, identity, null, null, TransferFailure.CHECKSUM_MISMATCH, 10) == null)
        check(TransferQueuePolicy.admission(request, listOf(p)) == TransferAdmission.EXISTING_OPERATION)
        check(TransferQueuePolicy.admission(request.copy(operationId = id("other")), listOf(p)) == TransferAdmission.DUPLICATE_SOURCE)
        check(TransferQueuePolicy.admission(request.copy(operationId = id("other"), sourceIdentity = id("different")), listOf(p)) == TransferAdmission.DESTINATION_CONFLICT)
        check(TransferQueuePolicy.admission(request.copy(operationId = id("other"), fileName = "different.zip"), listOf(p), 1) == TransferAdmission.CAPACITY)
        val other = TransferRecord.enqueue(request.copy(operationId = id("other"), fileName = "different.zip"))
        check(TransferQueuePolicy.availableSlots(listOf(p, other)) == 1)
        val cancelled = other.cancel(0)!!
        val aggregate = TransferBatchSummary.of(listOf(p, cancelled))
        check(aggregate.total == 2 && aggregate.pending == 1 && aggregate.cancelled == 1)
        check(runCatching { TransferBatchSummary.of(listOf(p, p)) }.isFailure)
    }
    @JvmStatic fun main(args: Array<String>) {
        integrity(); println("PASS transfer integrity and path constraints")
        lifecycle(); println("PASS transfer lifecycle and publication reconciliation")
        resumeAndRetry(); println("PASS resume and bounded retry")
        durableQueue(); println("PASS durable state and queue policies")
    }
}
