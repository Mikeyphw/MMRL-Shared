# M0 baseline and M1-A platform-neutral Activity contracts

**Status:** M0 inventory and M1-A Shared source candidate. Neither Android consumer is changed by this PR; M1 Android persistence and UI acceptance are **not complete**.

## Immutable baseline (verified November 21, 2026)

| Repository / branch | Audited head | Existing pin / boundary |
| --- | --- | --- |
| `Mikeyphw/MMRL-Shared:main` | `1bd273c7c386b4116144db5c36659ead47cd299a` | Pure Kotlin/JVM. No Android or network execution. |
| `Mikeyphw/MMRL:next` | `0750675f3ff347c7487e25bf9f155dbc023769ea` | Shared Git submodule pinned at the same Shared head. Upstream-sync/release guards retained. |
| `Mikeyphw/MMRL-LSPosed:main` | `a63961f35a812dec9bfe6c6954cb0525d2f4ce8a` | Shared Git submodule pinned at the same Shared head. Vector write journal and signed-debug/release boundaries retained. |
| `Mikeyphw/MMRL:master` | Reference only | Do not merge `master` into `next`; selectively adapt features. |

Heads are historical source baselines, not a promise of current branch freshness. Consumers must re-check their exact pins and hosted CI before consuming this PR.

## Six-feature gap / ownership matrix

| Capability | MMRL `next` baseline | MMRL-LSPosed `main` baseline | Owner of privileged execution |
| --- | --- | --- | --- |
| Installation review | Existing root ZIP install flow; `master` review implementation is not ported | APK viewer handoff and first-party receipts, no general third-party APK install review | Root backend in MMRL; Android PackageInstaller in LSPosed |
| Activity history | No `master`-equivalent durable operation timeline | Existing verified download receipts and Vector write journal, not a unified timeline | Separate app-owned Room/database, history service, UI |
| Reliable downloads | Basic `DownloadService` with progress | Verified first-party downloads with private staging, SHA-256 receipts and cancellation | App-owned transport/storage/services |
| GitHub module sources | Root repositories, no port of `master` configurable Releases/Actions resolver | Discover and first-party evidence; not general third-party APK module sources | App-owned GitHub HTTP/auth clients; shared identity policy |
| Batch operations | `BulkInstallViewModel` exists, basic batch UX | No general APK module batch review/download | Per-app serialized install authority |
| Background update checks | Existing `ModuleService`/interval settings | Cached version comparison, no scheduled module polling | App-owned WorkManager/service and notifications |

## Current Shared API: M1-A

Additive pure Kotlin types in `OperationContracts.kt`:

- `OperationId`: safe, opaque app-generated identifier. No URLs, paths, tokens or private data.
- `OperationKind`, `OperationOrigin`, `OperationStatus`, `OperationPhase`, `OperationResult`: stable enums for Activity.
- `OperationFailure`: normalized uppercase machine code, not an unbounded error log.
- `OperationEvent`: validated proposal with optimistic expected status and monotonic timestamp.
- `OperationRecord`: immutable snapshot, `SCHEMA_VERSION=1`, strict `create`/`restore`, and fail-closed `reduce`.

Transitions are pure. Terminal states cannot reopen; retries/rollbacks must create new linked operation records. Progress never decreases. A `RECONCILIATION_REQUIRED` operation cannot resume or reach a terminal state without an explicitly reviewed reconciliation event. **The Boolean records a decision, but is not proof of a real device read-back; the app must enforce that authorization and journal integrity independently.** Review/approval statuses never authorize installation themselves.

The Shared core does **not** log, persist, enqueue, retry, install, communicate over a network, open a root shell, access Vector, or verify an APK publisher. `OperationFailure` codes must be selected by an app-maintained allowlist; apps must redact technical logs and avoid copying secrets into IDs or metadata.

## Security / regression boundaries

1. Keep `MMRL:next` upstream sync policy, official release guard, exact-source Devtool Android workflows, and existing root backend unchanged.
2. Keep `MMRL-LSPosed:main` VectorRootTransport/VectorMutation/VectorWriteJournal unchanged. Existing unknown write outcomes remain blocked until device-specific reconciliation.
3. Keep LSPosed signed-debug evidence, production certificate enrollment, and release-admission policies separate.
4. `MMRL-Shared` remains pure Kotlin/JVM: no Android/Compose/Room/WorkManager, credentials, network client, filesystem mutations, root/Vector code, or installer semantics.
5. No automatic approval, installation, module enabling, scope writes, publication, or consumer PR merge.
6. Run Shared JUnit/Gradle tests on hosted N05A GitHub Actions. Local standalone compilation is supplementary only.

## Follow-on PR sequence (not part of this candidate)

1. Merge this Shared API only after N05A hosted source, regression, JVM tests and staging publication checks pass.
2. Allow the existing **N05F PR-only** mechanism to propose exact Git submodule pins to each consumer; review, build, and merge pins separately. Never assume N05F automatically merges or validates device behavior.
3. `MMRL:next` M1-B: Room migration, read-only Activity screen, bounded redacted logs, existing download integration and restart recovery.
4. `MMRL-LSPosed:main` M1-C: independent Room migration/read-only Activity, verified download receipts and **read-only** Vector journal projections, no Vector mutation modification.
5. Require native hosted Android build/JVM/lint/API 36 UI checks and physical-device acceptance for each consumer. Do not mark M1 complete before both applications prove restart survival and sanitized failures.

## Acceptance status

| Gate | State |
| --- | --- |
| Source baseline and owner inventory | DOCUMENTED for review |
| Pure Kotlin compilation and focused reducer tests | LOCAL check only; GitHub N05A pending |
| Hosted N05A source/JUnit/staging | NOT YET RUN for this PR |
| Consumer shared pin PRs | NOT STARTED |
| MMRL Activity persistence / UI / device tests | NOT STARTED |
| LSPosed Activity persistence / UI / device tests | NOT STARTED |
| Production signing / release / privileged device writes | NOT PERFORMED |
