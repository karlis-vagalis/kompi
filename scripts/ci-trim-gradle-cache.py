"""Keep at most 350 MiB of recent Gradle task cache entries on the CI runner."""

from pathlib import Path

cache = Path.home() / '.gradle/caches/build-cache-1'
limit = 350 * 1024 * 1024
entries = [(entry.stat(), entry) for entry in cache.iterdir()
           if entry.is_file() and not entry.is_symlink() and not entry.name.endswith('.lock')] if cache.is_dir() else []
size = sum(info.st_size for info, _ in entries)
for info, entry in sorted(entries, key=lambda item: item[0].st_mtime_ns):
    if size <= limit:
        break
    entry.unlink()
    size -= info.st_size
print(f'Gradle task cache size: {size // (1024 * 1024)} MiB')
