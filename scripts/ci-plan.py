#!/usr/bin/env python3
"""Plan CI from the checked-out Git tree, or verify the selected jobs' results."""

import json
import os
import re
import subprocess
import sys
from pathlib import Path

JOBS = frozenset({'protocol-regeneration', 'host', 'sources', 'bundle', 'android',
                  'reply-recovery-emulator', 'prek'})
HOST = frozenset({'host', 'sources', 'bundle'})
ANDROID = frozenset({'android', 'reply-recovery-emulator'})
ARTIFACTS = frozenset({'sources', 'bundle'}) | ANDROID
PREK_CONFIGS = {'prek.toml', '.pre-commit-config.yaml', '.pre-commit-config.yml',
                '.pre-commit-hooks.yaml', '.pre-commit-hooks.yml'}
DOCS = {'README.md', 'AGENTS.md', 'CONTRIBUTING.md', 'android/README.md',
        'android/dependencies.md', 'daemon/README.md', 'daemon/DEPENDENCIES.md',
        'pi-extension/README.md'}
REV = re.compile(r'[0-9a-f]{40}')


def classify(path):
    if path in DOCS or re.fullmatch(r'docs/(?:[\w-]+\.md|(?:images|screenshots)/[\w-]+\.png)', path, re.ASCII):
        return frozenset()
    if path in PREK_CONFIGS:
        return frozenset({'prek'})
    if re.fullmatch(r'(?:rust/kompi-daemon/(?:src|tests|examples)/.+\.rs|daemon/(?:src|test)/.+\.ts)', path, re.ASCII):
        return HOST
    if (not path.startswith('android/app/src/main/java/com/kompi/app/protocol/')
            and re.fullmatch(r'android/app/src/(?:main|test|androidTest)/(?:java/.+\.(?:java|kt)|res/.+\.xml)', path, re.ASCII)):
        return ANDROID
    # Package manifests, build scripts, protocol, vendored libraries, and new path
    # families default to full CI. Expand the allowlist only after auditing inputs.
    return JOBS


def git(*args):
    return subprocess.check_output(['git', *args])


def revision(value):
    if not isinstance(value, str) or not REV.fullmatch(value) or value == '0' * 40:
        raise ValueError('missing or invalid event commit')
    return value


def select(event_name, ref, package, event):
    """Return (jobs, cache mode). Only main may write; PRs restore downloads only."""
    if package or ref.startswith('refs/tags/'):
        return JOBS, 'off'
    main = event_name == 'push' and ref == 'refs/heads/main'
    pr = event_name == 'pull_request'
    if not (main or pr):
        return JOBS, 'off'
    cache = 'main' if main else 'downloads'
    try:
        head, *parents = git('rev-list', '--parents', '-n', '1', 'HEAD').decode('ascii').split()
        if pr:
            # Job checkouts build GitHub's merge tree, not the PR branch. Use the
            # immutable event base only if HEAD is exactly the expected test merge.
            pull = event['pull_request']
            base = revision(pull['base']['sha'])
            if parents != [base, revision(pull['head']['sha'])]:
                raise ValueError('checkout is not the event test merge')
        else:
            base = revision(event['before'])
            if head != revision(event['after']):
                raise ValueError('checkout is not the event push head')
            git('merge-base', '--is-ancestor', base, head)
        # No rename detection: both paths of a move are classified, along with
        # deletions. NUL delimiters preserve whitespace and unusual filenames.
        raw = git('diff', '--no-ext-diff', '--no-renames', '--ignore-submodules=none',
                  '--name-only', '-z', base, head, '--')
        selected = frozenset().union(*(classify(os.fsdecode(path)) for path in raw.split(b'\0') if path))
    except (KeyError, TypeError, ValueError, subprocess.CalledProcessError) as exc:
        print(f'Cannot establish changed paths ({exc}); running full CI', file=sys.stderr)
        return JOBS, cache
    if main and selected - {'prek'}:
        selected |= ARTIFACTS  # Existing main artifact consumers get every package together.
    return selected, cache


def verify(needs):
    """Reject missing, cancelled, unsuccessful, or unexpectedly skipped work."""
    planner = needs['plan']
    if planner['result'] != 'success':
        raise ValueError(f"planner result: {planner['result']}")
    planned = json.loads(planner['outputs']['jobs'])
    if (not isinstance(planned, dict) or planned.keys() != JOBS
            or any(type(run) is not bool for run in planned.values())
            or needs.keys() != JOBS | {'plan'}):
        raise ValueError('incomplete or invalid job plan/results')
    failures = []
    for job, run in sorted(planned.items()):
        expected = 'success' if run else 'skipped'
        actual = needs[job]['result']
        if actual != expected:
            failures.append(f'{job}: expected {expected}, got {actual}')
    if failures:
        raise ValueError('\n'.join(failures))
    print('All selected jobs succeeded; unselected jobs were skipped.')


def main():
    if sys.argv[1:] == ['route']:
        event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_bytes())
        selected, cache = select(os.environ['GITHUB_EVENT_NAME'], os.environ['GITHUB_REF'],
                                 os.environ['CI_PACKAGE'] == 'true', event)
        plan = {job: job in selected for job in sorted(JOBS)}
        print(f'Selected jobs: {", ".join(sorted(selected)) or "none"}; cache: {cache}')
        with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as output:
            print(f'jobs={json.dumps(plan, separators=(",", ":"))}', f'cache={cache}', sep='\n', file=output)
    elif sys.argv[1:] == ['gate']:
        try:
            verify(json.loads(os.environ['CI_NEEDS']))
        except (KeyError, TypeError, ValueError) as exc:
            sys.exit(f'CI gate failed: {exc}')
    else:
        sys.exit('Usage: ci-plan.py {route|gate}')


if __name__ == '__main__':
    main()
