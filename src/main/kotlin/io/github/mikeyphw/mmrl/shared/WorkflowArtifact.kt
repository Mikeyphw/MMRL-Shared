package io.github.mikeyphw.mmrl.shared

/** GitHub Actions run provenance identity, not an authorization to install binaries. */
data class GitHubRunIdentity private constructor(
    val repository: GitHubRepository,
    val runId: Long,
    val attempt: Int,
    val commitSha: String,
    val event: String,
    val branch: String,
) {
    fun matchesCurrentRun(
        candidateId: Long,
        candidateAttempt: Int,
        candidateSha: String,
        candidateEvent: String,
        candidateBranch: String,
        status: String,
        conclusion: String,
    ): Boolean = candidateId == runId && candidateAttempt == attempt &&
        candidateSha == commitSha && candidateEvent == event && candidateBranch == branch &&
        status == "completed" && conclusion == "success"

    /** Artifact metadata does not prove the run attempt. Recheck run separately. */
    fun matchesArtifactOwner(ownerRunId: Long, ownerCommitSha: String): Boolean =
        ownerRunId == runId && ownerCommitSha == commitSha

    companion object {
        private val commitPattern = Regex("[0-9a-f]{40}")
        private val branchPattern = Regex("[A-Za-z0-9][A-Za-z0-9_.\\-/]{0,150}")

        fun of(
            repository: GitHubRepository, runId: Long, attempt: Int,
            commitSha: String, event: String, branch: String,
        ): GitHubRunIdentity? {
            if (runId <= 0 || attempt <= 0 || !commitPattern.matches(commitSha) ||
                event !in setOf("push", "pull_request") ||
                !branchPattern.matches(branch) || branch.contains("..") ||
                branch.startsWith('/') || branch.endsWith('/')) return null
            return GitHubRunIdentity(repository, runId, attempt, commitSha, event, branch)
        }
    }
}
