"""Summarize real compiler diagnostics by migration owner without hiding any failures."""
from pathlib import Path
import argparse,re,json,collections
from imports_263 import ROOT,owner
p=argparse.ArgumentParser();p.add_argument('log',type=Path);a=p.parse_args()
raw=a.log.read_bytes();text=raw.decode('utf-16' if raw.startswith(b'\xff\xfe') else 'utf8',errors='replace')
pattern=re.compile(r'^(.*?\.java):(\d+): error:\s*(.*)$',re.M)
matches=list(pattern.finditer(text));rows=[]
for index,m in enumerate(matches):
    path=Path(m[1].strip());line=int(m[2]);message=m[3]
    try:rel=path.relative_to(ROOT).as_posix();scope=owner(path)
    except ValueError:rel=str(path);scope='unknown'
    context=text[m.end():matches[index+1].start() if index+1<len(matches) else m.end()+600]
    rows.append({'path':rel,'line':line,'owner':scope,'message':message,'context':context[:1200]})
# Gradle may repeat diagnostics in its failure summary. Keep original first occurrence.
unique=list({(r['path'],r['line'],r['message']):r for r in reversed(rows)}.values())
out=a.log.with_suffix('.json');out.write_text(json.dumps(unique,indent=2)+'\n')
print('DIAGNOSTICS',len(unique),'OWNERS',dict(collections.Counter(r['owner'] for r in unique)))
for scope in ['root','world','network','ui']:
    group=[r for r in unique if r['owner']==scope]
    print(scope,'top files:',collections.Counter(r['path'] for r in group).most_common(8))
    print(scope,'missing symbols:',collections.Counter(re.findall(r'symbol:\s+(.*)', '\n'.join(r['context'] for r in group))).most_common(15))
