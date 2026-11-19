# N05F: Source-bound shared Kotlin dependency propagation

N05F is a PR-only Gitlink + exact source-contract SHA updater for
`Mikeyphw/MMRL:next` and `Mikeyphw/MMRL-LSPosed:main`. It is invoked by
`workflow_run` after a successful N05A **push** to `MMRL-Shared:main`. Fork PRs,
unsuccessful or manual N05A runs, non-current source SHAs, downgrade attempts,
branch takeover, and changed repository identity fail closed.

## Idempotent source-revision lifecycle

For each validated source commit `SHA`, choose
`feat/n05f-shared-sync-SHA` using all 40 lowercase hex digits. Each new
revision receives a new automation branch and PR, without rewriting previous
branches or reopening historical merged/closed PRs.

Inspect each consumer base Git tree and the exact Python source-contract fixture
first. If the base Gitlink already pins `SHA`, report `unchanged` even when a
historical N05F PR is closed. If it differs, verify the previous pin is an
ancestor of `SHA` in Shared; inspect only the SHA-specific automation branch.

Reuse an already-open, non-draft PR only with exact branch/head identity and
allowed two-file source inventory. Refuse a closed or draft PR for the current
unmerged source, a colliding branch, a wrong branch commit marker, moved base,
non-fast-forward, or unauthorized repository access. Recovery from a partially
pushed branch can create a missing PR only when its history is vetted. Never
force push, rebase, reset, delete branches, merge a PR, build Android locally,
sign or release artifacts.

The consumer repository identities are intentionally pinned: the recreated
`MMRL` repository ID is `1463338730` and the LSPosed repository ID is
`1444635884`. If either is recreated again, stop and audit its permissions,
branches, ruleset, PR history, and token scope before changing its identity pin.

The cross-repository secret `MMRL_FEDERATION_TOKEN` must have **Contents** and
**Pull requests** write permission only on both current consumer repositories.
The GitHub Actions `github.token` independently reads Shared. The credential
value is never included in receipts or diagnostics. A successful repo read does
not prove all future write operations will be authorized; the next real hosted
PR creation is the end-to-end permission gate.

## Test and qualification

Run the current executable regression contract:

```sh
python3 -B -m unittest discover -s scripts/tests -p 'test_n05f.py' -v
```

Tests exercise SHA branch identity, repository recreation refusal, credential
separation, validated N05A origin, real Gitlink writes into an isolated bare
Git repository, safe PR reuse, already-merged idempotence, stale/foreign branch
refusal, no writes during `plan`, no auto-merges and workflow constraints.

N05A is the only required Shared PR workflow and is recorded in the committed
`config/delivery-required-ci.json`. GitHub workflow ID `409351241` was observed
at N05A run `42271467062` for Shared commit
`56299faf7f17851485a607b97ca2f0eec3bb2dca`.

PR checks qualify only the exact PR HEAD on GitHub. After merge, verify the
new Shared HEAD passed N05A push, N05F created separate consumer PRs, and each
consumer's branch ruleset enforces the applicable hosted Android/Devtool
checks. Skipped tests are not passes. Native local tests do not replace hosted
Android, device, release or downstream consumer acceptance.

The full N05F automation is intentionally neither triggerable by
`workflow_dispatch` nor a cross-repository auto-merge mechanism. Upstream MMRL
source synchronization remains independent of this shared-core pin propagation.

## N05F gated by the same hosted N05A workflow

The existing N05A PR and `main` push workflow **also executes** the N05F
lifecycle/negative regression suite before Gradle and Maven staging. The
source-validation code requires the exact `N05A` workflow ID `409351241`,
full Shared repository identity, push event, current HEAD, and successful
completion. A successful similarly named workflow from another identity
must not qualify a source update. The native Devtool overlay validation
runs the same current test suite locally, but does not replace the hosted
workflow gate. Preserve this shared test discovery in future CI changes.

## Hosted regression harness isolation

N05A runs the same `test_n05f.py` suite on pull requests and trusted pushes.
The negative HTTP-403/redaction test supplies a **mock** `N05F_READ_TOKEN` in
a fully isolated environment, so hosted `GITHUB_ACTIONS=true` does not mask
that code path with the earlier missing-token refusal. A separate security test
still verifies the missing read token fails closed without falling back to the
consumer write credential. No actual Actions secret is required by N05A tests.
