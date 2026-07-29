#!/usr/bin/env python3
from __future__ import annotations

from hashlib import sha256
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'verification/sbom.json'
MODULES = ['app', 'sandbox-domain', 'sandbox-contract', 'sandbox-framework', 'sandbox-native', 'sandbox-companion32', 'sandbox-runtime', 'fixture-basic']
components = []
for module in MODULES:
    base = ROOT / module
    files = sorted(p for p in base.rglob('*') if p.is_file() and '/build/' not in p.as_posix())
    digest = sha256()
    languages: set[str] = set()
    for path in files:
        rel = path.relative_to(ROOT).as_posix().encode()
        digest.update(rel + b'\0')
        digest.update(path.read_bytes())
        suffix = path.suffix.lower()
        languages.add({'.java':'Java','.aidl':'AIDL','.cpp':'C++','.h':'C++','.xml':'XML','.gradle':'Gradle'}.get(suffix, suffix.lstrip('.') or 'binary'))
    components.append({
        'type': 'application' if module in {'app', 'fixture-basic'} else 'library',
        'name': module,
        'version': '0.2.0-development',
        'license': 'Apache-2.0',
        'fileCount': len(files),
        'languages': sorted(languages),
        'sourceDigestSha256': digest.hexdigest(),
    })
document = {
    'bomFormat': 'ControlledSandbox-SBOM',
    'specVersion': '1.0',
    'project': 'controlled-sandbox-cleanroom',
    'version': '0.2.0-development',
    'components': components,
    'externalRuntimeDependencies': [
        {'name':'Android platform APIs','scope':'provided'},
        {'name':'Android Gradle Plugin','version':'8.11.1','scope':'build'},
        {'name':'Gradle','version':'8.13','scope':'build'},
    ],
}
OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text(json.dumps(document, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(f'PASS generated SBOM with {len(components)} components')
