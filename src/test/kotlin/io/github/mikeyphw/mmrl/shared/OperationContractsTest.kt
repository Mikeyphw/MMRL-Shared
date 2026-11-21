package io.github.mikeyphw.mmrl.shared

import org.junit.Assert.*
import org.junit.Test

class OperationContractsTest {
    private val id = OperationId.parse("activity:123-abc")!!
    private val other = OperationId.parse("activity:124-abc")!!

    private fun start(): OperationRecord =
        OperationRecord.create(id, OperationKind.DOWNLOAD, OperationOrigin.USER, 100L)!!

    private fun event(from: OperationStatus, to: OperationStatus, phase: OperationPhase,
                      at: Long, progress: Int? = null,
                      failure: OperationFailure? = null, reviewed: Boolean = false,
                      reboot: Boolean = false, eventId: OperationId = id): OperationEvent =
        OperationEvent.of(eventId, from, to, phase, at, progress, failure, reviewed, reboot)!!

    @Test fun opaqueIdsAndNormalizedFailuresRejectUnsafeInput() {
        assertEquals(id, OperationId.parse(id.value))
        for (bad in listOf("", "../a", "/path", "token=secret", "a b", "https://example.com", "é", "a".repeat(129)))
            assertNull(bad, OperationId.parse(bad))
        assertEquals("NETWORK_ERROR", OperationFailure.parse("NETWORK_ERROR")!!.code)
        for (bad in listOf("", "network_error", "AUTH TOKEN", "ERROR:secret", "A".repeat(65)))
            assertNull(bad, OperationFailure.parse(bad))
        assertNull(OperationEvent.of(id, OperationStatus.QUEUED, OperationStatus.RUNNING,
            OperationPhase.PREPARING, -1))
        assertNull(OperationEvent.of(id, OperationStatus.QUEUED, OperationStatus.RUNNING,
            OperationPhase.PREPARING, 200, 101))
    }

    @Test fun progressAndSuccessfulResultAreMonotonicAndFinal() {
        val queued = start()
        val running = queued.reduce(event(OperationStatus.QUEUED, OperationStatus.RUNNING,
            OperationPhase.TRANSFERRING, 101, 10))!!
        val later = running.reduce(event(OperationStatus.RUNNING, OperationStatus.RUNNING,
            OperationPhase.VERIFYING, 102, 85))!!
        assertNull(later.reduce(event(OperationStatus.RUNNING, OperationStatus.RUNNING,
            OperationPhase.VERIFYING, 103, 20)))
        assertNull(later.reduce(event(OperationStatus.RUNNING, OperationStatus.SUCCEEDED,
            OperationPhase.FINISHED, 99)))
        val complete = later.reduce(event(OperationStatus.RUNNING, OperationStatus.SUCCEEDED,
            OperationPhase.FINISHED, 104))!!
        assertEquals(100, complete.progressPercent)
        assertEquals(OperationResult.SUCCESS, complete.result)
        assertTrue(complete.status.terminal)
        assertNull(complete.reduce(event(OperationStatus.SUCCEEDED, OperationStatus.RUNNING,
            OperationPhase.PREPARING, 105)))
        assertNull(queued.reduce(event(OperationStatus.QUEUED, OperationStatus.RUNNING,
            OperationPhase.PREPARING, 102, eventId = other)))
        assertNull(queued.reduce(event(OperationStatus.RUNNING, OperationStatus.RUNNING,
            OperationPhase.PREPARING, 102)))
    }

    @Test fun reviewAndApprovalDoNotAuthorizeExecution() {
        val review = start().reduce(event(OperationStatus.QUEUED, OperationStatus.NEEDS_REVIEW,
            OperationPhase.WAITING_FOR_USER, 110))!!
        assertTrue(review.status.requiresUserDecision)
        assertNull(review.result)
        assertNull(review.reduce(event(OperationStatus.NEEDS_REVIEW, OperationStatus.SUCCEEDED,
            OperationPhase.FINISHED, 111)))
        val approval = review.reduce(event(OperationStatus.NEEDS_REVIEW, OperationStatus.NEEDS_APPROVAL,
            OperationPhase.WAITING_FOR_USER, 112))!!
        assertNotNull(approval.reduce(event(OperationStatus.NEEDS_APPROVAL,
            OperationStatus.CANCELLED, OperationPhase.FINISHED, 113)))
        assertNull(approval.reduce(event(OperationStatus.NEEDS_APPROVAL,
            OperationStatus.RUNNING, OperationPhase.FINISHED, 113)))
    }

    @Test fun uncertainOutcomeCannotAutoRetryOrResolveWithoutExplicitReview() {
        val running = start().reduce(event(OperationStatus.QUEUED, OperationStatus.RUNNING,
            OperationPhase.APPLYING, 101))!!
        val uncertain = running.reduce(event(OperationStatus.RUNNING,
            OperationStatus.RECONCILIATION_REQUIRED, OperationPhase.WAITING_FOR_USER, 102))!!
        assertTrue(uncertain.status.requiresUserDecision)
        assertNull(uncertain.reduce(event(OperationStatus.RECONCILIATION_REQUIRED,
            OperationStatus.RUNNING, OperationPhase.APPLYING, 103)))
        assertNull(uncertain.reduce(event(OperationStatus.RECONCILIATION_REQUIRED,
            OperationStatus.SUCCEEDED, OperationPhase.FINISHED, 104)))
        val resolved = uncertain.reduce(event(OperationStatus.RECONCILIATION_REQUIRED,
            OperationStatus.INTERRUPTED, OperationPhase.FINISHED, 105, reviewed = true))!!
        assertEquals(OperationResult.INTERRUPTED, resolved.result)
        assertNull(resolved.reduce(event(OperationStatus.INTERRUPTED,
            OperationStatus.RUNNING, OperationPhase.APPLYING, 106)))
    }

    @Test fun failureMustBeNormalizedAndRestoredSnapshotsValidated() {
        val running = start().reduce(event(OperationStatus.QUEUED,
            OperationStatus.RUNNING, OperationPhase.TRANSFERRING, 110, 40))!!
        assertNull(running.reduce(event(OperationStatus.RUNNING, OperationStatus.FAILED,
            OperationPhase.FINISHED, 111)))
        val failed = running.reduce(event(OperationStatus.RUNNING, OperationStatus.FAILED,
            OperationPhase.FINISHED, 112, failure = OperationFailure.parse("NETWORK_ERROR")))!!
        assertEquals("NETWORK_ERROR", failed.failure!!.code)
        assertEquals(OperationResult.FAILURE, failed.result)
        assertEquals(40, failed.progressPercent)
        assertNotNull(OperationRecord.restore(failed.id, failed.kind, failed.origin,
            failed.status, failed.phase, failed.createdAtMillis, failed.updatedAtMillis,
            failed.progressPercent, failed.parentId, failed.failure, failed.rebootRequired))
        assertNull(OperationRecord.restore(id, OperationKind.DOWNLOAD, OperationOrigin.USER,
            OperationStatus.RUNNING, OperationPhase.FINISHED, 1, 2))
        assertNull(OperationRecord.restore(id, OperationKind.DOWNLOAD, OperationOrigin.USER,
            OperationStatus.SUCCEEDED, OperationPhase.FINISHED, 1, 2, 99))
        assertNull(OperationRecord.restore(id, OperationKind.DOWNLOAD, OperationOrigin.USER,
            OperationStatus.QUEUED, OperationPhase.PENDING, 1, 2, schemaVersion = 2))
        assertNull(OperationRecord.create(id, OperationKind.DOWNLOAD, OperationOrigin.USER,
            10, parentId = id))
    }

    @Test fun rebootFlagRequiresSuccessAndRetriesUseLinkedNewIds() {
        val queued = start()
        val running = queued.reduce(event(OperationStatus.QUEUED, OperationStatus.RUNNING,
            OperationPhase.APPLYING, 101))!!
        assertNull(running.reduce(event(OperationStatus.RUNNING, OperationStatus.FAILED,
            OperationPhase.FINISHED, 102, failure = OperationFailure.parse("DENIED"), reboot = true)))
        val complete = running.reduce(event(OperationStatus.RUNNING, OperationStatus.SUCCEEDED,
            OperationPhase.FINISHED, 103, reboot = true))!!
        assertTrue(complete.rebootRequired)
        val child = OperationRecord.create(other, OperationKind.ROLLBACK, OperationOrigin.USER,
            110, parentId = id)!!
        assertEquals(id, child.parentId)
        assertNull(complete.reduce(event(OperationStatus.SUCCEEDED, OperationStatus.RUNNING,
            OperationPhase.APPLYING, 111)))
    }
}
