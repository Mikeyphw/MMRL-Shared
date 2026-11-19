"""Current N05F lifecycle, origin, provenance, and negative regression gates."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import sys

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ((ROOT / 'patch' / 'n05f_propagate.py')
          if (ROOT / 'patch' / 'n05f_propagate.py').is_file()
          else (ROOT / 'n05f_propagate.py'))
spec = importlib.util.spec_from_file_location('n05f_propagate', SCRIPT)
n = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = n
spec.loader.exec_module(n)
OLD = '9e09e1267099ef6a015f0c4bb1dcd31e3471d0ee'
CURRENT = '56299faf7f17851485a607b97ca2f0eec3bb2dca'
NEW = 'a' * 40


def cmd(*args, cwd=None, env=None, input=None):
    return subprocess.run(args, cwd=cwd, env=env, input=input, text=True,
                          capture_output=True, check=True).stdout.strip()


def fixture(pin=OLD):
    return (f'PIN = "{pin}"\nSUB = "third_party/mmrl-shared"\n'
            "self.assertIn('third_party/mmrl-shared/src/main/kotlin', adapter)\n")


class N05FContracts(unittest.TestCase):
    def test_exact_sha_and_full_sha_derived_branch(self):
        self.assertEqual(n.branch_for(NEW), n.BRANCH_PREFIX + NEW)
        self.assertNotEqual(n.branch_for(OLD), n.branch_for(NEW))
        self.assertEqual(len(n.branch_for(NEW).split('-')[-1]), 40)
        for value in ('', 'foo', 'A' * 40, NEW + '0', 'a' * 39, 'refs/heads/main'):
            with self.assertRaises(n.Refusal):
                n.branch_for(value)

    def test_exact_consumer_targets_and_recreated_repository_identity(self):
        self.assertEqual([(c.repo, c.repo_id, c.base) for c in n.CONSUMERS], [
            ('Mikeyphw/MMRL', 1463338730, 'next'),
            ('Mikeyphw/MMRL-LSPosed', 1444635884, 'main'),
        ])
        self.assertEqual(n.SOURCE_ID, 1448291955)
        self.assertEqual(n.GITLINK, 'third_party/mmrl-shared')

    def test_repository_recreation_and_unauthorized_access_refused(self):
        with patch.object(n, 'gh', return_value={'full_name': n.CONSUMERS[0].repo,
                                                   'id': 1298790213}):
            with self.assertRaisesRegex(n.Refusal, 'identity changed'):
                n.identity(n.CONSUMERS[0].repo, n.CONSUMERS[0].repo_id)
        with patch.object(n, 'gh', return_value={'message': 'Bad credentials'}):
            with self.assertRaises(n.Refusal):
                n.identity(n.CONSUMERS[0].repo, n.CONSUMERS[0].repo_id)

    def test_latest_shared_source_must_have_matching_successful_push(self):
        runs = {'workflow_runs': [{'head_sha': NEW,
                  'name': 'N05A Shared Kotlin JVM contracts and staged Maven',
                  'workflow_id': n.N05A_WORKFLOW_ID,
                  'head_repository': {'id': n.SOURCE_ID, 'full_name': n.SOURCE},
                  'event': 'push', 'conclusion': 'success', 'status': 'completed'}]}
        with patch.object(n, 'identity'), patch.object(n, 'ref', return_value=NEW), \
             patch.object(n, 'gh', return_value=runs):
            n.latest_validated_source(NEW)
            with self.assertRaises(n.Refusal):
                n.latest_validated_source(OLD)
        for event in ('pull_request', 'workflow_dispatch'):
            changed = {'workflow_runs': [{**runs['workflow_runs'][0], 'event': event}]}
            with patch.object(n, 'identity'), patch.object(n, 'ref', return_value=NEW), \
                 patch.object(n, 'gh', return_value=changed):
                with self.assertRaises(n.Refusal):
                    n.latest_validated_source(NEW)

    def test_same_named_wrong_id_and_recreated_repo_n05a_runs_refused(self):
        trusted = {'head_sha': NEW,
                   'name': 'N05A Shared Kotlin JVM contracts and staged Maven',
                   'workflow_id': n.N05A_WORKFLOW_ID,
                   'head_repository': {'id': n.SOURCE_ID, 'full_name': n.SOURCE},
                   'event': 'push', 'conclusion': 'success', 'status': 'completed'}
        for field, value in (
            ('workflow_id', n.N05A_WORKFLOW_ID + 1),
            ('head_repository', {'id': 123, 'full_name': n.SOURCE}),
            ('head_repository', {'id': n.SOURCE_ID, 'full_name': 'attacker/MMRL-Shared'}),
        ):
            run = {**trusted, field: value}
            with patch.object(n, 'identity'), patch.object(n, 'ref', return_value=NEW), \
                 patch.object(n, 'gh', return_value={'workflow_runs': [run]}):
                with self.assertRaisesRegex(n.Refusal, 'no completed successful N05A'):
                    n.latest_validated_source(NEW)

    def test_downgrades_and_unknown_comparison_refused(self):
        for bad in ({'status': 'behind', 'ahead_by': 0},
                    {'status': 'diverged', 'ahead_by': 2},
                    {'status': 'ahead', 'ahead_by': 0}, {}):
            with patch.object(n, 'gh', return_value=bad):
                with self.assertRaises(n.Refusal):
                    n.upstream_fast_forward(OLD, NEW)
        with patch.object(n, 'gh') as mock:
            n.upstream_fast_forward(NEW, NEW)
            mock.assert_not_called()

    def test_already_pinned_consumer_skips_closed_pr_and_old_feature_branch(self):
        c = n.CONSUMERS[0]
        def git_mock(root, *args):
            self.assertEqual(args, ('rev-parse', 'HEAD'))
            return OLD
        with tempfile.TemporaryDirectory() as tmp, \
             patch.object(n, 'identity'), patch.object(n, 'ref', return_value=OLD) as r, \
             patch.object(n, 'run'), patch.object(n, 'git', side_effect=git_mock), \
             patch.object(n, 'tree_pin', return_value=OLD), \
             patch.object(n, 'fixture_at') as f, patch.object(n, 'get_prs') as prs:
            row = n.prepare(c, OLD, Path(tmp) / 'checkout')
        self.assertEqual(row['action'], 'unchanged')
        self.assertIsNone(row['pr_number'])
        self.assertEqual(row['branch'], n.branch_for(OLD))
        f.assert_called_once()
        prs.assert_not_called()
        self.assertEqual(r.call_count, 2)

    def test_stale_closed_pr_for_current_unmerged_source_is_refused(self):
        c = n.CONSUMERS[0]
        branch = n.branch_for(NEW)
        closed = {'state': 'closed', 'draft': False,
                  'head': {'ref': branch, 'sha': OLD},
                  'base': {'ref': 'next'}, 'number': 18}
        def ref_mock(repo, requested, missing_ok=False):
            return OLD if requested == c.base else OLD
        with tempfile.TemporaryDirectory() as tmp, patch.object(n, 'identity'), \
             patch.object(n, 'ref', side_effect=ref_mock), \
             patch.object(n, 'run'), patch.object(n, 'git', return_value=OLD), \
             patch.object(n, 'tree_pin', return_value=OLD), \
             patch.object(n, 'fixture_at'), \
             patch.object(n, 'upstream_fast_forward'), \
             patch.object(n, 'get_prs', return_value=[closed]):
            with self.assertRaisesRegex(n.Refusal, 'Closed'):
                n.prepare(c, NEW, Path(tmp) / 'checkout')

    def test_foreign_branch_marker_refused_even_if_paths_match(self):
        c = n.CONSUMERS[0]
        old_branch_head = 'b' * 40
        refs = [OLD, OLD, old_branch_head]  # base, base recheck, feature
        def ref_mock(repo, requested, missing_ok=False):
            if requested == c.base:
                return OLD
            return old_branch_head
        def git_mock(root, *args):
            if args[:1] == ('fetch',):
                return ''
            if args[:2] == ('rev-parse', 'HEAD'):
                return OLD
            if args[:2] == ('rev-parse', 'refs/remotes/origin/n05f'):
                return old_branch_head
            if args[:1] == ('merge-base',):
                return OLD
            if args[:2] == ('diff', '--name-only'):
                return n.GITLINK
            if args[:3] == ('show', '-s', '--format=%s'):
                return n.COMMIT_PREFIX + 'c' * 40
            raise AssertionError(args)
        with tempfile.TemporaryDirectory() as tmp, patch.object(n, 'identity'), \
             patch.object(n, 'ref', side_effect=ref_mock), \
             patch.object(n, 'run'), patch.object(n, 'git', side_effect=git_mock), \
             patch.object(n, 'tree_pin', return_value=OLD), \
             patch.object(n, 'fixture_at'), \
             patch.object(n, 'upstream_fast_forward'), \
             patch.object(n, 'get_prs', return_value=[]):
            with self.assertRaisesRegex(n.Refusal, 'exact source SHA'):
                n.prepare(c, NEW, Path(tmp) / 'checkout')

    def test_recoverable_existing_source_branch_reuses_open_pr_only(self):
        c = n.CONSUMERS[0]
        head = 'b' * 40
        branch = n.branch_for(NEW)
        pr = {'state': 'open', 'draft': False,
              'head': {'ref': branch, 'sha': head},
              'base': {'ref': c.base}, 'number': 91}
        def ref_mock(repo, requested, missing_ok=False):
            return OLD if requested == c.base else head
        def git_mock(root, *args):
            if args[:1] == ('fetch',):
                return ''
            if args[:2] == ('rev-parse', 'HEAD'):
                return OLD
            if args[:2] == ('rev-parse', 'refs/remotes/origin/n05f'):
                return head
            if args[:1] == ('merge-base',):
                return OLD
            if args[:2] == ('diff', '--name-only'):
                return n.GITLINK + '\n' + c.pin_fixture
            if args[:3] == ('show', '-s', '--format=%s'):
                return n.COMMIT_PREFIX + NEW
            raise AssertionError(args)
        with tempfile.TemporaryDirectory() as tmp, patch.object(n, 'identity'), \
             patch.object(n, 'ref', side_effect=ref_mock), \
             patch.object(n, 'run'), patch.object(n, 'git', side_effect=git_mock), \
             patch.object(n, 'tree_pin', side_effect=[OLD, NEW]), \
             patch.object(n, 'fixture_at'), \
             patch.object(n, 'upstream_fast_forward'), \
             patch.object(n, 'get_prs', return_value=[pr]):
            result = n.prepare(c, NEW, Path(tmp) / 'checkout')
        self.assertEqual(result['action'], 'already-proposed')
        self.assertEqual(result['pr_number'], 91)

    def test_source_contract_fixture_is_exact(self):
        self.assertTrue(n.approved_fixture(fixture(OLD), OLD))
        self.assertFalse(n.approved_fixture(fixture(OLD) + OLD, OLD))
        self.assertFalse(n.approved_fixture(fixture(OLD).replace('SUB =', 'OTHER ='), OLD))
        self.assertFalse(n.approved_fixture(fixture(OLD) + n.GITLINK, OLD))

    def test_git_plumbing_changes_only_gitlink_and_pin_fixture(self):
        with tempfile.TemporaryDirectory(prefix='n05f-test-') as temp:
            base = Path(temp)
            bare, work = base / 'remote.git', base / 'src'
            cmd('git', 'init', '--bare', '-q', str(bare))
            cmd('git', 'clone', '-q', str(bare), str(work))
            cmd('git', '-C', str(work), 'config', 'user.email', 'local@example.com')
            cmd('git', '-C', str(work), 'config', 'user.name', 'Local Test')
            file = n.CONSUMERS[0].pin_fixture
            (work / 'scripts').mkdir()
            (work / file).write_text(fixture(OLD), encoding='utf-8')
            cmd('git', '-C', str(work), 'add', file)
            cmd('git', '-C', str(work), 'update-index', '--add', '--cacheinfo',
                f'160000,{OLD},{n.GITLINK}')
            cmd('git', '-C', str(work), 'commit', '-qm', 'test base')
            original = cmd('git', '-C', str(work), 'rev-parse', 'HEAD')
            cmd('git', '-C', str(work), 'push', '-q', 'origin', 'HEAD:refs/heads/next')
            with patch.object(n, 'ref', side_effect=lambda repo, branch, missing_ok=False:
                              NEW if repo == n.SOURCE else original if branch == 'next' else None):
                row = {'checkout': str(work), 'parent': original, 'target_sha': NEW,
                       'repository': n.CONSUMERS[0].repo, 'base': 'next',
                       'base_head': original, 'branch_head': None,
                       'branch': n.branch_for(NEW), 'pin_fixture': file,
                       'branch_pin': OLD}
                commit = n.commit_and_push(row)
            changed = cmd('git', '-C', str(work), 'diff', '--name-only', original, commit).splitlines()
            self.assertEqual(changed, [file, n.GITLINK])
            self.assertEqual(cmd('git', '-C', str(work), 'ls-tree', commit, '--', n.GITLINK),
                             f'160000 commit {NEW}\t{n.GITLINK}')
            content = cmd('git', '-C', str(work), 'show', f'{commit}:{file}')
            self.assertIn(NEW, content)
            self.assertNotIn(OLD, content)
            self.assertEqual(cmd('git', '-C', str(bare), 'rev-parse',
                                 f'refs/heads/{n.branch_for(NEW)}'), commit)

    def test_plan_never_writes_and_propagation_never_merges(self):
        a = {'consumer': 'mmrl-next', 'repository': n.CONSUMERS[0].repo,
             'base': 'next', 'base_head': OLD, 'branch': n.branch_for(NEW),
             'branch_head': None, 'parent': OLD, 'current_pin': OLD,
             'branch_pin': OLD, 'target_sha': NEW, 'pin_fixture': n.CONSUMERS[0].pin_fixture,
             'action': 'create-pr', 'pr_number': None, 'checkout': '/tmp/mock'}
        b = {**a, 'consumer': 'mmrl-lsposed', 'repository': n.CONSUMERS[1].repo,
             'base': 'main', 'pin_fixture': n.CONSUMERS[1].pin_fixture}
        with patch.object(n, 'latest_validated_source'), \
             patch.object(n, 'prepare', side_effect=[a, b]), \
             patch.object(n, 'commit_and_push', side_effect=AssertionError('plan mutated')):
            result = n.execute(NEW, 'plan')
        self.assertEqual(len(result['consumers']), 2)
        self.assertFalse(result['auto_merge'])
        self.assertNotIn('checkout', result['consumers'][0])
        self.assertNotIn('parent', result['consumers'][0])

    def test_unchanged_consumers_do_not_push_or_create_pr_on_apply(self):
        a = {'action': 'unchanged', 'consumer': 'mmrl-next',
             'parent': OLD, 'checkout': '/tmp/none'}
        b = {'action': 'unchanged', 'consumer': 'mmrl-lsposed',
             'parent': OLD, 'checkout': '/tmp/none'}
        with patch.object(n, 'latest_validated_source'), \
             patch.object(n, 'prepare', side_effect=[a, b]), \
             patch.object(n, 'commit_and_push', side_effect=AssertionError('replayed commit')), \
             patch.object(n, 'ensure_pr', side_effect=AssertionError('reopened PR')):
            result = n.execute(NEW, 'apply')
        self.assertTrue(all(row['action'] == 'unchanged' for row in result['consumers']))

    def test_safe_github_failure_redacts_credentials(self):
        bad = subprocess.CompletedProcess([], 1, '',
            'gh: Resource not accessible by integration (HTTP 403)\nsecret-token')
        with patch.object(n.subprocess, 'run', return_value=bad):
            with self.assertRaises(n.Refusal) as cm:
                n.gh('repos/Mikeyphw/MMRL-Shared/actions/runs?branch=main')
        self.assertIn('http=403', str(cm.exception))
        self.assertNotIn('secret-token', str(cm.exception))

    def test_read_token_separated_from_consumer_write_token(self):
        with patch.dict(os.environ, {'GH_TOKEN': 'consumer-only',
                                     'N05F_READ_TOKEN': 'shared-read',
                                     'GITHUB_ACTIONS': 'true'}, clear=True):
            with patch.object(n.subprocess, 'run',
                    return_value=subprocess.CompletedProcess([], 0, '{}', '')) as mock:
                n.gh('repos/Mikeyphw/MMRL-Shared/git/ref/heads/main')
                self.assertEqual(mock.call_args.kwargs['env']['GH_TOKEN'], 'shared-read')
                n.gh('repos/Mikeyphw/MMRL/git/ref/heads/next')
                self.assertEqual(mock.call_args.kwargs['env']['GH_TOKEN'], 'consumer-only')
        with patch.dict(os.environ, {'GH_TOKEN': 'consumer-only', 'GITHUB_ACTIONS': 'true'}, clear=True):
            with self.assertRaisesRegex(n.Refusal, 'read token missing'):
                n.gh('repos/Mikeyphw/MMRL-Shared/actions/runs?branch=main')
        with self.assertRaisesRegex(n.Refusal, 'read-only'):
            n.gh('repos/Mikeyphw/MMRL-Shared/git/ref/heads/main', method='POST', payload={})

    def test_hosted_workflow_and_ci_policy_contracts(self):
        workflow = ((ROOT / 'patch' / 'n05f-propagate.yml')
                    if (ROOT / 'patch' / 'n05f-propagate.yml').is_file()
                    else (ROOT.parent / '.github/workflows/n05f-propagate.yml'))
        # Artifact-oriented unit runners may carry the workflow in an independent
        # source checkout; in-repo CI must exercise this current source.
        self.assertTrue(workflow.is_file(), 'Trusted hosted trigger workflow missing')
        txt = workflow.read_text(encoding='utf-8')
        self.assertIn('workflow_run:', txt)
        self.assertIn('MMRL_FEDERATION_TOKEN', txt)
        self.assertNotIn('workflow_dispatch:', txt)
        self.assertIn("conclusion == 'success'", txt)
        policy = ROOT.parent / 'config/delivery-required-ci.json'
        self.assertTrue(policy.is_file(), 'Committed required-CI policy missing')
        parsed = json.loads(policy.read_text(encoding='utf-8'))
        self.assertEqual(parsed['repository'], n.SOURCE)
        self.assertEqual(parsed['base_branch'], 'main')
        self.assertEqual(parsed['event'], 'pull_request')
        self.assertEqual(parsed['required_workflows'], [
            {'name': 'N05A Shared Kotlin JVM contracts and staged Maven',
             'workflow_id': 409351241}])
        source = SCRIPT.read_text(encoding='utf-8')
        self.assertNotIn('git push --force', source)
        self.assertNotIn('gh pr merge', source)
        self.assertNotIn('gradlew', source)
        self.assertIn('branch_for', source)
        self.assertIn('N05A_WORKFLOW_ID', source)
        n05a = ROOT.parent / '.github/workflows/n05a-jvm.yml'
        self.assertTrue(n05a.is_file(), 'N05A hosted workflow missing')
        n05a_text = n05a.read_text(encoding='utf-8')
        self.assertIn("python3 -B -m unittest discover -s scripts/tests -p 'test_n05f.py' -v", n05a_text)
        self.assertIn('pull_request:', n05a_text)
        self.assertIn('push:', n05a_text)


if __name__ == '__main__':
    unittest.main()
