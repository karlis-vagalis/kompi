"""Check release versions and verify the manifest of a built Android APK."""

import argparse
import re
import subprocess
import tomllib
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GRADLE = 'android/app/build.gradle.kts'
PACKAGE = 'com.kompi.app'
SEMVER = r'(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)'
ABIS = ('arm64-v8a', 'x86_64')


@dataclass(frozen=True)
class Version:
    name: str
    code: int

    def __post_init__(self):
        if not re.fullmatch(SEMVER, self.name) or not 1 <= self.code <= 2_100_000_000:
            raise ValueError(f'invalid release version: {self.name} (code {self.code})')

    @property
    def semver(self):
        return tuple(map(int, self.name.split('.')))

    @classmethod
    def from_gradle(cls, source):
        def literal(key, pattern):
            lines = re.findall(rf'(?m)^\s*{key}\s*=([^\n]*)$', source)
            match = re.fullmatch(pattern, lines[0].strip()) if len(lines) == 1 else None
            if match is None:
                raise ValueError(f'expected one literal {key} assignment in {GRADLE}')
            return match[1]

        return cls(literal('versionName', r'"([^"\n]+)"'), int(literal('versionCode', r'([1-9][0-9]*)')))


def check_upgrade(current, previous):
    if current.semver <= previous.semver or current.code <= previous.code:
        raise ValueError(f'release must increase both versionName and versionCode: {previous} -> {current}')


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT, text=True)


def check_release(current, tag):
    if tag is not None and tag != f'v{current.name}':
        raise ValueError(f'tag {tag} does not match Android versionName {current.name}')
    for crate in ('kompi-daemon', 'kompi-protocol'):
        manifest = tomllib.loads((ROOT / f'rust/{crate}/Cargo.toml').read_text())
        if manifest['package']['version'] != current.name:
            raise ValueError(f'{crate} version does not match Android versionName {current.name}')
    for old_tag in git('tag', '--merged', 'HEAD', '--list', 'v*').splitlines():
        if old_tag == tag or not re.fullmatch(f'v{SEMVER}', old_tag):
            continue
        previous = Version.from_gradle(git('show', f'{old_tag}:{GRADLE}'))
        if old_tag != f'v{previous.name}':
            raise ValueError(f'{old_tag} does not match its tagged Android versionName')
        if tag is None and current == previous:
            continue  # Ordinary CI may build an already tagged version unchanged.
        check_upgrade(current, previous)


def check_badging(badging, expected, abi):
    packages = [line for line in badging.splitlines() if line.startswith('package:')]
    native = [line for line in badging.splitlines() if line.startswith('native-code:')]
    prefix = f"package: name='{PACKAGE}' versionCode='{expected.code}' versionName='{expected.name}'"
    if len(packages) != 1 or not (packages[0] == prefix or packages[0].startswith(prefix + ' ')):
        raise ValueError(f'APK manifest does not match {PACKAGE} {expected.name} (code {expected.code}): {packages}')
    if native != [f"native-code: '{abi}'"]:
        raise ValueError(f'APK must contain only {abi}: {native}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_subparsers(dest='mode', required=True)
    modes.add_parser('check').add_argument('--tag')
    apk = modes.add_parser('verify-apk')
    apk.add_argument('aapt', type=Path)
    apk.add_argument('apk', type=Path)
    apk.add_argument('abi', choices=ABIS)
    args = parser.parse_args()

    try:
        version = Version.from_gradle((ROOT / GRADLE).read_text())
        if args.mode == 'check':
            check_release(version, args.tag)
        else:
            badging = subprocess.check_output([args.aapt, 'dump', 'badging', args.apk], text=True)
            check_badging(badging, version, args.abi)
        print(f'Verified Android release metadata: {version.name} (code {version.code})')
    except (ValueError, subprocess.CalledProcessError) as error:
        parser.exit(1, f'{error}\n')


if __name__ == '__main__':
    main()
