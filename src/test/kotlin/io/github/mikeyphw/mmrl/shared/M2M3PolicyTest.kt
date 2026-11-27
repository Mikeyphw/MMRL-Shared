package io.github.mikeyphw.mmrl.shared

import org.junit.Test

class M2M3PolicyTest {
    @Test fun transferIntegrity() = TransferPolicyCases.integrity()
    @Test fun transferLifecycle() = TransferPolicyCases.lifecycle()
    @Test fun transferResumeAndRetry() = TransferPolicyCases.resumeAndRetry()
    @Test fun durableTransferQueue() = TransferPolicyCases.durableQueue()
    @Test fun githubRepositoryAndRelease() = RepositoryPolicyCases.repositoryAndRelease()
    @Test fun githubActionsAndRedirects() = RepositoryPolicyCases.actionsAndRedirects()
    @Test fun githubRankingAndCache() = RepositoryPolicyCases.rankAndCache()
}
