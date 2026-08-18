from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import re
import subprocess
from typing import Iterable

PRODUCTION_PATTERN = re.compile(r'^[^/]+/src/main/.*\.java$')


def _strip_comments_and_literals(text: str) -> str:
    out = []
    i = 0
    state = 'code'
    while i < len(text):
        ch = text[i]
        nxt = text[i + 1] if i + 1 < len(text) else ''
        if state == 'code':
            if ch == '/' and nxt == '/':
                out.extend('  '); i += 2; state = 'line'; continue
            if ch == '/' and nxt == '*':
                out.extend('  '); i += 2; state = 'block'; continue
            if ch == '"': out.append(' '); i += 1; state = 'string'; continue
            if ch == "'": out.append(' '); i += 1; state = 'char'; continue
            out.append(ch); i += 1; continue
        if state == 'line':
            if ch == '\n': out.append('\n'); state = 'code'
            else: out.append(' ')
            i += 1; continue
        if state == 'block':
            if ch == '*' and nxt == '/': out.extend('  '); i += 2; state = 'code'
            else: out.append('\n' if ch == '\n' else ' '); i += 1
            continue
        if state in {'string', 'char'}:
            quote = '"' if state == 'string' else "'"
            if ch == '\\':
                out.extend('  ' if i + 1 < len(text) else ' '); i += 2; continue
            if ch == quote: out.append(' '); i += 1; state = 'code'; continue
            out.append('\n' if ch == '\n' else ' '); i += 1
    return ''.join(out)


def _production_sources(root: Path, commit: str | None = None) -> dict[str, str]:
    if commit is None:
        result: dict[str, str] = {}
        for module in root.iterdir():
            source_root = module / 'src/main'
            if not module.is_dir() or not source_root.is_dir():
                continue
            for path in source_root.rglob('*.java'):
                result[path.relative_to(root).as_posix()] = path.read_text(
                    encoding='utf-8', errors='ignore')
        return dict(sorted(result.items()))
    names = subprocess.run(
        ['git', 'ls-tree', '-r', '--name-only', commit], cwd=root,
        text=True, encoding='utf-8', errors='replace',
        capture_output=True, check=True).stdout.splitlines()
    result = {}
    for name in names:
        if not PRODUCTION_PATTERN.match(name):
            continue
        result[name] = subprocess.run(
            ['git', 'show', f'{commit}:{name}'], cwd=root,
            text=True, encoding='utf-8', errors='replace',
            capture_output=True, check=True).stdout
    return dict(sorted(result.items()))


def _strongly_connected(graph: dict[str, set[str]]) -> list[list[str]]:
    index = 0
    stack: list[str] = []
    indices: dict[str, int] = {}
    low: dict[str, int] = {}
    active: set[str] = set()
    components: list[list[str]] = []

    def visit(node: str) -> None:
        nonlocal index
        indices[node] = low[node] = index; index += 1
        stack.append(node); active.add(node)
        for target in sorted(graph.get(node, set())):
            if target not in indices:
                visit(target); low[node] = min(low[node], low[target])
            elif target in active:
                low[node] = min(low[node], indices[target])
        if low[node] == indices[node]:
            component = []
            while True:
                current = stack.pop(); active.remove(current); component.append(current)
                if current == node: break
            if len(component) > 1 or node in graph.get(node, set()):
                components.append(sorted(component))

    for node in sorted(graph):
        if node not in indices: visit(node)
    return sorted(components)


def _module_graph(root: Path, commit: str | None) -> dict[str, set[str]]:
    if commit is None:
        settings = (root / 'settings.gradle').read_text(encoding='utf-8')
        reader = lambda name: (root / name).read_text(encoding='utf-8') if (root / name).is_file() else ''
    else:
        settings = subprocess.run(
            ['git', 'show', f'{commit}:settings.gradle'], cwd=root,
            text=True, encoding='utf-8', errors='replace',
            capture_output=True, check=True).stdout
        def reader(name: str) -> str:
            result = subprocess.run(
                ['git', 'show', f'{commit}:{name}'], cwd=root,
                text=True, encoding='utf-8', errors='replace',
                capture_output=True)
            return result.stdout if result.returncode == 0 else ''
    modules = sorted(set(re.findall(r"include\s+'(:[^']+)'", settings)))
    graph = {module: set() for module in modules}
    for module in modules:
        text = reader(module[1:] + '/build.gradle')
        for target in re.findall(r"project\(['\"](:[^'\"]+)['\"]\)", text):
            if target in graph: graph[module].add(target)
    return graph


def _method_metrics(path: str, text: str) -> list[dict[str, object]]:
    clean = _strip_comments_and_literals(text)
    pattern = re.compile(
        r'(?m)^\s*(?:(?:public|protected|private|static|final|abstract|synchronized|native|default|strictfp)\s+)*'
        r'(?:[\w.$<>\[\],?@]+\s+)+(\w+)\s*\([^;{}]*\)\s*(?:throws\s+[^\{]+)?\{')
    rows = []
    for match in pattern.finditer(clean):
        brace = clean.find('{', match.start())
        depth = 0; end = brace
        for index in range(brace, len(clean)):
            if clean[index] == '{': depth += 1
            elif clean[index] == '}':
                depth -= 1
                if depth == 0: end = index + 1; break
        body = clean[brace:end]
        complexity = 1
        complexity += len(re.findall(r'\b(?:if|for|while|case|catch)\b', body))
        complexity += body.count('&&') + body.count('||') + body.count('?')
        rows.append({
            'path': path,
            'method': match.group(1),
            'line': clean.count('\n', 0, match.start()) + 1,
            'complexity': complexity,
        })
    return rows


def _public_api(path: str, text: str) -> set[str]:
    clean = _strip_comments_and_literals(text)
    package = re.search(r'(?m)^\s*package\s+([\w.]+);', clean)
    prefix = package.group(1) if package else ''
    signatures = set()
    declaration = re.compile(
        r'(?m)^\s*(public|protected)\s+([^\n\{;]+(?:\([^\n\{;]*\)[^\n\{;]*)?)\s*(?:\{|;)')
    for match in declaration.finditer(clean):
        normalized = re.sub(r'\s+', ' ', match.group(0).rstrip('{;').strip())
        signatures.add(f'{prefix}|{Path(path).name}|{normalized}')
    return signatures


def metrics(root: Path, commit: str | None = None) -> dict[str, object]:
    sources = _production_sources(root, commit)
    packages: dict[str, str] = {}
    package_graph: dict[str, set[str]] = {}
    methods: list[dict[str, object]] = []
    api: set[str] = set()
    method_counts = []
    for path, text in sources.items():
        clean = _strip_comments_and_literals(text)
        package_match = re.search(r'(?m)^\s*package\s+([\w.]+);', clean)
        package = package_match.group(1) if package_match else ''
        packages[path] = package
        package_graph.setdefault(package, set())
        for imported in re.findall(r'(?m)^\s*import\s+([\w.]+);', clean):
            imported_package = imported.rsplit('.', 1)[0]
            if imported_package.startswith('com.warden.controlledsandbox') and imported_package != package:
                package_graph[package].add(imported_package)
                package_graph.setdefault(imported_package, set())
        rows = _method_metrics(path, text)
        methods.extend(rows)
        method_counts.append({'path': path, 'methods': len(rows)})
        api.update(_public_api(path, text))
    methods.sort(key=lambda row: (-int(row['complexity']), str(row['path']), int(row['line'])))
    method_counts.sort(key=lambda row: (-int(row['methods']), str(row['path'])))
    module_graph = _module_graph(root, commit)
    return {
        'sourceFiles': len(sources),
        'moduleDependencies': {key: sorted(value) for key, value in sorted(module_graph.items())},
        'moduleCycles': _strongly_connected(module_graph),
        'packageDependencyCycles': _strongly_connected(package_graph),
        'methodCount': len(methods),
        'maxMethodComplexity': int(methods[0]['complexity']) if methods else 0,
        'complexityBands': {
            'atLeast15': sum(1 for row in methods if int(row['complexity']) >= 15),
            'atLeast25': sum(1 for row in methods if int(row['complexity']) >= 25),
            'atLeast40': sum(1 for row in methods if int(row['complexity']) >= 40),
        },
        'highestComplexityMethods': methods[:25],
        'methodsPerSource': method_counts[:25],
        'maxMethodsPerSource': int(method_counts[0]['methods']) if method_counts else 0,
        'methodCountBands': {
            'atLeast40': sum(1 for row in method_counts if int(row['methods']) >= 40),
            'atLeast80': sum(1 for row in method_counts if int(row['methods']) >= 80),
            'atLeast120': sum(1 for row in method_counts if int(row['methods']) >= 120),
        },
        'publicApiSignatures': sorted(api),
        'publicApiCount': len(api),
    }


HOST_STUB_ACTIVITY_PACKAGE = 'com.warden.controlledsandbox.runtime.component.activity.'
HOST_PHYSICAL_ACTIVITY_LIMIT = 128
HOST_PHYSICAL_ALIAS_LIMIT = 0


def host_activity_stub_bounds(root: Path) -> dict[str, object]:
    """Host physical Activity/alias count is a bounded architecture constant."""
    manifest = (root / 'sandbox-runtime/src/main/AndroidManifest.xml').read_text(
        encoding='utf-8')
    activities = re.findall(r'<activity\s+android:name="([^"]+)"', manifest)
    aliases = re.findall(r'<activity-alias\s+android:name="([^"]+)"', manifest)
    stub_activities = [name for name in activities if name.startswith(HOST_STUB_ACTIVITY_PACKAGE)]
    forbidden = [
        name for name in stub_activities + aliases
        if 'SlotVariants' in name or 'StubActivityAlias' in name or 'StubActivityVariants' in name
    ]
    errors = []
    if len(stub_activities) > HOST_PHYSICAL_ACTIVITY_LIMIT:
        errors.append(
            f'host physical Activity count {len(stub_activities)} exceeds '
            f'{HOST_PHYSICAL_ACTIVITY_LIMIT}')
    if len(aliases) > HOST_PHYSICAL_ALIAS_LIMIT:
        errors.append(
            f'host activity-alias count {len(aliases)} exceeds {HOST_PHYSICAL_ALIAS_LIMIT}')
    if forbidden:
        errors.append('index-coupled Host stub remnants: ' + ','.join(forbidden[:8]))
    return {
        'activityCount': len(stub_activities),
        'aliasCount': len(aliases),
        'limitActivities': HOST_PHYSICAL_ACTIVITY_LIMIT,
        'limitAliases': HOST_PHYSICAL_ALIAS_LIMIT,
        'errors': errors,
    }
