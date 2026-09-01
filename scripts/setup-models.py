#!/usr/bin/env python3
"""Explicit setup only: download verified runtime/tokenizers; never called from a query."""
import hashlib
import json
import platform
import tarfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def download(url, target, expected):
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists() and hashlib.file_digest(target.open('rb'), 'sha256').hexdigest() == expected:
        return
    temp = target.with_suffix('.part')
    with urllib.request.urlopen(url, timeout=60) as response, temp.open('wb') as output:
        while block := response.read(1024 * 1024):
            output.write(block)
    if hashlib.file_digest(temp.open('rb'), 'sha256').hexdigest() != expected:
        temp.unlink()
        raise SystemExit(f'Checksum mismatch for {target.name}')
    temp.replace(target)

def main():
    lock = json.loads((ROOT / 'config/models.lock.json').read_text())
    for artifact in lock['tokenizers']:
        download(artifact['url'], ROOT / artifact['path'], artifact['sha256'])
        print(f"Verified {artifact['name']} tokenizer")
    if platform.system() == 'Darwin':
        archive = ROOT / '.tools/ollama-darwin.tgz'
        download(lock['ollamaUrl'], archive, lock['ollamaSha256'])
        destination = ROOT / '.tools/ollama'
        destination.mkdir(exist_ok=True)
        with tarfile.open(archive) as package:
            package.extractall(destination, filter='data')
        print('Verified local Ollama runtime')
    else:
        print('Install the Ollama version in config/models.lock.json for your platform.')
    print('Start ./scripts/ollama serve, then pull nomic-embed-text:v1.5 and qwen3:4b with ./scripts/ollama pull.')

if __name__ == '__main__':
    main()
