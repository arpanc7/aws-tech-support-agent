#!/usr/bin/env python3
"""Install checksum-verified, project-local macOS ARM64 build tools. No global changes."""
from pathlib import Path
import hashlib
import platform
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / '.tools'
PACKAGES = [
    ('jdk.tar.gz', 'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.12.1_1.tar.gz',
     'sha256', '3623232f33a9c3baadf304480b2535f9a3cba8a58d42ecbb438ba267315d9998'),
    ('maven.tar.gz', 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.tar.gz',
     'sha512', 'bcfe4fe305c962ace56ac7b5fc7a08b87d5abd8b7e89027ab251069faebee516b0ded8961445d6d91ec1985dfe30f8153268843c89aa392733d1a3ec956c9978'),
]

def main():
    if (platform.system(), platform.machine()) != ('Darwin', 'arm64'):
        raise SystemExit('This convenience bootstrap targets macOS ARM64. Install JDK 21 and Maven 3.9.11 on other platforms.')
    TOOLS.mkdir(exist_ok=True)
    for name, url, algorithm, expected in PACKAGES:
        archive = TOOLS / name
        if not archive.exists():
            print(f'Downloading {name}', flush=True)
            with urllib.request.urlopen(url, timeout=60) as response, archive.with_suffix('.part').open('wb') as output:
                while block := response.read(1024 * 1024):
                    output.write(block)
            archive.with_suffix('.part').replace(archive)
        actual = hashlib.file_digest(archive.open('rb'), algorithm).hexdigest()
        if actual != expected:
            raise SystemExit(f'Checksum mismatch: {archive}. Remove the file and retry.')
        with tarfile.open(archive) as package:
            package.extractall(TOOLS, filter='data')
    jdk = next(TOOLS.glob('jdk-*/Contents/Home'))
    link = TOOLS / 'jdk'
    if not link.exists():
        link.symlink_to(jdk.relative_to(TOOLS))
    print('Build tools ready. Use ./scripts/maven for project-local Java and Maven.', flush=True)

if __name__ == '__main__':
    main()
