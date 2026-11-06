#!/usr/bin/env python3
"""N05F: PR-only, fail-closed propagation of the audited MMRL-Shared gitlink.

This is deliberately independent of Gradle, Android, local root and releases.
A trusted workflow_run after successful shared JVM validation invokes this.
"""
from __future__ import annotations
import argparse
from dataclasses import dataclass
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from typing import Any

SHA = re.compile(r'^[0-9a-f]{40}$')
SOURCE = 'Mikeyphw/MMRL-Shared'
BRANCH = 'feat/n05f-shared-sync'
GITLINK = 'third_party/mmrl-shared'
COMMIT_PREFIX = 'chore(n05f): pin MMRL-Shared '

@dataclass(frozen=True)
class Consumer:
    repo: str
    base: str
    slug: str
    pin_fixture: str

CONSUMERS = (
    Consumer('Mikeyphw/MMRL', 'next', 'mmrl-next', 'scripts/test_n05c_shared_source.py'),
    Consumer('Mikeyphw/MMRL-LSPosed', 'main', 'mmrl-lsposed', 'scripts/tests/test_n05b_shared_contracts.py'),
)

class Refusal(Exception):
    pass


def run(args: list[str], *, cwd: Path | None = None, stdin: str | None = None,
        allow_404: bool = False, timeout: int = 120,
        auth: str = 'consumer') -> str | None:
    env = os.environ.copy()
    env['GIT_TERMINAL_PROMPT'] = '0'
    env['GIT_CONFIG_NOSYSTEM'] = '1'
    if auth == 'shared':
        # The cross-repository PAT is intentionally scoped ONLY to consumers.
        # Read MMRL-Shared through its own short-lived GitHub Actions token.
        read_token = os.environ.get('N05F_READ_TOKEN', '')
        if not read_token and os.environ.get('GITHUB_ACTIONS') == 'true':
            raise Refusal('N05F shared-source read token is missing; refusing consumer-token fallback')
        if read_token:
            env['GH_TOKEN'] = read_token
            env['GITHUB_TOKEN'] = read_token
    elif auth != 'consumer':
        raise Refusal('Unsupported GitHub credential route')
    # No network commands are permitted to log secrets or their raw errors.
    try:
        p = subprocess.run(args, input=stdin, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                           text=True, cwd=cwd, timeout=timeout, env=env)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise Refusal(f'{args[0]} unavailable or timed out') from exc
    if p.returncode:
        if allow_404 and ('HTTP 404' in p.stderr or 'Not Found' in p.stderr):
            return None
        raise Refusal(f'{args[0]} operation failed (exit={p.returncode})')
    return p.stdout.strip()


def gh(path: str, *, method: str = 'GET', payload: dict | None = None,
       allow_404: bool = False) -> Any:
    args = ['gh','api','-H','Accept: application/vnd.github+json','--method',method,path]
    shared_read = path.startswith(f'repos/{SOURCE}/')
    if shared_read and (method != 'GET' or payload is not None):
        raise Refusal('Shared-source credential is read-only')
    raw = run(args + (['--input','-'] if payload is not None else []),
              stdin=json.dumps(payload) if payload is not None else None,
              allow_404=allow_404,
              auth='shared' if shared_read else 'consumer')
    if raw is None:
        return None
    try:
        return json.loads(raw)
    except ValueError as exc:
        raise Refusal('GitHub returned invalid JSON') from exc


def git(path: Path, *args: str) -> str:
    # Using a GitHub CLI credential helper avoids putting credentials in URLs.
    return str(run(['git','-c','credential.helper=!gh auth git-credential','-C',str(path),*args],timeout=200))


def check_sha(value: str) -> str:
    if not SHA.fullmatch(value):
        raise Refusal('N05F requires one exact 40-character lowercase source SHA')
    return value


def ref(repo: str, branch: str, *, missing_ok: bool = False) -> str | None:
    data = gh(f'repos/{repo}/git/ref/heads/{branch}', allow_404=missing_ok)
    if data is None:
        return None
    sha = (data.get('object') or {}).get('sha','')
    return check_sha(sha)


def latest_validated_source(sha: str) -> None:
    if ref(SOURCE,'main') != sha:
        raise Refusal('Shared main advanced or source SHA is not current; stale dispatch refused')
    # The hosted trigger is a successful N05A push run. This check also applies
    # to local/manual use: verify exact commit has a successful hosted push run.
    runs = gh(f'repos/{SOURCE}/actions/runs?branch=main&per_page=100')
    if not any(r.get('head_sha') == sha and r.get('name') ==
               'N05A Shared Kotlin JVM contracts and staged Maven' and
               r.get('event') == 'push' and r.get('conclusion') == 'success' and
               r.get('status') == 'completed'
               for r in runs.get('workflow_runs',[])):
        raise Refusal('Shared SHA has no completed successful N05A push validation')


def upstream_fast_forward(old: str, new: str) -> None:
    if old == new:
        return
    result = gh(f'repos/{SOURCE}/compare/{old}...{new}')
    if result.get('status') != 'ahead' or result.get('ahead_by',0) <= 0:
        raise Refusal('Non-fast-forward or unverified shared pin update refused')


def get_prs(c: Consumer) -> list[dict]:
    prs = gh(f'repos/{c.repo}/pulls?state=all&head=Mikeyphw:{BRANCH}&base={c.base}&per_page=100')
    if not isinstance(prs, list):
        raise Refusal('GitHub PR query returned unexpected data')
    return [p for p in prs if (p.get('head') or {}).get('ref')==BRANCH and
            (p.get('base') or {}).get('ref')==c.base]


def tree_pin(path: Path, rev: str) -> str:
    line = git(path,'ls-tree',rev,'--',GITLINK)
    match = re.fullmatch(r'160000 commit ([0-9a-f]{40})\tthird_party/mmrl-shared',line)
    if not match:
        raise Refusal('Missing or malformed original shared submodule gitlink')
    return match.group(1)


def prepare(c: Consumer, sha: str, work: Path) -> dict[str,Any]:
    base_remote=ref(c.repo,c.base)
    if not base_remote:
        raise Refusal('Required consumer base branch missing')
    head_feature=ref(c.repo, BRANCH,missing_ok=True)
    prs=get_prs(c)
    if len(prs)>1:
        raise Refusal('Ambiguous existing PRs')
    if head_feature:
        if prs and (len(prs)!=1 or prs[0].get('state')!='open' or prs[0].get('draft') is True):
            raise Refusal('Existing N05F PR was closed or is unexpected; manual reconciliation required')
        if prs and (prs[0].get('head') or {}).get('sha')!=head_feature:
            raise Refusal('PR head differs from GitHub branch reference')
    elif prs:
        raise Refusal('Existing N05F PR without branch; manual reconciliation required')
    # Fetch only the approved branch, without a checkout or submodule initialization.
    run(['git','clone','--quiet','--no-checkout','--filter=blob:none','--single-branch',
         '--branch',c.base,f'https://github.com/{c.repo}.git',str(work)],timeout=240)
    base=git(work,'rev-parse','HEAD')
    if base!=base_remote or ref(c.repo,c.base)!=base:
        raise Refusal('Consumer base moved while evaluating update')
    base_pin=tree_pin(work,base)
    upstream_fast_forward(base_pin,sha)
    parent=base
    if head_feature:
        git(work,'fetch','--quiet','--no-tags','origin',f'refs/heads/{BRANCH}:refs/remotes/origin/n05f')
        parent=git(work,'rev-parse','refs/remotes/origin/n05f')
        if parent!=head_feature:
            raise Refusal('PR branch changed during evaluation')
        # Preserve only an existing feature branch that changed the shared gitlink.
        merge_base=git(work,'merge-base',base,parent)
        paths=git(work,'diff','--name-only',merge_base,parent).splitlines()
        if any(p not in (GITLINK, c.pin_fixture) for p in paths):
            raise Refusal('Existing N05F branch includes unrelated changes')
        last_message=git(work,'show','-s','--format=%s',parent)
        if not last_message.startswith(COMMIT_PREFIX):
            raise Refusal('Existing N05F branch was not produced by this workflow')
    parent_pin=tree_pin(work,parent)
    upstream_fast_forward(parent_pin,sha)
    mode_line=git(work,'ls-tree',parent,'--',c.pin_fixture)
    if not re.fullmatch(r'100644 blob [0-9a-f]{40}\t'+re.escape(c.pin_fixture),mode_line):
        raise Refusal('Unexpected source-test fixture mode or path')
    fixture=git(work,'show',f'{parent}:{c.pin_fixture}')
    if fixture.count(parent_pin)!=1 or fixture.count('third_party/mmrl-shared')!=1:
        raise Refusal('Pinned source-contract fixture is not the exact expected form')
    return {'consumer':c.slug,'repository':c.repo,'base':c.base,
            'base_head':base,'branch':BRANCH,'branch_head':head_feature,
            'parent':parent,'current_pin':base_pin,'branch_pin':parent_pin,
            'target_sha':sha,'pin_fixture':c.pin_fixture,'action':('unchanged' if base_pin==sha else
                  (('already-proposed' if prs else 'recover-pr') if parent_pin==sha and head_feature
                    else ('update-pr' if head_feature and prs else
                          'recover-update-pr' if head_feature else 'create-pr'))),
            'pr_number':prs[0]['number'] if prs else None,'checkout':str(work)}


def commit_and_push(row: dict[str,Any]) -> str:
    root=Path(row['checkout']); parent=row['parent']; sha=row['target_sha']
    git(root,'read-tree',parent)
    git(root,'update-index','--add','--cacheinfo',f'160000,{sha},{GITLINK}')
    fixture=git(root,'show',f'{parent}:{row["pin_fixture"]}')
    if fixture.count(row['branch_pin']) != 1:
        raise Refusal('Pinned test fixture unexpectedly changed')
    updated=fixture.replace(row['branch_pin'],sha)+'\n'
    blob=run(['git','-C',str(root),'hash-object','-w','--stdin'],stdin=updated)
    if not blob or not SHA.fullmatch(blob):
        raise Refusal('Failed to write pinned-test blob')
    git(root,'update-index','--add','--cacheinfo',f'100644,{blob},{row["pin_fixture"]}')
    tree=git(root,'write-tree')
    old_tree=git(root,'rev-parse',f'{parent}^{{tree}}')
    if tree==old_tree:
        raise Refusal('Update produced no tree change')
    env=os.environ.copy()
    env.update({'GIT_AUTHOR_NAME':'MMRL Shared Sync','GIT_AUTHOR_EMAIL':'actions@users.noreply.github.com',
                'GIT_COMMITTER_NAME':'MMRL Shared Sync','GIT_COMMITTER_EMAIL':'actions@users.noreply.github.com'})
    # commit-tree does not require any blob checkout or local Gradle invocation.
    p=subprocess.run(['git','-C',str(root),'commit-tree',tree,'-p',parent,
                      '-m',COMMIT_PREFIX+sha],capture_output=True,text=True,env=env,timeout=30)
    if p.returncode or not SHA.fullmatch(p.stdout.strip()):
        raise Refusal('Git commit-tree failed')
    new=p.stdout.strip()
    if ref(SOURCE,'main')!=sha:
        raise Refusal('Shared main advanced during propagation')
    if ref(row['repository'],row['base'])!=row['base_head']:
        raise Refusal('Consumer base moved before push')
    if ref(row['repository'],BRANCH,missing_ok=True)!=row['branch_head']:
        raise Refusal('Consumer feature branch moved before push')
    git(root,'push','origin',f'{new}:refs/heads/{BRANCH}')  # no force and no refspec modifiers
    return new


def ensure_pr(c: Consumer, row: dict[str,Any], new_head: str) -> dict[str,Any]:
    if ref(SOURCE,'main')!=row['target_sha']:
        raise Refusal('Shared main advanced before PR creation')
    title='chore(n05f): update pinned MMRL-Shared Kotlin core'
    body=(f'Source-pinned shared Kotlin dependency update to `{row["target_sha"]}`.\n\n'
          'Automated change is limited to `third_party/mmrl-shared` gitlink and its exact-SHA source-contract fixture. '
          f'Validated source: [N05A Shared JVM CI](https://github.com/{SOURCE}/actions).\n\n'
          'Do not merge unless the consumer PR-hosted Android/Devtool checks pass. '
          'Never auto-merge, release, or run privileged device operations.\n')
    if row['pr_number']:
        current=gh(f'repos/{c.repo}/pulls/{row["pr_number"]}')
        if current.get('state')!='open' or (current.get('head') or {}).get('sha')!=new_head:
            raise Refusal('PR identity changed after push; manual reconciliation required')
        gh(f'repos/{c.repo}/pulls/{row["pr_number"]}',method='PATCH',payload={'title':title,'body':body})
        return {'result':'updated-pr','pr_number':row['pr_number']}
    created=gh(f'repos/{c.repo}/pulls',method='POST',payload={'title':title,'head':BRANCH,
                                                               'base':c.base,'body':body,'draft':False,
                                                               'maintainer_can_modify':False})
    if (created.get('head') or {}).get('sha')!=new_head or created.get('draft') is not False:
        raise Refusal('PR creation returned an unexpected commit or draft status')
    return {'result':'created-pr','pr_number':created['number']}


def execute(sha: str, mode: str) -> dict[str,Any]:
    latest_validated_source(sha)
    entries=[]
    with tempfile.TemporaryDirectory(prefix='mmrl-n05f-') as tmp:
        for c in CONSUMERS:
            row=prepare(c,sha,Path(tmp)/c.slug)
            entries.append(row)
        # Plan both repos before the first write, ensuring preflight fail-closed.
        if mode=='apply':
            for row,c in zip(entries,CONSUMERS):
                if row['action'] in {'unchanged','already-proposed'}:
                    continue
                new=row['parent'] if row['action']=='recover-pr' else commit_and_push(row)
                row.update(ensure_pr(c,row,new))
                row['new_head']=new
        for row in entries:
            row.pop('checkout',None)
            row.pop('parent',None)
    return {'schema':'mmrl.n05f.propagation.v1','mode':mode,'source':SOURCE,
            'source_sha':sha,'local_gradle':False,'auto_merge':False,
            'releases':False,'consumers':entries}


def main() -> int:
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('mode',choices=['plan','apply'])
    p.add_argument('--sha',required=True)
    p.add_argument('--receipt',type=Path)
    args=p.parse_args()
    if args.mode=='apply' and not os.getenv('GH_TOKEN') and not os.getenv('GITHUB_TOKEN'):
        raise Refusal('GH_TOKEN / GitHub App token required for writing consumer PRs')
    output=execute(check_sha(args.sha),args.mode)
    if args.receipt:
        if args.receipt.exists() or args.receipt.is_symlink():
            raise Refusal('Receipt file exists; refusing overwrite')
        args.receipt.write_text(json.dumps(output,indent=2,sort_keys=True)+'\n',encoding='utf-8')
    print(json.dumps(output,indent=2,sort_keys=True))
    return 0

if __name__=='__main__':
    try:raise SystemExit(main())
    except Refusal as exc:
        print(f'N05F_REFUSED: {exc}',file=sys.stderr)
        raise SystemExit(2)
