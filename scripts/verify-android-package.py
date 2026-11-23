"""Verify both ABI payloads and their notices in the APK that will be signed."""
from hashlib import sha256
from pathlib import Path
from sys import argv
from zipfile import ZipFile

if len(argv) != 2:
    raise SystemExit('usage: python3 scripts/verify-android-package.py RELEASE.apk')
root = Path(__file__).resolve().parents[1]
staged = root / 'android/.native-build'


def require_equal(name, actual, expected):
    if actual != expected:
        raise SystemExit(f'{name}: packaged content differs from verified input')
    print(f'{sha256(actual).hexdigest()}  {name}')


with ZipFile(argv[1]) as apk, ZipFile(staged / 'onnxruntime.aar') as ort:
    for name in apk.namelist():
        if name.startswith('lib/') and any(engine in name.lower() for engine in ('whistle', 'needle')):
            raise SystemExit(f'unavailable speech engine in APK: {name}')
    for abi in ('arm64-v8a', 'x86_64'):
        name = f'lib/{abi}/libiroh_ffi.so'
        require_equal(name, apk.read(name), (staged / 'jni' / abi / 'libiroh_ffi.so').read_bytes())
        for lib in ('libonnxruntime.so', 'libonnxruntime4j_jni.so'):
            name = f'lib/{abi}/{lib}'
            require_equal(name, apk.read(name), ort.read(f'jni/{abi}/{lib}'))
    for bad in ('.dylib', '.dll'):
        if any(n.lower().endswith(bad) for n in apk.namelist()):
            raise SystemExit(f'unexpected desktop {bad} in Android package')
    for tree in (root / 'android/app/src/main/assets/licenses', staged / 'assets/licenses'):
        for file in sorted(tree.rglob('*')):
            if file.is_file():
                name = 'assets/licenses/' + file.relative_to(tree).as_posix()
                require_equal(name, apk.read(name), file.read_bytes())
