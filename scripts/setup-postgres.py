#!/usr/bin/env python3
"""Explicit optional native pgvector setup; never changes global application settings."""
import hashlib
import platform
import shutil
import subprocess
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
URL = 'https://github.com/PostgresApp/PostgresApp/releases/download/v2.9.6/Postgres-2.9.6-17.dmg'
SHA256 = 'b38bb00b8c8702a568270aab85995c550f7f93d1503b818efdc5ff9a519b7168'

def main():
    if platform.system() != 'Darwin':
        raise SystemExit('Use Docker Compose on other platforms.')
    tools = ROOT / '.tools'
    tools.mkdir(exist_ok=True)
    archive = tools / 'postgres.dmg'
    if not archive.exists():
        with urllib.request.urlopen(URL, timeout=60) as response, archive.with_suffix('.part').open('wb') as output:
            shutil.copyfileobj(response, output)
        archive.with_suffix('.part').replace(archive)
    with archive.open('rb') as source:
        if hashlib.file_digest(source, 'sha256').hexdigest() != SHA256:
            raise SystemExit('Postgres.app checksum mismatch. Remove the download and retry.')
    destination = tools / 'Postgres.app'
    if destination.exists():
        print('Project-local Postgres.app already exists; no files were replaced.')
        return
    mount = tools / 'postgres-installer'
    mount.mkdir(exist_ok=True)
    subprocess.run(['hdiutil','attach','-readonly','-nobrowse','-mountpoint',str(mount),str(archive)], check=True)
    try:
        shutil.copytree(mount / 'Postgres.app', destination, symlinks=True)
    finally:
        subprocess.run(['hdiutil','detach',str(mount)], check=True)
        mount.rmdir()
    print('Native PostgreSQL 17 with pgvector is ready. Run ./scripts/postgres-native start.')

if __name__ == '__main__':
    main()
