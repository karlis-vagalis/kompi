"""Verify a standalone release APK against the expected ABI and staged inputs."""
from argparse import ArgumentParser
from collections import Counter
from hashlib import sha256
from pathlib import Path
from zipfile import ZipFile

parser = ArgumentParser(description=__doc__)
parser.add_argument('apk', type=Path)
parser.add_argument('abi', choices=('arm64-v8a', 'x86_64'))
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
staged = root / 'android/.native-build'
libs = {
    'libandroidx.graphics.path.so', 'libdatastore_shared_counter.so',
    'libimage_processing_util_jni.so', 'libiroh_ffi.so', 'libjnidispatch.so',
    'libmediapipe_tasks_audio_jni.so', 'libmediapipe_tasks_jni.so',
    'libonnxruntime.so', 'libonnxruntime4j_jni.so', 'libsurface_util_jni.so',
}


def require_equal(name, actual, expected):
    if actual != expected:
        raise SystemExit(f'{name}: packaged content differs from verified input')
    print(f'{sha256(actual).hexdigest()}  {name}')


with ZipFile(args.apk) as apk, ZipFile(staged / 'onnxruntime.aar') as ort:
    actual = Counter(n for n in apk.namelist() if n.startswith('lib/') and not n.endswith('/'))
    expected = Counter(f'lib/{args.abi}/{lib}' for lib in libs)
    if actual != expected:
        raise SystemExit(f'unexpected native entries: {list((actual - expected).elements())}; '
                         f'missing: {list((expected - actual).elements())}')
    name = f'lib/{args.abi}/libiroh_ffi.so'
    require_equal(name, apk.read(name), (staged / 'jni' / args.abi / 'libiroh_ffi.so').read_bytes())
    for lib in ('libonnxruntime.so', 'libonnxruntime4j_jni.so'):
        name = f'lib/{args.abi}/{lib}'
        require_equal(name, apk.read(name), ort.read(f'jni/{args.abi}/{lib}'))
    for bad in ('.dylib', '.dll'):
        if any(n.lower().endswith(bad) for n in apk.namelist()):
            raise SystemExit(f'unexpected desktop {bad} in Android package')
    for tree in (root / 'android/app/src/main/assets', staged / 'assets'):
        for file in sorted(tree.rglob('*')):
            if file.is_file():
                name = 'assets/' + file.relative_to(tree).as_posix()
                require_equal(name, apk.read(name), file.read_bytes())
