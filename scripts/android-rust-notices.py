"""Archive license material from the exact registry and local sources resolved by Cargo."""
import json
import sys
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

metadata, output = map(Path, sys.argv[1:])
packages = json.loads(metadata.read_text())['packages']
with ZipFile(output, 'w') as archive:
    def add(name, data):
        archive.writestr(ZipInfo(name), data, compress_type=ZIP_DEFLATED)

    index = ['crate\tversion\tdeclared license\tsource']
    for package in sorted(packages, key=lambda p: (p['name'], p['version'])):
        name, version = package['name'], package['version']
        source = Path(package['manifest_path']).parent
        index.append('\t'.join([name, version, package['license'] or '',
                                package['source'] or f'third-party/{name}']))
        # Some native dependencies keep their notices below vendor/ or in source headers.
        # Keep the entire locked crate source available in the release source archive too.
        for path in sorted(source.rglob('*')):
            relative = path.relative_to(source)
            if 'target' in relative.parts or '.git' in relative.parts or path.is_symlink():
                continue
            if path.is_file() and (path.name.lower().startswith((
                'license', 'licence', 'notice', 'third_party', 'third-party', 'thirdparty',
                'copyright', 'authors', 'copying')) or str(path) == package.get('license_file')):
                add(f'{name}-{version}/{relative.as_posix()}', path.read_bytes())
    add('packages.tsv', ('\n'.join(index) + '\n').encode())
