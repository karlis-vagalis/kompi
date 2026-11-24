import unittest
from unittest.mock import patch

import android_release as release


class AndroidReleaseTest(unittest.TestCase):
    def setUp(self):
        self.current = release.Version.from_gradle((release.ROOT / release.GRADLE).read_text())

    def badging(self, version, abi='arm64-v8a'):
        return (
            f"package: name='com.kompi.app' versionCode='{version.code}' "
            f"versionName='{version.name}' platformBuildVersionName='16'\n"
            f"native-code: '{abi}'\n"
        )

    def test_actual_release_manifest_must_follow_source_not_old_signer_constant(self):
        for abi in release.ABIS:
            with self.subTest(abi=abi):
                release.check_badging(self.badging(self.current, abi), self.current, abi)
                for wrong in (release.Version(self.current.name, 3), release.Version('0.1.2', self.current.code)):
                    with self.assertRaisesRegex(ValueError, 'APK manifest'):
                        release.check_badging(self.badging(wrong, abi), self.current, abi)
        with self.assertRaisesRegex(ValueError, 'APK must contain only'):
            release.check_badging(self.badging(self.current, 'x86_64'), self.current, 'arm64-v8a')

    def test_release_increases_both_version_fields(self):
        previous = release.Version('0.1.3', 4)
        release.check_upgrade(release.Version('0.1.4', 5), previous)
        for wrong in (release.Version('0.1.4', 4), release.Version('0.1.3', 5), release.Version('0.1.2', 10)):
            with self.subTest(wrong=wrong), self.assertRaisesRegex(ValueError, 'must increase both'):
                release.check_upgrade(wrong, previous)

    def test_tag_matches_declared_version_and_comparison_skips_same_tag_only(self):
        tag = f'v{self.current.name}'
        with self.assertRaisesRegex(ValueError, 'does not match Android versionName'):
            release.check_release(self.current, 'v0.0.0')
        with patch.object(release, 'git', side_effect=[f'{tag}\nv0.1.0\n', 'versionName = "0.1.0"\nversionCode = 1\n']) as git:
            release.check_release(self.current, tag)
            self.assertEqual(git.call_count, 2)

    def test_duplicate_gradle_assignments_fail_closed(self):
        source = 'versionName = "0.1.3"\nversionCode = 4\nversionCode = 3\n'
        with self.assertRaisesRegex(ValueError, 'one literal versionCode'):
            release.Version.from_gradle(source)
