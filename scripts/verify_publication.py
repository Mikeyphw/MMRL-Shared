#!/usr/bin/env python3
"""Attest exact GitHub source, executed JVM tests and local Maven staging outputs."""
from pathlib import Path
import hashlib
import json
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
COORD = 'io.github.mikeyphw.mmrl:mmrl-shared-core:0.1.0-n05a'
PUBLISHED = ROOT / 'build/staging-maven/io/github/mikeyphw/mmrl/mmrl-shared-core/0.1.0-n05a'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    if os.getenv('GITHUB_ACTIONS') != 'true' or os.getenv('GITHUB_REPOSITORY') != 'Mikeyphw/MMRL-Shared':
        raise RuntimeError('Exact public repository GitHub Actions run required')
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    if commit != os.getenv('GITHUB_SHA') or len(commit) != 40:
        raise RuntimeError('Cannot attest a different source commit')
    testfiles = sorted((ROOT / 'build/test-results/test').glob('TEST-*.xml'))
    if not testfiles:
        raise RuntimeError('No JVM tests were executed')
    tests, failures, errors = 0, 0, 0
    for t in testfiles:
        root = ET.parse(t).getroot()
        tests += int(root.get('tests', 0))
        failures += int(root.get('failures', 0))
        errors += int(root.get('errors', 0))
    if tests < 5 or failures or errors:
        raise RuntimeError('JVM tests failed, were skipped or are incomplete')
    required = ('mmrl-shared-core-0.1.0-n05a.jar',
                'mmrl-shared-core-0.1.0-n05a-sources.jar',
                'mmrl-shared-core-0.1.0-n05a.pom')
    files = {}
    for name in required:
        path = PUBLISHED / name
        if not path.is_file() or not path.stat().st_size:
            raise RuntimeError('Missing publication member: ' + name)
        files[name] = {'size': path.stat().st_size, 'sha256': sha(path)}
    receipt = {
        'schema': 'mmrl-shared.n05a.staged-maven.v1',
        'source_commit': commit,
        'repository': os.getenv('GITHUB_REPOSITORY'),
        'github_run_id': os.environ.get('GITHUB_RUN_ID', ''),
        'github_run_attempt': os.environ.get('GITHUB_RUN_ATTEMPT', ''),
        'coordinate': COORD,
        'jvm_tests': tests,
        'stage_only': True,
        'signature_verified': False,
        'release_qualified': False,
        'files': files,
    }
    output = ROOT / 'build/n05a-staging-receipt.json'
    output.write_text(json.dumps(receipt, indent=2, sort_keys=True) + '\n')
    print(json.dumps(receipt, sort_keys=True))


if __name__ == '__main__':
    try:
        main()
    except (KeyError, OSError, ValueError, RuntimeError, ET.ParseError) as e:
        print('N05A_PUBLICATION_REFUSED: ' + str(e), file=sys.stderr)
        raise SystemExit(2) from e
