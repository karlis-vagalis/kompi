"""Build and smoke-test a native cargo-dist archive. No publishing or signing."""
import argparse
from functools import partial
import hashlib
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
from threading import Thread
import time
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
    # Pi loads extensions (and its Windows preload) by URL relative to the worker.
    # Build these as standalone ESM entry points in the same directory.
    entries = ['worker', *('conversations/' + name for name in (
        'attachment-order', 'android-tools', 'follow-up-boundary', 'transcript-owner', 'windows-pi-preload'))]
    dest = worker / 'dist-worker/daemon/src'
    for entry in entries:
        run('bun', 'build', '--target=node', '--format=esm', '--outfile', dest / (Path(entry).name + '.js'),
            f'src/{entry}.ts', cwd=daemon)
    for package, license in [('zod', 'LICENSE'), ('typebox', 'license')]:
        shutil.copyfile(daemon / 'node_modules' / package / license, worker / f'{package}-LICENSE')
    for file in ('package.json', 'bun.lock'):
        shutil.copyfile(daemon / file, worker / file)
    run('cargo', 'metadata', '--locked', '--format-version=1', stdout=subprocess.DEVNULL)
    tag = 'v' + tomllib.loads((ROOT / 'rust/kompi-daemon/Cargo.toml').read_text())['package']['version']
    output = ROOT / 'target/distrib'
    output.mkdir(parents=True, exist_ok=True)
    manifest = output / 'native-dist-manifest.json'
    manifest.unlink(missing_ok=True)
    built = run('dist', 'build', '--artifacts=local', '--target', host, '--tag', tag, '--output-format=json',
                stdout=subprocess.PIPE, env={**os.environ, 'KOMPI_PACKAGE_INPUTS': str(INPUT.resolve())})
    manifest.write_bytes(built.stdout)
    run('dist', 'build', '--artifacts=global', '--tag', tag)
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
        smoke_worker(package / 'worker')
    with tempfile.TemporaryDirectory() as temp:
        smoke_installer(Path(temp), archive, tag)
    print(f'Verified {archive}: {digest}')


def smoke_installer(fixture, archive, tag):
    windows = os.name == 'nt'
    installer = archive.parent / ('kompi-daemon-installer.ps1' if windows else 'kompi-daemon-installer.sh')
    if f'https://github.com/karlis-vagalis/kompi/releases/download/{tag}' not in installer.read_text():
        raise RuntimeError('installer does not target the expected release')
    assets = fixture / 'release'
    assets.mkdir()
    shutil.copyfile(archive, assets / archive.name)
    prefix = fixture / 'installed'
    cmd = ['pwsh', '-NoProfile', '-File'] if windows else ['sh']
    env = {k: v for k, v in os.environ.items() if k not in ('GITHUB_PATH', 'KOMPI_DAEMON_INSTALL_DIR', 'CARGO_DIST_FORCE_INSTALL_DIR')}
    with ThreadingHTTPServer(('127.0.0.1', 0), partial(SimpleHTTPRequestHandler, directory=assets)) as server:
        thread = Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            env.update(KOMPI_DAEMON_UNMANAGED_INSTALL=str(prefix), KOMPI_DAEMON_DOWNLOAD_URL=f'http://127.0.0.1:{server.server_port}')
            for _ in range(2):
                run(*cmd, installer, cwd=fixture, env=env, timeout=120)
                exe = prefix / ('kompi.exe' if windows else 'kompi')
                run(exe, '--version', cwd=fixture)
                smoke_serve(exe, fixture)
                worker, = prefix.glob('kompi-runtime-*/worker')
                if not worker.is_absolute() or not worker.resolve().is_relative_to(prefix.resolve()):
                    raise RuntimeError('setup-worker did not provision the installed runtime')
                for source, installed in [(ROOT / 'LICENSE', worker.parent / 'LICENSE'), (INPUT / 'notices.tar.gz', worker.parent / 'notices.tar.gz')]:
                    if installed.read_bytes() != source.read_bytes():
                        raise RuntimeError(f'installed {installed.name} does not match the package input')
                smoke_worker(worker)
        finally:
            server.shutdown()
            thread.join()


def smoke_serve(exe, fixture):
    args = [exe, '--state-dir', fixture / 'state']
    env = {k: v for k, v in os.environ.items() if not k.startswith('KOMPI_')}
    with subprocess.Popen([*args, 'serve'], cwd=fixture, env=env) as daemon:
        try:
            deadline = time.monotonic() + 30
            while daemon.poll() is None and time.monotonic() < deadline:
                status = subprocess.run([*args, 'status', '--json'], cwd=fixture, env=env,
                                        capture_output=True, timeout=10)
                if status.returncode == 0 and json.loads(status.stdout)['running']:
                    return
                time.sleep(0.25)
            raise RuntimeError('installed daemon did not become ready')
        finally:
            daemon.kill()


def smoke_worker(worker):
    if (worker / 'node_modules').exists():
        raise RuntimeError('worker smoke requires a clean install')
    # Verify all Pi-loaded modules resolve without the source checkout or SDK.
    directory = worker / 'dist-worker/daemon/src'
    for module in ('attachment-order', 'android-tools', 'follow-up-boundary', 'transcript-owner'):
        run('node', '--input-type=module', '-e', f"await import('./{module}.js')", cwd=directory)
    # A real round trip without opening a Pi session or using model credentials.
    request_id = '6031d25f-0da2-43dd-9d2c-152d66b4438a'
    conversation = '0bba8946-46a5-48e0-9ba5-28dd3c15620f'
    request = {'v': 4, 'id': request_id, 'message': {'type': 'snapshot', 'payload': {'conversationId': conversation}}}
    response = run('bun', '--no-install', 'dist-worker/daemon/src/worker.js', cwd=worker, text=True,
                   input=json.dumps(request) + '\n', stdout=subprocess.PIPE, timeout=30)
    reply = json.loads(response.stdout)
    if (reply['id'], reply['message']['type'], reply['message']['payload']['code']) != (request_id, 'error', 'session-not-found'):
        raise RuntimeError('packaged worker did not answer the snapshot request')


if __name__ == '__main__':
    main()
