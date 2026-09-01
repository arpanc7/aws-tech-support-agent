#!/usr/bin/env python3
"""Exercise real local models. Smoke expectations are not a hallucination benchmark."""
import argparse
import http.cookiejar
import json
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--base-url', default='http://127.0.0.1:8080')
    parser.add_argument('--output', default='.cache/smoke-report.json')
    args = parser.parse_args()
    if args.base_url not in ('http://127.0.0.1:8080', 'http://localhost:8080'):
        raise SystemExit('Smoke tests are restricted to the local demo.')
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    def get(path):
        with opener.open(args.base_url + path, timeout=10) as response:
            return json.load(response)
    token = get('/api/v1/session')['csrfToken']
    report = {'corpus': get('/api/v1/corpus'), 'results': []}
    cases = json.loads((ROOT / 'eval/smoke.json').read_text())['cases']
    for case in cases:
        for repeat in range(2 if case.get('repeat') else 1):
            payload = {'question': case['question'], 'previousQuestions': [], 'filters': {'service': case['service'], 'region': case.get('region', '')}}
            request = urllib.request.Request(args.base_url + '/api/v1/chat', data=json.dumps(payload).encode(), headers={'Content-Type':'application/json', 'X-CSRF-Token':token})
            start = time.monotonic()
            try:
                with opener.open(request, timeout=70) as response:
                    result = json.load(response)
            except urllib.error.HTTPError as error:
                result = json.load(error)
            passed = result.get('status') == case['expectedStatus']
            if repeat: passed = passed and result.get('cacheDisposition') == 'EXACT'
            if result.get('status') == 'ANSWERED':
                citations = {c['id']: c for c in result['citations']}
                passed = passed and result.get('answerMode') == 'GROUNDED_SYNTHESIS'
                passed = passed and bool(result['claims']) and all(
                    c['text'].strip() and c['citationIds'] and all(
                        x in citations and citations[x]['quote'].strip()
                        and citations[x]['sourceUrl'].startswith('https://docs.aws.amazon.com/')
                        for x in c['citationIds']) for c in result['claims'])
            elapsed = round(time.monotonic() - start, 3)
            row = {'id': case['id'] + ('-repeat' if repeat else ''), 'passed': passed, 'elapsedSeconds': elapsed, 'response': result}
            report['results'].append(row)
            print(f"{row['id']}: {'PASS' if passed else 'FAIL'} {result.get('status', result.get('code'))} {elapsed}s {result.get('cacheDisposition', '')}", flush=True)
            output = ROOT / args.output
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_text(json.dumps(report, indent=2) + '\n')
    raise SystemExit(0 if all(r['passed'] for r in report['results']) else 1)

if __name__ == '__main__':
    main()
