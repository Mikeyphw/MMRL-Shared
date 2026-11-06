import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

_HOME=Path(__file__).resolve().parents[1]
SCRIPT=(_HOME/'patch'/'n05f_propagate.py') if (_HOME/'patch'/'n05f_propagate.py').is_file() else (_HOME/'n05f_propagate.py')
spec=importlib.util.spec_from_file_location('n05f_propagate',SCRIPT)
import sys
n=importlib.util.module_from_spec(spec)
sys.modules[spec.name]=n
spec.loader.exec_module(n)
OLD='9e09e1267099ef6a015f0c4bb1dcd31e3471d0ee'
NEW='a'*40


def cmd(*args, cwd=None, env=None, input=None):
    p=subprocess.run(args,cwd=cwd,env=env,input=input,text=True,capture_output=True,check=True)
    return p.stdout.strip()

class Propagation(unittest.TestCase):
    def test_exact_sha(self):
        self.assertEqual(n.check_sha(OLD),OLD)
        for val in ('', 'foo','A'*40, OLD+'x','a'*39, 'refs/heads/main'):
            with self.assertRaises(n.Refusal):n.check_sha(val)

    def test_allowed_repositories_and_pins(self):
        self.assertEqual([(c.repo,c.base) for c in n.CONSUMERS],
                         [('Mikeyphw/MMRL','next'),('Mikeyphw/MMRL-LSPosed','main')])
        self.assertEqual(n.GITLINK,'third_party/mmrl-shared')
        self.assertNotIn('release',n.BRANCH)

    def test_ref_unknown_status_refused(self):
        with patch.object(n,'gh',return_value={'object':{'sha':'bad'}}):
            with self.assertRaises(n.Refusal):n.ref('Mikeyphw/MMRL','next')

    def test_unsafe_downgrade_refused(self):
        with patch.object(n,'gh',return_value={'status':'behind','ahead_by':0}):
            with self.assertRaises(n.Refusal):n.upstream_fast_forward(OLD,NEW)

    def test_trusted_ci_required(self):
        with patch.object(n,'ref',return_value=OLD), patch.object(n,'gh',return_value={'workflow_runs':[]}):
            with self.assertRaises(n.Refusal):n.latest_validated_source(OLD)

    def test_old_sha_run_not_substituted(self):
        runs={'workflow_runs':[{'head_sha':NEW,'name':'N05A Shared Kotlin JVM contracts and staged Maven',
                'event':'push','conclusion':'success','status':'completed'}]}
        with patch.object(n,'ref',return_value=OLD),patch.object(n,'gh',return_value=runs):
            with self.assertRaises(n.Refusal):n.latest_validated_source(OLD)

    def test_closed_automation_pr_is_not_reopened(self):
        c=n.CONSUMERS[0]
        closed={'state':'closed','draft':False,
                'head':{'ref':n.BRANCH,'sha':NEW},'base':{'ref':'next'},'number':18}
        with patch.object(n,'ref',side_effect=lambda repo,branch,missing_ok=False:
                          OLD if branch=='next' else NEW), \
             patch.object(n,'get_prs',return_value=[closed]):
            with tempfile.TemporaryDirectory() as tmp:
                with self.assertRaisesRegex(n.Refusal,'closed'):
                    n.prepare(c,NEW,Path(tmp)/'clone')

    def test_source_must_still_be_current(self):
        with patch.object(n,'ref',return_value=NEW):
            with self.assertRaises(n.Refusal):n.latest_validated_source(OLD)

    def test_github_head_credential_not_embedded(self):
        s=SCRIPT.read_text()
        workflow=((_HOME/'patch'/'n05f-propagate.yml') if (_HOME/'patch'/'n05f-propagate.yml').is_file() else (_HOME.parent/'.github/workflows/n05f-propagate.yml')).read_text()
        self.assertNotIn('git push --force',s)
        self.assertNotIn('gh pr merge',s)
        self.assertNotIn('gradlew',s)
        self.assertIn('workflow_run:',workflow)
        self.assertIn('MMRL_FEDERATION_TOKEN',workflow)
        self.assertNotIn('workflow_dispatch',workflow)
        self.assertNotIn('pull_request:',workflow)
        self.assertIn("conclusion == 'success'",workflow)

    def test_git_plumbing_updates_only_gitlink_and_pin_fixture(self):
        with tempfile.TemporaryDirectory(prefix='n05f-test-') as temp:
            base=Path(temp)
            bare=base/'remote.git';work=base/'src'
            cmd('git','init','--bare','-q',str(bare))
            cmd('git','clone','-q',str(bare),str(work))
            cmd('git','-C',str(work),'config','user.email','local@example.com')
            cmd('git','-C',str(work),'config','user.name','Local Test')
            fixture='scripts/test_n05c_shared_source.py'
            (work/'scripts').mkdir(parents=True)
            (work/fixture).write_text('PIN = "'+OLD+'"\nSUB = "third_party/mmrl-shared"\n')
            cmd('git','-C',str(work),'add',fixture)
            cmd('git','-C',str(work),'update-index','--add','--cacheinfo',
                f'160000,{OLD},third_party/mmrl-shared')
            cmd('git','-C',str(work),'commit','-qm','test base')
            original=cmd('git','-C',str(work),'rev-parse','HEAD')
            cmd('git','-C',str(work),'push','-q','origin','HEAD:refs/heads/next')
            with patch.object(n,'ref',side_effect=lambda repo, branch,missing_ok=False:NEW if repo==n.SOURCE else original if branch=='next' else None):
                row={'checkout':str(work),'parent':original,'target_sha':NEW,
                     'repository':'Mikeyphw/MMRL','base':'next','base_head':original,
                     'branch_head':None,'pin_fixture':fixture,'branch_pin':OLD}
                commit=n.commit_and_push(row)
            self.assertEqual(cmd('git','-C',str(work),'ls-tree',commit,'--','third_party/mmrl-shared'),
                             f'160000 commit {NEW}\tthird_party/mmrl-shared')
            changed=cmd('git','-C',str(work),'diff','--name-only',original,commit).splitlines()
            self.assertEqual(changed,[fixture,'third_party/mmrl-shared'])
            fixture_content=cmd('git','-C',str(work),'show',f'{commit}:{fixture}')
            self.assertIn(NEW,fixture_content)
            self.assertNotIn(OLD,fixture_content)
            self.assertEqual(cmd('git','-C',str(bare),'rev-parse','refs/heads/feat/n05f-shared-sync'),commit)

    def test_no_mutations_in_plan(self):
        a={'consumer':'mmrl-next','repository':'Mikeyphw/MMRL','base':'next','base_head':OLD,
           'branch':'feat/n05f-shared-sync','branch_head':None,'parent':OLD,
           'current_pin':OLD,'branch_pin':OLD,'target_sha':NEW,
           'pin_fixture':'scripts/test_n05c_shared_source.py','action':'create-pr','pr_number':None,
           'checkout':'/tmp/mock'}
        b={**a,'consumer':'mmrl-lsposed','repository':'Mikeyphw/MMRL-LSPosed',
           'pin_fixture':'scripts/tests/test_n05b_shared_contracts.py'}
        with (patch.object(n,'latest_validated_source'),
              patch.object(n,'prepare',side_effect=[a,b]),
              patch.object(n,'commit_and_push',side_effect=AssertionError('plan mutated'))):
            result=n.execute(NEW,'plan')
        self.assertEqual(len(result['consumers']),2)
        self.assertFalse(result['auto_merge'])
        self.assertNotIn('checkout',result['consumers'][0])

    def test_safe_api_failure_identifies_endpoint_without_secrets(self):
        bad=subprocess.CompletedProcess([],1,'','gh: Resource not accessible by integration (HTTP 403)\nsecret-token')
        with patch.object(n.subprocess,'run',return_value=bad):
            with self.assertRaises(n.Refusal) as cm:
                n.gh('repos/Mikeyphw/MMRL-Shared/actions/runs?branch=main&per_page=100')
        message=str(cm.exception)
        self.assertIn('endpoint=repos/Mikeyphw/MMRL-Shared/actions/runs',message)
        self.assertIn('http=403',message)
        self.assertNotIn('secret-token',message)

    def test_real_consumer_fixture_form_two_gitlink_mentions(self):
        for fixture in (
            'PIN = "'+OLD+'"\nSUB = "third_party/mmrl-shared"\n'+
            "self.assertIn('third_party/mmrl-shared/src/main/kotlin', adapter)\n",
            'EXPECTED_SHA = "'+OLD+'"\nSUB = "third_party/mmrl-shared"\n'+
            "self.assertIn('third_party/mmrl-shared/src/main/kotlin', adapter)\n",
        ):
            self.assertTrue(n.approved_fixture(fixture, OLD))
            self.assertFalse(n.approved_fixture(fixture+OLD, OLD))
            self.assertFalse(n.approved_fixture(fixture.replace('SUB =', 'OTHER ='), OLD))
            self.assertFalse(n.approved_fixture(fixture+'third_party/mmrl-shared', OLD))

    def test_shared_api_uses_workflow_scoped_read_token(self):
        with patch.dict(os.environ, {
            'GH_TOKEN':'consumer-only-token',
            'N05F_READ_TOKEN':'shared-read-token',
            'GITHUB_ACTIONS':'true'}, clear=True):
            with patch.object(n.subprocess,'run',return_value=subprocess.CompletedProcess([],0,'{}','')) as mocked:
                self.assertEqual(n.gh('repos/Mikeyphw/MMRL-Shared/git/ref/heads/main'),{})
            process_env=mocked.call_args.kwargs['env']
            self.assertEqual(process_env['GH_TOKEN'],'shared-read-token')
            self.assertEqual(process_env['GITHUB_TOKEN'],'shared-read-token')

    def test_consumer_api_retains_write_scoped_token(self):
        with patch.dict(os.environ, {
            'GH_TOKEN':'consumer-only-token',
            'N05F_READ_TOKEN':'shared-read-token',
            'GITHUB_ACTIONS':'true'}, clear=True):
            with patch.object(n.subprocess,'run',return_value=subprocess.CompletedProcess([],0,'{}','')) as mocked:
                self.assertEqual(n.gh('repos/Mikeyphw/MMRL/git/ref/heads/next'),{})
            self.assertEqual(mocked.call_args.kwargs['env']['GH_TOKEN'],'consumer-only-token')

    def test_no_ambient_shared_token_fallback_on_hosted_runner(self):
        with patch.dict(os.environ, {'GH_TOKEN':'consumer-only-token','GITHUB_ACTIONS':'true'},clear=True):
            with self.assertRaisesRegex(n.Refusal,'read token is missing'):
                n.gh('repos/Mikeyphw/MMRL-Shared/actions/runs?branch=main')

    def test_shared_read_token_cannot_be_used_for_writes(self):
        with self.assertRaisesRegex(n.Refusal,'read-only'):
            n.gh('repos/Mikeyphw/MMRL-Shared/git/ref/heads/main',method='POST',payload={})

if __name__=='__main__':unittest.main()
