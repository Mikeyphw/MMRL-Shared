#!/usr/bin/env python3
"""N05A offline source boundary contract: no Gradle or network required."""
from pathlib import Path
import re
import sys
import tomllib

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = ROOT / 'src/main/kotlin/io/github/mikeyphw/mmrl/shared'
FILES = ('ReleaseIdentity.kt', 'Sha256Digest.kt', 'WorkflowArtifact.kt', 'StableSort.kt')


def main() -> None:
    source = [(PACKAGE / name).read_text(encoding='utf-8') for name in FILES]
    assert all(s.startswith('package io.github.mikeyphw.mmrl.shared\n') for s in source)
    for s in source:
        assert not re.search(r'(?m)^import\s+(?:android|androidx|com\.dergoogler|io\.github\.mikeyphw\.mmrl\.lsposed)\.', s)
        assert not any(word in s for word in ('Runtime.getRuntime(', 'ProcessBuilder(', 'su -c', 'VectorCli', 'KernelSU', 'Magisk'))
    settings = (ROOT / 'build.gradle.kts').read_text(encoding='utf-8')
    assert 'kotlin("jvm") version "2.2.0"' in settings
    assert 'version = "0.1.0-n05a"' in settings
    assert 'maven-publish' in settings
    assert 'staging-maven' in settings
    assert 'implementation(' not in settings
    conf = tomllib.loads((ROOT / '.devtool.toml').read_text())
    assert conf['workspace']['default_target'] == 'mmrl_shared'
    props = (ROOT / 'gradle/wrapper/gradle-wrapper.properties').read_text()
    assert 'gradle-8.14.3-bin.zip' in props
    assert 'bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531' in props
    workflow = (ROOT / '.github/workflows/n05a-jvm.yml').read_text()
    assert 'publishSharedCorePublicationToStagingRepository' in workflow
    assert 'pull_request:' in workflow and 'push:' in workflow
    assert 'secrets.' not in workflow and 'workflow_dispatch:' in workflow
    assert 'verify_publication.py' in workflow
    assert (ROOT / 'src/test/kotlin/io/github/mikeyphw/mmrl/shared/SharedCoreTest.kt').is_file()
    print('N05A_PURE_SOURCE_CONTRACT_PASS')


if __name__ == '__main__':
    try:
        main()
    except (AssertionError, OSError, ValueError, tomllib.TOMLDecodeError) as e:
        print(f'N05A_SOURCE_REFUSED: {e}', file=sys.stderr)
        raise SystemExit(2) from e
