"""Exercise routing against actual Git events, and the gate against job results."""

import importlib.util
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('ci_plan', Path(__file__).with_name('ci-plan.py'))
ci = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ci)

HOST = {'host', 'sources', 'bundle'}
ANDROID = {'android', 'reply-recovery-emulator'}
ALL = HOST | ANDROID | {'protocol-regeneration', 'prek'}
PLAN = Path(__file__).resolve().with_name('ci-plan.py')


class Routing(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        cwd = os.getcwd()
        os.chdir(directory.name)
        self.addCleanup(os.chdir, cwd)
        self.git('init', '-q', '-b', 'main')
        self.git('config', 'user.name', 'CI Test')
        self.git('config', 'user.email', 'test@example.invalid')
        self.git('config', 'commit.gpgsign', 'false')
        self.base = self.commit({'README.md': 'initial', 'daemon/src/worker.ts': 'initial'})

    def git(self, *args):
        return ci.git(*args).decode().strip()

    def commit(self, files):
        for name, content in files.items():
            path = Path(name)
            if content is None:
                path.unlink()
            else:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content)
        self.git('add', '-A')
        self.git('commit', '-qm', 'fixture')
        return self.git('rev-parse', 'HEAD')

    def push(self, before=None, after=None, *, package=False):
        return ci.select('push', 'refs/heads/main', package,
                         {'before': before or self.base, 'after': after or self.git('rev-parse', 'HEAD')})

    def pull(self, files, *, base_changes=None):
        self.git('checkout', '-qb', 'topic')
        topic = self.commit(files)
        self.git('checkout', '-q', 'main')
        base = self.commit(base_changes) if base_changes else self.base
        self.git('merge', '-q', '--no-ff', 'topic')
        return ci.select('pull_request', 'refs/pull/42/merge', False,
                         {'pull_request': {'head': {'sha': topic}, 'base': {'sha': base}}})

    def test_pr_android_ignores_already_landed_host_change(self):
        self.assertEqual(self.pull({'android/app/src/main/java/Test.kt': 'app'},
                                   base_changes={'daemon/src/worker.ts': 'new base'}), (ANDROID, 'downloads'))

    def test_pr_host_only(self):
        self.assertEqual(self.pull({'rust/kompi-daemon/src/main.rs': 'host'}), (HOST, 'downloads'))

    def test_pr_union_including_prek_and_docs(self):
        self.assertEqual(self.pull({'android/app/src/test/java/test.kt': '', 'daemon/test/foo.ts': '',
                                    'prek.toml': '', 'docs/releasing.md': ''}), (ANDROID | HOST | {'prek'}, 'downloads'))

    def test_docs_only(self):
        self.assertEqual(self.pull({'docs/releasing.md': '', 'android/dependencies.md': ''}), (set(), 'downloads'))
        self.assertEqual(self.push(), (set(), 'main'))

    def test_prek_only_and_then_deletion(self):
        head = self.commit({'prek.toml': ''})
        self.assertEqual(self.push(), ({'prek'}, 'main'))
        self.commit({'prek.toml': None})
        self.assertEqual(self.push(head), ({'prek'}, 'main'))

    def test_rename_out_of_source_tree_considers_both_paths(self):
        self.assertEqual(self.pull({'daemon/src/worker.ts': None, 'docs/worker.md': 'initial'}), (HOST, 'downloads'))

    def test_main_multicommit_push_keeps_artifact_consumers(self):
        self.commit({'daemon/src/worker.ts': 'changed'})
        self.commit({'README.md': 'docs followup'})
        self.assertEqual(self.push(), (HOST | ANDROID, 'main'))

    def test_unknown_names_are_not_split_by_newlines(self):
        self.assertEqual(self.pull({'docs/releasing.md\nnew-input': ''}), (ALL, 'downloads'))

    def test_shared_and_build_inputs_fan_out(self):
        for path in ('Cargo.lock', 'android/app/build.gradle.kts', 'daemon/package.json',
                     'rust/kompi-daemon/build.rs', 'justfile', '.github/workflows/release.yml',
                     'scripts/android-rust-notices.py', 'pi-extension/src/protocol/generated.ts',
                     'android/app/src/main/java/com/kompi/app/protocol/generated/Protocol.kt',
                     'third-party/postcard/LICENSE-APACHE', 'protocol/test-vectors/new.json'):
            with self.subTest(path=path):
                self.assertEqual(ci.classify(path), ALL)

    def test_missing_or_unverifiable_boundaries_force_full_ci(self):
        self.commit({'README.md': 'docs'})
        for before, after in [('0' * 40, None), ('d' * 40, None), (None, 'd' * 40)]:
            with self.subTest(before=before, after=after):
                self.assertEqual(self.push(before, after), (ALL, 'main'))
        self.assertEqual(ci.select('pull_request', 'refs/pull/42/merge', False,
                                   {'pull_request': {'base': {'sha': self.base},
                                                     'head': {'sha': self.git('rev-parse', 'HEAD')}}}), (ALL, 'downloads'))

    def test_tag_and_reusable_packaging_never_use_caches(self):
        self.commit({'README.md': 'docs'})
        self.assertEqual(self.push(package=True), (ALL, 'off'))
        self.assertEqual(ci.select('push', 'refs/tags/v0.9.0', False, {}), (ALL, 'off'))
        self.assertEqual(ci.select('workflow_call', 'refs/heads/main', True, {}), (ALL, 'off'))


class Gate(unittest.TestCase):
    def gate(self, selected, *, overrides=None, planner='success', omit=None):
        planned = {job: job in selected for job in ALL}
        needs = {job: {'result': 'success' if run else 'skipped'} for job, run in planned.items()}
        needs['plan'] = {'result': planner, 'outputs': {'jobs': json.dumps(planned)}}
        for job, result in (overrides or {}).items():
            needs[job]['result'] = result
        if omit:
            del needs[omit]
        return subprocess.run(['python3', PLAN, 'gate'], env={**os.environ, 'CI_NEEDS': json.dumps(needs)},
                              capture_output=True, text=True)

    def test_docs_only_and_successful_matrix(self):
        for selected in (set(), {'prek'}, HOST, ALL):
            with self.subTest(selected=selected):
                result = self.gate(selected)
                self.assertEqual(result.returncode, 0, result.stderr)

    def test_matrix_failure_cancellation_and_unexpected_skip(self):
        # GitHub exposes one aggregate result for both matrix legs.
        for result in ('failure', 'cancelled', 'skipped'):
            self.assertNotEqual(self.gate(HOST, overrides={'bundle': result}).returncode, 0)
        self.assertNotEqual(self.gate(ANDROID, overrides={'reply-recovery-emulator': 'skipped'}).returncode, 0)

    def test_failed_plan_and_incomplete_results_cannot_pass(self):
        self.assertNotEqual(self.gate(set(), planner='cancelled').returncode, 0)
        self.assertNotEqual(self.gate(set(), omit='sources').returncode, 0)
        self.assertNotEqual(self.gate(set(), overrides={'android': 'failure'}).returncode, 0)


if __name__ == '__main__':
    unittest.main()
