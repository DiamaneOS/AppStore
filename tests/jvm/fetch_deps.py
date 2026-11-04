"""Downloads the pinned test dependencies of deps.lock into a directory and checks their SHA-256."""
import hashlib
import sys
import urllib.request
import zipfile
from pathlib import Path

LOCK = Path(__file__).with_name('deps.lock')


def entries():
    for line in LOCK.read_text().splitlines():
        if line and not line.startswith('#'):
            name, digest, url = line.split()
            yield name, digest, url


def check(path, digest):
    return path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest() == digest


def fetch(target):
    target.mkdir(parents=True, exist_ok=True)
    for name, digest, url in entries():
        path = target / name
        if not check(path, digest):
            with urllib.request.urlopen(url) as r:
                path.write_bytes(r.read())
            if not check(path, digest):
                path.unlink()
                raise RuntimeError(f'{name}: SHA-256 mismatch')
        if name.endswith('.zip') and not (target / 'kotlinc').is_dir():
            with zipfile.ZipFile(path) as z:
                z.extractall(target)
            for f in (target / 'kotlinc/bin').iterdir():
                f.chmod(0o755)


if __name__ == '__main__':
    fetch(Path(sys.argv[1]))
