"""Read-only audit: reject @SubscribeEvent handlers whose exact target is abstract.

Use actual NeoForge/FML source jars; no dependency on an old Minecraft runtime.
This checks ordinary single-argument Java event methods, not inferred lambda targets.
"""
from pathlib import Path
from zipfile import ZipFile
import argparse
import importlib.util
import json
import re

ROOT = Path(__file__).resolve().parents[2]
TOKEN = re.compile(r'//[^\n]*|/\*.*?\*/|""".*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|\b\w+\b|[^\s]', re.S)

def types(source):
    package = re.search(r'\bpackage\s+([\w.]+)\s*;', source)
    if not package: return {}
    tokens = [m[0] for m in TOKEN.finditer(source) if not m[0].startswith(('//', '/*', '"', "'"))]
    result, stack, pending, modifiers = {}, [], None, []
    depth = 0
    for index, token in enumerate(tokens):
        if token in ('class', 'interface', 'record', 'enum') and index+1 < len(tokens) and (index == 0 or tokens[index-1] != '.'):
            name = tokens[index+1]
            if re.fullmatch(r'\w+', name):
                qualified = '.'.join([package[1], *(entry[0] for entry in stack), name])
                result[qualified] = 'abstract' in modifiers or token == 'interface'
                pending = name
        if token == '{':
            depth += 1
            if pending: stack.append((pending, depth)); pending = None
            modifiers = []
        elif token == '}':
            if stack and stack[-1][1] == depth: stack.pop()
            depth -= 1; modifiers = []
        elif token == ';': modifiers = []
        else: modifiers.append(token)
    return result

def audit(jars, owner=None):
    known = {}
    for path in jars:
        with ZipFile(path) as archive:
            for name in archive.namelist():
                if name.endswith('.java'): known.update(types(archive.read(name).decode('utf-8')))
    sources = list((ROOT/'mod/src/main/java').rglob('*.java'))
    for path in sources: known.update(types(path.read_text(encoding='utf-8')))
    spec = importlib.util.spec_from_file_location('migration_scope', ROOT/'tools/migration/imports_263.py')
    scope = importlib.util.module_from_spec(spec); spec.loader.exec_module(scope)
    findings, checked, unresolved = [], 0, []
    method = re.compile(r'@(?:[\w.]+\.)?SubscribeEvent(?:\([^)]*\))?\s+(?:public\s+|private\s+|protected\s+|static\s+|final\s+)*[\w.<>]+\s+(\w+)\(\s*(?:final\s+)?([\w.]+)\s+\w+\s*\)')
    for path in sources:
        if owner and scope.owner(path) != owner: continue
        source = path.read_text(encoding='utf-8')
        imports = {name.rsplit('.',1)[-1]:name for name in re.findall(r'import\s+([\w.]+);',source)}
        package = re.search(r'package\s+([\w.]+);', source)[1]
        for match in method.finditer(source):
            first, *rest = match[2].split('.',1)
            candidates = [match[2], package+'.'+match[2]]
            if first in imports: candidates.append(imports[first]+('.'+rest[0] if rest else ''))
            resolved = next((name for name in candidates if name in known), None)
            entry = {'file':path.relative_to(ROOT).as_posix(), 'line':source.count('\n',0,match.start())+1, 'method':match[1], 'event':resolved or match[2]}
            if resolved is None: unresolved.append(entry)
            else:
                checked += 1
                if known[resolved]: findings.append(entry)
    return {'checked':checked, 'abstract_subscriptions':findings, 'unresolved':unresolved}

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sources', type=Path, action='append', required=True)
    parser.add_argument('--owner', choices=['root','network','world','ui'])
    args = parser.parse_args(); report = audit(args.sources,args.owner)
    print(json.dumps(report,indent=2)); raise SystemExit(bool(report['abstract_subscriptions'] or report['unresolved']))
