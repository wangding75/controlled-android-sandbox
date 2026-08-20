#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
REFERENCE_ROOT = ROOT / 'ref'
MODULES = {
    'sandbox-domain': {'forbidden': ('android.', 'com.warden.controlledsandbox.runtime', 'com.warden.controlledsandbox.framework', 'com.warden.controlledsandbox.nativebridge', 'com.warden.controlledsandbox.app')},
    'sandbox-framework': {'forbidden': ('com.warden.controlledsandbox.runtime', 'com.warden.controlledsandbox.app')},
    'sandbox-native': {'forbidden': ('com.warden.controlledsandbox.runtime', 'com.warden.controlledsandbox.framework', 'com.warden.controlledsandbox.app')},
    'sandbox-companion32': {'forbidden': ('com.warden.controlledsandbox.runtime', 'com.warden.controlledsandbox.framework', 'com.warden.controlledsandbox.MainActivity', 'com.warden.controlledsandbox.SandboxRecord')},
    'sandbox-runtime': {'forbidden': ('com.warden.controlledsandbox.MainActivity', 'com.warden.controlledsandbox.ApkImportManager', 'com.warden.controlledsandbox.SandboxRecord')},
    'fixture-basic': {'forbidden': ('com.warden.controlledsandbox.runtime', 'com.warden.controlledsandbox.framework', 'com.warden.controlledsandbox.domain')},
}
PRODUCTION_ROOTS = [
    ROOT / 'app/src/main', ROOT / 'sandbox-domain/src/main', ROOT / 'sandbox-framework/src/main',
    ROOT / 'sandbox-native/src/main', ROOT / 'sandbox-companion32/src/main',
    ROOT / 'sandbox-runtime/src/main', ROOT / 'sandbox-contract/src/main',
]
TARGET_SPECIAL_CASES = ('com.alibaba.android.rimet', 'com.tencent.wework', 'com.ss.android.lark', 'com.lark')
ALLOWED_TARGET_GATE_ROOT = 'app/src/main/java/com/warden/controlledsandbox/compatibility/dingtalk/'
UPSTREAM_NAMES = ('virtualapp', 'newblackbox', 'twoyi')
GENERATED_ROOTS = (ROOT / 'build', ROOT / '.gradle')


def is_generated(path: Path) -> bool:
    return any(root == path or root in path.parents for root in GENERATED_ROOTS)

errors: list[str] = []
for module, rule in MODULES.items():
    for source in (ROOT / module / 'src/main').rglob('*.java'):
        text = source.read_text(encoding='utf-8')
        for forbidden in rule['forbidden']:
            if re.search(r'^\s*import\s+' + re.escape(forbidden), text, flags=re.MULTILINE):
                errors.append(f'{source.relative_to(ROOT)} imports forbidden layer {forbidden}')

for base in PRODUCTION_ROOTS:
    if not base.exists():
        continue
    for source in base.rglob('*'):
        if not source.is_file() or source.suffix.lower() not in {'.java', '.aidl', '.cpp', '.h', '.xml'}:
            continue
        text = source.read_text(encoding='utf-8', errors='ignore').lower()
        relative = source.relative_to(ROOT).as_posix()
        for package_name in TARGET_SPECIAL_CASES:
            isolated_dingtalk_gate = (package_name == 'com.alibaba.android.rimet'
                    and relative.startswith(ALLOWED_TARGET_GATE_ROOT))
            if package_name in text and not isolated_dingtalk_gate:
                errors.append(f'{source.relative_to(ROOT)} contains target-app package special case {package_name}')
        for upstream in UPSTREAM_NAMES:
            if upstream in text:
                errors.append(f'{source.relative_to(ROOT)} contains upstream project identifier {upstream}')


DOMAIN_JAVA_ROOT = ROOT / 'sandbox-domain/src/main/java'
DOMAIN_PACKAGE_ROOT = DOMAIN_JAVA_ROOT / 'com/warden/controlledsandbox/domain'
DOMAIN_REQUIRED_PACKAGES = {
    'packageinfo', 'packageinfo.manifest', 'identity', 'session', 'process',
    'component.activity', 'component.service', 'component.receiver',
    'component.provider', 'routing', 'persistence', 'protocol', 'port',
}

# Domain production code must expose subdomain boundaries in its filesystem/package layout.
for source in DOMAIN_PACKAGE_ROOT.rglob('*.java'):
    relative = source.relative_to(DOMAIN_JAVA_ROOT)
    expected_package = '.'.join(relative.parts[:-1])
    content = source.read_text(encoding='utf-8')
    match = re.search(r'^\s*package\s+([\w.]+);', content, flags=re.MULTILINE)
    if match is None:
        errors.append(f'{source.relative_to(ROOT)} has no package declaration')
    elif match.group(1) != expected_package:
        errors.append(
            f'{source.relative_to(ROOT)} declares {match.group(1)} but path requires {expected_package}'
        )
    if source.parent == DOMAIN_PACKAGE_ROOT and source.name != 'package-info.java':
        errors.append(
            f'{source.relative_to(ROOT)} is a flat domain root class; place it in an explicit subdomain package'
        )

DOMAIN_ALLOWED_IMPORTS = {
    'packageinfo': {'packageinfo.manifest'},
    'packageinfo.manifest': set(),
    'identity': {'persistence'},
    'session': {'process', 'port'},
    'process': {'packageinfo.manifest'},
    'component.activity': {'packageinfo.manifest'},
    'component.service': set(),
    'component.receiver': {'packageinfo.manifest'},
    'component.provider': set(),
    'routing': {'session'},
    'persistence': set(),
    'protocol': set(),
    'port': set(),
}

for source in DOMAIN_PACKAGE_ROOT.rglob('*.java'):
    if source.name == 'package-info.java':
        continue
    source_package = '.'.join(source.parent.relative_to(DOMAIN_PACKAGE_ROOT).parts)
    content = source.read_text(encoding='utf-8')
    for imported in re.findall(
            r'^\s*import\s+com\.warden\.controlledsandbox\.domain\.([\w.]+);',
            content, flags=re.MULTILINE):
        imported_package = imported.rsplit('.', 1)[0]
        if imported_package == source_package:
            continue
        allowed = DOMAIN_ALLOWED_IMPORTS.get(source_package)
        if allowed is not None and imported_package not in allowed:
            errors.append(
                f'{source.relative_to(ROOT)} imports disallowed domain package {imported_package}'
            )

# Prevent a foreign import from shadowing a same-package top-level type.
java_sources = [source for source in ROOT.rglob('*.java')
                if source.is_file() and REFERENCE_ROOT not in source.parents
                and not is_generated(source)]
package_types: dict[str, set[str]] = {}
source_meta: list[tuple[Path, str, str]] = []
for source in java_sources:
    content = source.read_text(encoding='utf-8')
    package_match = re.search(r'^\s*package\s+([\w.]+);', content, flags=re.MULTILINE)
    type_match = re.search(
        r'^\s*(?:public\s+)?(?:final\s+|abstract\s+)?(?:class|interface|enum|record)\s+(\w+)',
        content, flags=re.MULTILINE)
    if package_match is None or type_match is None:
        continue
    package_name = package_match.group(1)
    type_name = type_match.group(1)
    package_types.setdefault(package_name, set()).add(type_name)
    source_meta.append((source, package_name, content))
for source, package_name, content in source_meta:
    local_types = package_types.get(package_name, set())
    for imported in re.findall(r'^\s*import\s+([\w.]+);', content, flags=re.MULTILINE):
        simple_name = imported.rsplit('.', 1)[-1]
        if simple_name in local_types and not imported.startswith(package_name + '.'):
            errors.append(
                f'{source.relative_to(ROOT)} imports {imported}, shadowing same-package type {package_name}.{simple_name}'
            )

actual_domain_packages = {
    '.'.join(path.parent.relative_to(DOMAIN_PACKAGE_ROOT).parts)
    for path in DOMAIN_PACKAGE_ROOT.rglob('*.java')
    if path.name != 'package-info.java' and path.parent != DOMAIN_PACKAGE_ROOT
}
for package_name in sorted(DOMAIN_REQUIRED_PACKAGES - actual_domain_packages):
    errors.append(f'sandbox-domain is missing required subdomain package {package_name}')

settings = (ROOT / 'settings.gradle').read_text(encoding='utf-8')
required = (':app', ':sandbox-domain', ':sandbox-contract', ':sandbox-framework', ':sandbox-native', ':sandbox-companion32', ':sandbox-runtime', ':fixture-basic')
for module in required:
    if f"include '{module}'" not in settings:
        errors.append(f'settings.gradle is missing {module}')

runtime_gradle = (ROOT / 'sandbox-runtime/build.gradle').read_text(encoding='utf-8')
for dependency in (':sandbox-domain', ':sandbox-contract', ':sandbox-framework', ':sandbox-native'):
    if dependency not in runtime_gradle:
        errors.append(f'sandbox-runtime missing dependency {dependency}')

if errors:
    print('FAIL architecture boundary checks', file=sys.stderr)
    for error in errors:
        print(' - ' + error, file=sys.stderr)
    raise SystemExit(1)
print('PASS architecture boundary checks')
