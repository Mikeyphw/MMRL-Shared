package io.github.mikeyphw.mmrl.shared

/** Platform-neutral Activity contracts. No storage, execution, permission or signing authority. */
enum class OperationKind {
    DOWNLOAD, INSTALL, UPDATE, REMOVE, ENABLE, DISABLE, ACTION, ROLLBACK,
    SCOPE_CHANGE, UPDATE_CHECK, BATCH,
}

enum class OperationOrigin { USER, BACKGROUND, AUTOMATION, RECOVERY }

enum class OperationStatus {
    QUEUED, RUNNING, NEEDS_REVIEW, NEEDS_APPROVAL, RECONCILIATION_REQUIRED,
    SUCCEEDED, FAILED, CANCELLED, INTERRUPTED;

    val terminal: Boolean get() = this in setOf(SUCCEEDED, FAILED, CANCELLED, INTERRUPTED)
    val requiresUserDecision: Boolean get() = this in setOf(NEEDS_REVIEW, NEEDS_APPROVAL, RECONCILIATION_REQUIRED)
}

enum class OperationPhase {
    PENDING, PREPARING, TRANSFERRING, VERIFYING, INSPECTING, APPLYING,
    READING_BACK, WAITING_FOR_USER, FINISHED,
}

enum class OperationResult { SUCCESS, FAILURE, CANCELLED, INTERRUPTED }

/** Opaque app-generated ID. Never embed credentials, URLs, filesystem paths or user data. */
class OperationId private constructor(val value: String) {
    override fun equals(other: Any?): Boolean = other is OperationId && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = value

    companion object {
        private val safe = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        fun parse(value: String): OperationId? =
            if (safe.matches(value) && !value.contains("..")) OperationId(value) else null
    }
}

/** Stable machine-readable error code, deliberately not a free-form log or secret-bearing message. */
class OperationFailure private constructor(val code: String) {
    override fun equals(other: Any?): Boolean = other is OperationFailure && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = code

    companion object {
        private val safe = Regex("[A-Z][A-Z0-9_]{0,63}")
        fun parse(code: String): OperationFailure? = if (safe.matches(code)) OperationFailure(code) else null
    }
}

/** One immutable, source-identified operation-state proposal. Does not perform an action. */
class OperationEvent private constructor(
    val operationId: OperationId,
    val expectedStatus: OperationStatus,
    val nextStatus: OperationStatus,
    val phase: OperationPhase,
    val atMillis: Long,
    val progressPercent: Int?,
    val failure: OperationFailure?,
    val reconciliationReviewed: Boolean,
    val rebootRequired: Boolean,
) {
    companion object {
        fun of(
            operationId: OperationId,
            expectedStatus: OperationStatus,
            nextStatus: OperationStatus,
            phase: OperationPhase,
            atMillis: Long,
            progressPercent: Int? = null,
            failure: OperationFailure? = null,
            reconciliationReviewed: Boolean = false,
            rebootRequired: Boolean = false,
        ): OperationEvent? {
            if (atMillis < 0 || progressPercent != null && progressPercent !in 0..100) return null
            return OperationEvent(operationId, expectedStatus, nextStatus, phase, atMillis,
                progressPercent, failure, reconciliationReviewed, rebootRequired)
        }
    }
}

/** Immutable reducer snapshot. Android consumers own persistence and authorization. */
class OperationRecord private constructor(
    val id: OperationId,
    val kind: OperationKind,
    val origin: OperationOrigin,
    val status: OperationStatus,
    val phase: OperationPhase,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val progressPercent: Int?,
    val parentId: OperationId?,
    val failure: OperationFailure?,
    val rebootRequired: Boolean,
) {
    val schemaVersion: Int get() = SCHEMA_VERSION
    val result: OperationResult? get() = when (status) {
        OperationStatus.SUCCEEDED -> OperationResult.SUCCESS
        OperationStatus.FAILED -> OperationResult.FAILURE
        OperationStatus.CANCELLED -> OperationResult.CANCELLED
        OperationStatus.INTERRUPTED -> OperationResult.INTERRUPTED
        else -> null
    }

    /** Reject stale, impossible or unauthorized-by-state transitions without mutating the source. */
    fun reduce(event: OperationEvent): OperationRecord? {
        if (event.operationId != id || event.expectedStatus != status || status.terminal ||
            event.atMillis < updatedAtMillis) return null
        val allowed = when (status) {
            OperationStatus.QUEUED -> event.nextStatus in setOf(
                OperationStatus.RUNNING, OperationStatus.NEEDS_REVIEW, OperationStatus.CANCELLED,
                OperationStatus.FAILED, OperationStatus.INTERRUPTED)
            OperationStatus.RUNNING -> event.nextStatus in setOf(
                OperationStatus.RUNNING, OperationStatus.NEEDS_REVIEW, OperationStatus.NEEDS_APPROVAL,
                OperationStatus.RECONCILIATION_REQUIRED, OperationStatus.SUCCEEDED,
                OperationStatus.FAILED, OperationStatus.CANCELLED, OperationStatus.INTERRUPTED)
            OperationStatus.NEEDS_REVIEW -> event.nextStatus in setOf(
                OperationStatus.RUNNING, OperationStatus.NEEDS_APPROVAL,
                OperationStatus.CANCELLED, OperationStatus.FAILED, OperationStatus.INTERRUPTED)
            OperationStatus.NEEDS_APPROVAL -> event.nextStatus in setOf(
                OperationStatus.RUNNING, OperationStatus.CANCELLED,
                OperationStatus.FAILED, OperationStatus.INTERRUPTED)
            OperationStatus.RECONCILIATION_REQUIRED -> event.reconciliationReviewed &&
                event.nextStatus in setOf(OperationStatus.SUCCEEDED, OperationStatus.FAILED,
                    OperationStatus.INTERRUPTED)
            else -> false
        }
        if (!allowed || (event.reconciliationReviewed && status != OperationStatus.RECONCILIATION_REQUIRED)) return null
        val nextProgress = if (event.nextStatus == OperationStatus.SUCCEEDED) 100
            else event.progressPercent ?: progressPercent
        if (progressPercent != null && nextProgress != null && nextProgress < progressPercent) return null
        return restore(id, kind, origin, event.nextStatus, event.phase, createdAtMillis,
            event.atMillis, nextProgress, parentId, event.failure, event.rebootRequired)
    }

    companion object {
        const val SCHEMA_VERSION: Int = 1

        fun create(id: OperationId, kind: OperationKind, origin: OperationOrigin,
                   atMillis: Long, parentId: OperationId? = null): OperationRecord? =
            restore(id, kind, origin, OperationStatus.QUEUED, OperationPhase.PENDING,
                atMillis, atMillis, null, parentId, null, false)

        /** Rehydrate stored records only after validating schema, state, and timing. */
        fun restore(
            id: OperationId, kind: OperationKind, origin: OperationOrigin,
            status: OperationStatus, phase: OperationPhase,
            createdAtMillis: Long, updatedAtMillis: Long,
            progressPercent: Int? = null,
            parentId: OperationId? = null,
            failure: OperationFailure? = null,
            rebootRequired: Boolean = false,
            schemaVersion: Int = SCHEMA_VERSION,
        ): OperationRecord? {
            if (schemaVersion != SCHEMA_VERSION || createdAtMillis < 0 ||
                updatedAtMillis < createdAtMillis || parentId == id ||
                (progressPercent != null && progressPercent !in 0..100) ||
                ((status == OperationStatus.FAILED) != (failure != null)) ||
                (rebootRequired && status != OperationStatus.SUCCEEDED)) return null
            val phaseAllowed = when (status) {
                OperationStatus.QUEUED -> phase == OperationPhase.PENDING && progressPercent == null
                OperationStatus.RUNNING -> phase in setOf(OperationPhase.PREPARING,
                    OperationPhase.TRANSFERRING, OperationPhase.VERIFYING,
                    OperationPhase.INSPECTING, OperationPhase.APPLYING, OperationPhase.READING_BACK)
                OperationStatus.NEEDS_REVIEW, OperationStatus.NEEDS_APPROVAL,
                OperationStatus.RECONCILIATION_REQUIRED -> phase == OperationPhase.WAITING_FOR_USER
                else -> phase == OperationPhase.FINISHED
            }
            if (!phaseAllowed || (status == OperationStatus.SUCCEEDED && progressPercent != 100)) return null
            return OperationRecord(id, kind, origin, status, phase, createdAtMillis,
                updatedAtMillis, progressPercent, parentId, failure, rebootRequired)
        }
    }
}
