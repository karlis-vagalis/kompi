"""Exercise the promotion contract, including first-attempt-only provenance."""

import copy
import hashlib
import importlib.util
import io
import stat
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile, ZipInfo

spec = importlib.util.spec_from_file_location('promotion', Path(__file__).with_name('promote-release-artifacts.py'))
promote = importlib.util.module_from_spec(spec)
spec.loader.exec_module(promote)

SHA = 'a' * 40
REPO = {'id': 101, 'full_name': 'example/kompi'}
RUN = 21
WORKFLOW = 31


def zip_bytes(names):
    data = io.BytesIO()
    with ZipFile(data, 'w') as archive:
        for name in names:
            archive.writestr(name, b'expected bytes')
    return data.getvalue()


class Fixture:
    def __init__(self):
        self.run = {'id': RUN, 'workflow_id': WORKFLOW, 'path': promote.CI_PATH,
                    'repository': REPO.copy(), 'head_repository': REPO.copy(), 'event': 'push',
                    'head_branch': 'main', 'head_sha': SHA, 'status': 'completed',
                    'conclusion': 'success', 'run_attempt': 1}
        names = {'bundle-linux': 'bundle (ubuntu-24.04, x86_64-unknown-linux-gnu, sh, tar.gz, ...',
                 'bundle-windows': 'bundle (windows-2022, x86_64-pc-windows-msvc, ps1, zip, ...'}
        self.jobs = [{'name': names.get(name, name), 'head_sha': SHA, 'status': 'completed',
                      'conclusion': 'success', 'run_attempt': 1} for name in promote.REQUIRED_JOBS]
        self.artifacts = []
        self.downloads = {}
        self.downloaded = []
        self.on_download = lambda: None
        for artifact_id, (name, files) in enumerate(promote.FILE_SETS.items(), 1):
            self.artifacts.append({'name': name, 'id': artifact_id, 'expired': False,
                                   'workflow_run': {'id': RUN, 'repository_id': REPO['id'], 'head_branch': 'main',
                                                    'head_repository_id': REPO['id'], 'head_sha': SHA}})
            self.set_zip(artifact_id, zip_bytes(files))

    def set_zip(self, artifact_id, data):
        self.downloads[artifact_id] = data
        self.artifacts[artifact_id - 1].update(size_in_bytes=len(data), digest='sha256:' + hashlib.sha256(data).hexdigest())

    def get(self, path):
        assert path == f'runs/{RUN}'
        return copy.deepcopy(self.run)

    def list(self, path, key):
        expected = {'workflow_runs': f'workflows/{WORKFLOW}/runs?event=push&branch=main&head_sha={SHA}',
                    'jobs': f'runs/{RUN}/attempts/1/jobs?', 'artifacts': f'runs/{RUN}/artifacts?'}
        assert path.startswith(expected[key])
        return copy.deepcopy({'workflow_runs': [self.run], 'jobs': self.jobs, 'artifacts': self.artifacts}[key])

    def download(self, artifact_id, destination):
        self.downloaded.append(artifact_id)
        destination.write_bytes(self.downloads[artifact_id])
        self.on_download()


class Promotion(unittest.TestCase):
    def execute(self, api, *, success=False):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / 'verified'
            source = promote.Source(REPO['full_name'], REPO['id'], SHA, WORKFLOW)
            if success:
                self.assertEqual(promote.promote(api, source, output), RUN)
                self.assertEqual({str(path.relative_to(output)): path.read_bytes() for path in output.glob('*/*')},
                                 {f'{name}/{file}': b'expected bytes'
                                  for name, files in promote.FILE_SETS.items() for file in files})
            else:
                with self.assertRaises(promote.Ineligible):
                    promote.promote(api, source, output)
                self.assertEqual(list(root.iterdir()), [], 'fallback must not leave any files to upload')

    def test_complete_trusted_run(self):
        self.execute(Fixture(), success=True)

    def test_reject_invalid_run_provenance_before_download(self):
        cases = {'event': 'pull_request', 'head_branch': 'feature', 'head_sha': 'b' * 40,
                 'repository': {'id': 102, 'full_name': REPO['full_name']}, 'head_repository': {'id': 102},
                 'path': '.github/workflows/release.yml', 'workflow_id': 999,
                 'status': 'in_progress', 'conclusion': 'failure', 'run_attempt': 2}
        for key, value in cases.items():
            with self.subTest(key=key):
                api = Fixture()
                api.run[key] = value
                self.execute(api)
                self.assertEqual(api.downloaded, [])

    def test_reject_skipped_cancelled_missing_or_wrong_attempt_jobs(self):
        for name, change in [('host', {'conclusion': 'skipped'}), ('CI ready', {'conclusion': 'cancelled'}),
                             ('android', {'run_attempt': 2}), ('sources', {'head_sha': 'b' * 40})]:
            with self.subTest(name=name):
                api = Fixture()
                next(job for job in api.jobs if job['name'] == name).update(change)
                self.execute(api)
                self.assertEqual(api.downloaded, [])
        api = Fixture()
        api.jobs = [job for job in api.jobs if not job['name'].startswith('bundle (windows')]
        self.execute(api)

    def test_reject_ineligible_artifact_metadata_before_download(self):
        for mutation in (lambda a: a.pop(), lambda a: a.append(copy.deepcopy(a[0])),
                         lambda a: a[0].update(expired=True), lambda a: a[0].update(digest=None),
                         lambda a: a[0]['workflow_run'].update(id=RUN + 1),
                         lambda a: a[0]['workflow_run'].update(head_repository_id=123)):
            api = Fixture()
            mutation(api.artifacts)
            self.execute(api)
            self.assertEqual(api.downloaded, [])

    def test_reject_bad_digest_even_when_size_matches(self):
        api = Fixture()
        api.artifacts[0]['digest'] = 'sha256:' + '0' * 64
        self.execute(api)

    def test_reject_bad_archive_even_when_digest_matches(self):
        names = list(promote.FILE_SETS['unsigned-apk'])
        link = ZipInfo(names[0])
        link.create_system = 3
        link.external_attr = (stat.S_IFLNK | 0o777) << 16
        for bad in ([names[0]], [*names, '../extra'], [link, names[1]]):
            with self.subTest(bad=bad):
                api = Fixture()
                api.set_zip(1, zip_bytes(bad))
                self.execute(api)

    def test_rerun_or_artifact_replacement_during_transfer_discards_everything(self):
        api = Fixture()
        api.on_download = lambda: api.run.update(run_attempt=2)
        self.execute(api)
        api = Fixture()
        api.on_download = lambda: api.artifacts[0].update(id=10)
        self.execute(api)


if __name__ == '__main__':
    unittest.main()
