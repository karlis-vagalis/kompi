#!/usr/bin/env python3
"""Copy verified release inputs from the first attempt of exact-SHA main CI.

Uses the repository-scoped Actions API via gh. Does not publish, sign, modify
source runs, or execute downloaded files. Unavailable inputs leave no output.
"""

import hashlib
import json
import os
import re
import stat
import subprocess
import sys
import tempfile
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from shutil import copyfileobj
from zipfile import BadZipFile, ZipFile

CI_PATH = '.github/workflows/ci.yml'
REQUIRED_JOBS = {'plan', 'prek', 'protocol-regeneration', 'host', 'sources',
                 'android', 'reply-recovery-emulator', 'CI ready', 'bundle-linux', 'bundle-windows'}
FILE_SETS = {
    'unsigned-apk': ('app-arm64-v8a-release-unsigned.apk', 'app-x86_64-release-unsigned.apk'),
    'daemon-x86_64-unknown-linux-gnu': ('kompi-daemon-x86_64-unknown-linux-gnu.tar.gz',
                                      'kompi-daemon-x86_64-unknown-linux-gnu.tar.gz.sha256',
                                      'kompi-daemon-installer.sh'),
    'daemon-x86_64-pc-windows-msvc': ('kompi-daemon-x86_64-pc-windows-msvc.zip',
                                     'kompi-daemon-x86_64-pc-windows-msvc.zip.sha256',
                                     'kompi-daemon-installer.ps1'),
}
MAX_BYTES = 512 * 1024 * 1024  # Upper bound for each ZIP and its expanded content.


class Ineligible(ValueError):
    """No complete, trusted candidate could be established."""


def require(ok, reason):
    if not ok:
        raise Ineligible(reason)


def positive(value):
    return type(value) is int and value > 0


@dataclass(frozen=True)
class Source:
    repo: str
    repo_id: int
    sha: str
    workflow_id: int

    def check_run(self, run, run_id):
        require(run['id'] == run_id and run['workflow_id'] == self.workflow_id
                and run['path'] == CI_PATH, 'unexpected workflow or run ID')
        for key in ('repository', 'head_repository'):
            require(run[key]['id'] == self.repo_id and run[key]['full_name'] == self.repo,
                    'unexpected repository')
        require(run['event'] == 'push' and run['head_branch'] == 'main' and run['head_sha'] == self.sha,
                'not a push to main at the exact tagged SHA')
        require(run['status'] == 'completed' and run['conclusion'] == 'success', 'run not successful')
        # Artifact metadata is scoped to run ID but doesn't identify attempts.
        # Even a successful full rerun can have leftover attempt-1 artifacts.
        require(type(run['run_attempt']) is int and run['run_attempt'] == 1, 'rerun cannot establish artifact attempt')

    def check_jobs(self, jobs):
        names = []
        for job in jobs:
            name = job['name']
            require(isinstance(name, str) and name not in ('bundle-linux', 'bundle-windows'), 'unexpected job name')
            if name.startswith('bundle (ubuntu-24.04, x86_64-unknown-linux-gnu, '):
                name = 'bundle-linux'
            elif name.startswith('bundle (windows-2022, x86_64-pc-windows-msvc, '):
                name = 'bundle-windows'
            require(job['head_sha'] == self.sha and type(job['run_attempt']) is int and job['run_attempt'] == 1
                    and job['status'] == 'completed' and job['conclusion'] == 'success',
                    'incomplete/unsuccessful CI job')
            names.append(name)
        require(Counter(names) == Counter(REQUIRED_JOBS), 'missing, duplicate, or unexpected CI jobs')

    def artifact_metadata(self, artifacts, run_id):
        selected = {}
        seen_ids = set()
        for artifact in artifacts:
            name = artifact['name']
            if name not in FILE_SETS:
                continue
            require(name not in selected, 'duplicate artifact name')
            meta = artifact['workflow_run']
            require(meta['id'] == run_id and meta['repository_id'] == self.repo_id
                    and meta['head_repository_id'] == self.repo_id and meta['head_branch'] == 'main'
                    and meta['head_sha'] == self.sha, 'artifact run provenance did not match')
            artifact_id, size, digest = artifact['id'], artifact['size_in_bytes'], artifact['digest']
            require(positive(artifact_id) and artifact_id not in seen_ids and positive(size) and size <= MAX_BYTES
                    and artifact['expired'] is False and isinstance(digest, str)
                    and re.fullmatch(r'sha256:[0-9a-f]{64}', digest), 'missing/expired/invalid artifact metadata')
            seen_ids.add(artifact_id)
            selected[name] = (artifact_id, size, digest)
        require(selected.keys() == FILE_SETS.keys(), 'release artifacts incomplete')
        return selected


class Actions:
    def __init__(self, repo):
        self.root = f'repos/{repo}/actions/'

    def get(self, path):
        return json.loads(subprocess.check_output(['gh', 'api', self.root + path], timeout=20))

    def list(self, path, key):
        pages = json.loads(subprocess.check_output(
            ['gh', 'api', '--paginate', '--slurp', self.root + path], timeout=30))
        items = [item for page in pages for item in page[key]]
        require(pages and len(items) == pages[0]['total_count'], 'incomplete API listing')
        return items

    def download(self, artifact_id, destination):
        # This command outputs raw ZIP bytes, not parsed or terminal-safe text.
        with destination.open('xb') as archive:
            subprocess.run(['gh', 'api', '--allow-escape-sequences', self.root + f'artifacts/{artifact_id}/zip'],
                           stdout=archive, check=True, timeout=240)


def checked_unpack(zip_path, destination, names, metadata):
    _, size, digest = metadata
    require(zip_path.stat().st_size == size, 'artifact ZIP size did not match')
    with zip_path.open('rb') as data:
        require('sha256:' + hashlib.file_digest(data, 'sha256').hexdigest() == digest,
                'artifact ZIP digest did not match')
    with ZipFile(zip_path) as archive:
        entries = archive.infolist()
        require(Counter(item.filename for item in entries) == Counter(names), 'artifact ZIP contents did not match')
        require(sum(item.file_size for item in entries) <= MAX_BYTES, 'artifact ZIP expands beyond limit')
        destination.mkdir()
        for item in entries:
            kind = stat.S_IFMT(item.external_attr >> 16)
            require(kind in (0, stat.S_IFREG) and item.file_size > 0 and not item.flag_bits & 1,
                    'non-regular, empty, or encrypted artifact member')
            # Names were allowlisted above; write regular files, never extract paths/modes.
            with archive.open(item) as src, (destination / item.filename).open('xb') as dst:
                copyfileobj(src, dst)


def promote(api, source, output):
    require(not output.exists() and not output.is_symlink(), 'destination must not exist')
    runs = api.list(f'workflows/{source.workflow_id}/runs?event=push&branch=main&head_sha={source.sha}&per_page=100',
                    'workflow_runs')
    # Prefer the most recent eligible run, but never merge artifacts across runs.
    for run in sorted(runs, key=lambda run: run['id'], reverse=True):
        run_id = run['id']
        try:
            require(positive(run_id), 'invalid run ID')
            source.check_run(run, run_id)
            source.check_run(api.get(f'runs/{run_id}'), run_id)
            source.check_jobs(api.list(f'runs/{run_id}/attempts/1/jobs?per_page=100', 'jobs'))
            metadata = source.artifact_metadata(api.list(f'runs/{run_id}/artifacts?per_page=100', 'artifacts'), run_id)
            with tempfile.TemporaryDirectory(dir=output.parent) as temp:
                staging = Path(temp) / 'verified'
                staging.mkdir()
                for name, expected in FILE_SETS.items():
                    zip_path = Path(temp) / f'{name}.zip'
                    api.download(metadata[name][0], zip_path)
                    checked_unpack(zip_path, staging / name, expected, metadata[name])
                # Reject a rerun started while listing/downloading, or changed
                # artifacts (e.g. expired/deleted) instead of looking up by name later.
                source.check_run(api.get(f'runs/{run_id}'), run_id)
                require(metadata == source.artifact_metadata(
                    api.list(f'runs/{run_id}/artifacts?per_page=100', 'artifacts'), run_id), 'artifacts changed during transfer')
                source.check_run(api.get(f'runs/{run_id}'), run_id)
                staging.rename(output)
            return run_id
        except (ValueError, KeyError, TypeError, OSError, BadZipFile, RuntimeError, subprocess.SubprocessError) as exc:
            print(f'Main CI run {run_id} ineligible: {exc}', file=sys.stderr)
    raise Ineligible('no verified full main CI run for this SHA')


def main():
    if len(sys.argv) != 2:
        sys.exit('Usage: promote-release-artifacts.py ABSENT_OUTPUT_DIRECTORY')
    repo, sha = os.environ['GITHUB_REPOSITORY'], os.environ['GITHUB_SHA']
    repo_id = int(os.environ['GITHUB_REPOSITORY_ID'])
    output = Path(sys.argv[1])
    try:
        require(re.fullmatch(r'[a-zA-Z0-9_.-]+/[a-zA-Z0-9_.-]+', repo)
                and re.fullmatch(r'[0-9a-f]{40}', sha) and repo_id > 0, 'invalid GitHub identity')
        api = Actions(repo)
        workflow = api.get('workflows/ci.yml')
        require(workflow['path'] == CI_PATH and positive(workflow['id']), 'unexpected CI workflow identity')
        run = promote(api, Source(repo, repo_id, sha, workflow['id']), output)
    except (ValueError, KeyError, TypeError, OSError, subprocess.SubprocessError) as exc:
        print(f'Rebuilding release: {exc}', file=sys.stderr)
        decision = 'promoted=false'
    else:
        print(f'Promoting verified artifacts from main CI run {run}, attempt 1, commit {sha}')
        decision = 'promoted=true'
    with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as step_output:
        print(decision, file=step_output)


if __name__ == '__main__':
    main()
