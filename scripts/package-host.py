"""Build and smoke-test a native cargo-dist archive. No publishing or signing."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import tomllib

ROOT = Path(__file__).resolve().parents[1]
INPUT = ROOT / 'target/package-inputs'


def run(*args, cwd=ROOT, **kwargs):
    return subprocess.run(args, cwd=cwd, check=True, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.parse_args()
    host = next(line.removeprefix('host: ') for line in run('rustc', '-vV', capture_output=True, text=True).stdout.splitlines() if line.startswith('host: '))
    config = tomllib.loads((ROOT / 'dist-workspace.toml').read_text())['dist']
    if host not in config['targets']:
        parser.error(f'unsupported native target: {host}')
    if run('dist', '--version', capture_output=True, text=True).stdout.strip().split()[-1] != config['cargo-dist-version']:
        parser.error('install cargo-dist ' + config['cargo-dist-version'])
    if not (INPUT / 'notices.tar.gz').is_file():
        parser.error('prepare the matching notices with scripts/prepare-sources.sh first')
    daemon = ROOT / 'daemon'
    # The shared protocol sources resolve their imports from pi-extension.
    run('bun', 'install', '--frozen-lockfile', cwd=ROOT / 'pi-extension')
    run('bun', 'install', '--frozen-lockfile', cwd=daemon)
    run('bun', 'run', 'build', cwd=daemon)
    worker = INPUT / 'worker'
    if worker.exists():
        shutil.rmtree(worker)
    shutil.copytree(daemon / 'dist-worker', worker / 'dist-worker')
    for file in ('package.json', 'bun.lock'):
        shutil.copyfile(daemon / file, worker / file)
    run('cargo', 'metadata', '--locked', '--format-version=1', stdout=subprocess.DEVNULL)
    run('dist', 'build', '--artifacts=local', '--target', host)
    suffix = '.zip' if os.name == 'nt' else '.tar.gz'
    archive = ROOT / 'target/distrib' / f'kompi-daemon-{host}{suffix}'
    with archive.open('rb') as src:
        digest = hashlib.file_digest(src, 'sha256').hexdigest()
    if digest != Path(str(archive) + '.sha256').read_text().split()[0]:
        raise RuntimeError('archive checksum mismatch')
    with tempfile.TemporaryDirectory() as temp:
        shutil.unpack_archive(archive, temp)
        exe, = Path(temp).rglob('kompi.exe' if os.name == 'nt' else 'kompi')
        package = exe.parent
        for file in ('LICENSE', 'notices.tar.gz'):
            if not (package / file).stat().st_size:
                raise RuntimeError(f'empty {file}')
        version = run(exe, '--version', cwd=package, capture_output=True, text=True).stdout.strip()
        if os.environ.get('GITHUB_REF_TYPE') == 'tag' and 'v' + version.split()[-1] != os.environ['GITHUB_REF_NAME']:
            raise RuntimeError(f'tag does not match packaged {version}')
        run('bun', 'install', '--production', '--frozen-lockfile', cwd=package / 'worker')
        # A real round trip without opening a Pi session or using model credentials.
        request_id = '6031d25f-0da2-43dd-9d2c-152d66b4438a'
        conversation = '0bba8946-46a5-48e0-9ba5-28dd3c15620f'
        request = {'v': 4, 'id': request_id, 'message': {'type': 'snapshot', 'payload': {'conversationId': conversation}}}
        response = run('bun', 'run', 'worker', cwd=package / 'worker', text=True,
                       input=json.dumps(request) + '\n', stdout=subprocess.PIPE, timeout=30)
        reply = json.loads(response.stdout)
        if (reply['id'], reply['message']['type'], reply['message']['payload']['code']) != (request_id, 'error', 'session-not-found'):
            raise RuntimeError('packaged worker did not answer the snapshot request')
    print(f'Verified {archive}: {digest}')


if __name__ == '__main__':
    main()
