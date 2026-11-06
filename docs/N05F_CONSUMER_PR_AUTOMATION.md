# N05F — automated shared Kotlin consumer updates

## Verified architecture

An N05F hosted run first executes its own 11 offline safety and Git plumbing tests, then plans the source-exact changes. A successful **push** run of **N05A Shared Kotlin JVM contracts and staged Maven** on `Mikeyphw/MMRL-Shared:main` starts the N05F GitHub Actions workflow. It checks that the validated SHA still matches the **current** shared `main` head, then checks the consumer gitlink pins. It opens or updates non-draft pull requests against:

- `Mikeyphw/MMRL:next` via `feat/n05f-shared-sync`;
- `Mikeyphw/MMRL-LSPosed:main` via `feat/n05f-shared-sync`.

The update is limited to the `third_party/mmrl-shared` gitlink **and the existing exact-SHA Python source-contract fixture** (MMRL N05C or LSPosed N05B); without updating that fixture, the hosted source checks would reject the new pin. The source must have passed hosted N05A JVM CI, and its commit must descend from both the existing consumer base and feature-branch pins. A shared downgrade, untrusted PR branch, manual edits to the automation branch, missing source run, or race in remote refs causes refusal. The workflow never merges, touches release workflows, performs local Android/Gradle builds, or accesses devices. Both consumers already run Devtool Android workflows on pull requests targeting their respective branches.

### Mandatory GitHub credential

A token for the *two consumer repositories* is required because the default `GITHUB_TOKEN` of `MMRL-Shared` has no write permission to other repositories. Provision one as the `MMRL_FEDERATION_TOKEN` Actions secret in `Mikeyphw/MMRL-Shared`. Use a short-lived fine-grained PAT or GitHub App installation token with **Contents: Read and write** and **Pull requests: Read and write** on **only** `Mikeyphw/MMRL` and `Mikeyphw/MMRL-LSPosed`. No permission to write `MMRL-Shared` or release workflows is necessary. If fine-grained PAT creation needs a repository selection containing both apps, select only those two. Token never appears in ZIP, git remotes, logs, or source.

Configure the secret before pushing the new shared workflow:

```zsh
# Prompts for the secret value without putting it in shell history.
gh secret set MMRL_FEDERATION_TOKEN -R Mikeyphw/MMRL-Shared
```

The installer does **not** create or export tokens and does **not** push automatically. After setting the secret, push the committed `MMRL-Shared:main` using `git -C "$HOME/Code/MMRL-Shared" push origin main`. GitHub will execute N05A on this new commit; on its success, N05F opens consumer PRs.

Manual dry run (read-only; first push and N05A success must already exist):

```zsh
python3 "$HOME/Code/MMRL-Shared/scripts/n05f_propagate.py" plan --sha "$(git -C "$HOME/Code/MMRL-Shared" rev-parse HEAD)"
```

### Interaction with N05E source locks

N05E's `validate-both` lock freezes **currently merged** consumer heads. On an updated shared `main`, N05E's previous all-source-pinned predicate intentionally fails until both consumer PRs merge and local checkouts are safely fast-forwarded and their submodules are updated. **Do not reset, force-push, or silently re-freeze a lock to hide dependency drift.** Once both PRs are green and merged, run the existing read-only source verification, then explicitly freeze a new `validate-both` source lock and run hosted reruns as appropriate. N05F opens PRs only: it does not merge them.

### Failure recovery

If the first consumer branch is pushed and PR creation fails, a later run can recover a feature branch with no PR **only when** its history contains no changes beyond the shared gitlink and its pinned-test fixture and the latest commit belongs to N05F. If an existing PR was closed, or a branch was edited by a human, the automation refuses further writes until manually reconciled. If the shared `main` moves after a workflow event, its older run refuses writes.
