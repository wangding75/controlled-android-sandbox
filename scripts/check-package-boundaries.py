#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []

RUNTIME_ROOT = ROOT / 'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime'
FRAMEWORK_ROOT = ROOT / 'sandbox-framework/src/main/java/com/warden/controlledsandbox/framework'

REQUIRED_RUNTIME_PACKAGES = {
    'broker', 'guest', 'component.activity', 'component.service',
    'component.receiver', 'provider', 'diagnostics', 'protocol', 'status', 'capability', 'systemservice',
}
REQUIRED_FRAMEWORK_PACKAGES = {
    'core', 'identity', 'activity', 'routing', 'packagemanager', 'permission', 'service', 'capability',
}

RUNTIME_ALLOWED_DEPS = {
    'broker': {'component.activity', 'component.receiver', 'component.service', 'diagnostics',
               'guest', 'protocol', 'provider', 'status'},
    'guest': {'broker', 'capability', 'component.activity', 'diagnostics', 'protocol', 'provider', 'systemservice'},
    'component.activity': {'broker', 'diagnostics', 'guest', 'protocol'},
    'component.service': {'protocol'},
    'component.receiver': {'protocol'},
    'provider': {'protocol'},
    'diagnostics': {'protocol'},
    'protocol': set(),
    'status': {'component.activity', 'component.receiver', 'component.service', 'protocol', 'provider'},
    'capability': set(),
    'systemservice': set(),
}
FRAMEWORK_ALLOWED_DEPS = {
    'core': {'capability', 'identity', 'packagemanager', 'permission', 'service'},
    'identity': {'capability'},
    'activity': {'core', 'identity', 'routing'},
    'routing': set(),
    'packagemanager': {'identity'},
    'permission': {'core', 'identity'},
    'service': {'core', 'identity'},
    'capability': set(),
    'systemservice': set(),
}


def package_for(source: Path) -> str | None:
    text = source.read_text(encoding='utf-8')
    match = re.search(r'^\s*package\s+([\w.]+);', text, flags=re.MULTILINE)
    return None if match is None else match.group(1)


def validate_tree(java_root: Path, package_root: Path, required: set[str], prefix: str) -> None:
    actual: set[str] = set()
    for source in java_root.rglob('*.java'):
        relative = source.relative_to(java_root)
        expected = '.'.join(relative.parts[:-1])
        declared = package_for(source)
        if declared is None:
            errors.append(f'{source.relative_to(ROOT)} has no package declaration')
        elif declared != expected:
            errors.append(f'{source.relative_to(ROOT)} declares {declared} but path requires {expected}')
        if source.is_relative_to(package_root) and source.parent != package_root:
            sub = '.'.join(source.parent.relative_to(package_root).parts)
            if source.name != 'package-info.java':
                actual.add(sub)
        if source.parent == package_root:
            errors.append(f'{source.relative_to(ROOT)} is a flat {prefix} root class')
    for missing in sorted(required - actual):
        errors.append(f'{prefix} is missing required package {missing}')


validate_tree(ROOT / 'sandbox-runtime/src/main/java', RUNTIME_ROOT,
              REQUIRED_RUNTIME_PACKAGES, 'sandbox-runtime')
validate_tree(ROOT / 'sandbox-framework/src/main/java', FRAMEWORK_ROOT,
              REQUIRED_FRAMEWORK_PACKAGES, 'sandbox-framework')
validate_tree(ROOT / 'sandbox-runtime/src/testHarness/java',
              ROOT / 'sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime',
              set(), 'sandbox-runtime testHarness')
validate_tree(ROOT / 'sandbox-framework/src/testHarness/java',
              ROOT / 'sandbox-framework/src/testHarness/java/com/warden/controlledsandbox/framework',
              set(), 'sandbox-framework testHarness')

# Stage-specific package names are forbidden in all Java/AIDL/XML production and harness sources.
for base in [ROOT / 'app/src', ROOT / 'sandbox-runtime/src', ROOT / 'sandbox-framework/src',
             ROOT / 'sandbox-domain/src', ROOT / 'sandbox-contract/src']:
    for source in base.rglob('*'):
        if not source.is_file() or source.suffix not in {'.java', '.aidl', '.xml'}:
            continue
        if 'dev.controlledsandbox.b2' in source.read_text(encoding='utf-8', errors='ignore'):
            errors.append(f'{source.relative_to(ROOT)} contains removed stage-specific package dev.controlledsandbox.b2')


def direct_subpackage(imported: str, base: str) -> str | None:
    if not imported.startswith(base + '.'):
        return None
    tail = imported[len(base) + 1:]
    parts = tail.split('.')
    if parts[0] == 'component' and len(parts) > 2:
        return '.'.join(parts[:2])
    return parts[0]


def enforce_imports(package_root: Path, base: str, allowed: dict[str, set[str]]) -> None:
    for source in package_root.rglob('*.java'):
        if source.name == 'package-info.java':
            continue
        source_sub = '.'.join(source.parent.relative_to(package_root).parts)
        text = source.read_text(encoding='utf-8')
        for imported in re.findall(r'^\s*import\s+([\w.]+);', text, flags=re.MULTILINE):
            target_sub = direct_subpackage(imported, base)
            if target_sub is None or target_sub == source_sub:
                continue
            if target_sub not in allowed.get(source_sub, set()):
                errors.append(
                    f'{source.relative_to(ROOT)} imports disallowed internal package {target_sub}'
                )


enforce_imports(RUNTIME_ROOT, 'com.warden.controlledsandbox.runtime', RUNTIME_ALLOWED_DEPS)
enforce_imports(FRAMEWORK_ROOT, 'com.warden.controlledsandbox.framework', FRAMEWORK_ALLOWED_DEPS)

# Product App may use only stable Runtime entry/protocol/diagnostic surfaces.
APP_ALLOWED_RUNTIME = {
    'com.warden.controlledsandbox.runtime.broker.RuntimeBrokerService',
    'com.warden.controlledsandbox.runtime.protocol.RuntimeKeys',
    'com.warden.controlledsandbox.runtime.protocol.ComponentOperations',
    'com.warden.controlledsandbox.runtime.diagnostics.RuntimeDiagnostics',
}
for source in (ROOT / 'app/src').rglob('*.java'):
    text = source.read_text(encoding='utf-8')
    for imported in re.findall(r'^\s*import\s+(com\.warden\.controlledsandbox\.runtime\.[\w.]+);',
                               text, flags=re.MULTILINE):
        if imported not in APP_ALLOWED_RUNTIME:
            errors.append(f'{source.relative_to(ROOT)} imports internal Runtime implementation {imported}')
    if re.search(r'^\s*import\s+com\.warden\.controlledsandbox\.framework\.', text, flags=re.MULTILINE):
        errors.append(f'{source.relative_to(ROOT)} imports internal Framework implementation')


# Migration must preserve both historically distinct Framework proxy regression suites.
required_test_classes = {
    'com.warden.controlledsandbox.framework.core.FrameworkIdentityProxySelfTest':
        ROOT / 'sandbox-framework/src/testHarness/java/com/warden/controlledsandbox/framework/core/FrameworkIdentityProxySelfTest.java',
    'com.warden.controlledsandbox.framework.core.FrameworkProxySelfTest':
        ROOT / 'sandbox-framework/src/testHarness/java/com/warden/controlledsandbox/framework/core/FrameworkProxySelfTest.java',
}
static_compiler = (ROOT / 'tools/static_android_compile.py').read_text(encoding='utf-8')
for class_name, source in required_test_classes.items():
    if not source.is_file():
        errors.append(f'missing migrated regression suite {source.relative_to(ROOT)}')
    if static_compiler.count("'" + class_name + "'") != 1:
        errors.append(f'static Android compiler must execute {class_name} exactly once')

# Test migrations must never collapse two top-level classes onto one fully-qualified name.
test_types: dict[str, Path] = {}
for test_root in [ROOT / 'sandbox-runtime/src/testHarness/java', ROOT / 'sandbox-framework/src/testHarness/java']:
    for source in test_root.rglob('*.java'):
        content = source.read_text(encoding='utf-8')
        package_match = re.search(r'^\s*package\s+([\w.]+);', content, flags=re.MULTILINE)
        type_match = re.search(
            r'^\s*(?:public\s+)?(?:final\s+|abstract\s+)?(?:class|interface|enum|record)\s+(\w+)',
            content, flags=re.MULTILINE,
        )
        if package_match is None or type_match is None:
            continue
        fqcn = package_match.group(1) + '.' + type_match.group(1)
        previous = test_types.get(fqcn)
        if previous is not None:
            errors.append(
                f'duplicate migrated test type {fqcn}: {previous.relative_to(ROOT)} and {source.relative_to(ROOT)}'
            )
        else:
            test_types[fqcn] = source

# Android component names must point at the new package layout.
manifest = (ROOT / 'sandbox-runtime/src/main/AndroidManifest.xml').read_text(encoding='utf-8')
required_components = [
    'com.warden.controlledsandbox.runtime.broker.RuntimeBrokerService',
    'com.warden.controlledsandbox.runtime.diagnostics.RuntimeInitProvider',
]
required_components += [f'com.warden.controlledsandbox.runtime.guest.GuestProcessService{i}' for i in range(8)]
required_components += [f'com.warden.controlledsandbox.runtime.component.activity.StubActivity{i}' for i in range(8)]
for component in required_components:
    if component not in manifest:
        errors.append(f'sandbox-runtime manifest is missing migrated component {component}')

if errors:
    print('FAIL Runtime/Framework package boundary checks', file=sys.stderr)
    for error in errors:
        print(' - ' + error, file=sys.stderr)
    raise SystemExit(1)
print('PASS Runtime/Framework package boundary checks')
