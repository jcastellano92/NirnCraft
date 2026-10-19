"""Compile native NirnCraft pipelines offline with installed26.3 shaderc/SPIR-V libraries; never starts Minecraft."""
from pathlib import Path
import argparse,hashlib,json,re,subprocess,zipfile
ROOT=Path(__file__).resolve().parents[2]
DEFAULT=Path(r'E:\NirnCraft-scratch\migration-26.3')

def run(command):
    result=subprocess.run(command,cwd=ROOT,text=True,capture_output=True)
    if result.returncode:raise RuntimeError(result.stdout+'\n'+result.stderr)
    print(result.stdout.strip())

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--scratch',type=Path,default=DEFAULT)
    parser.add_argument('--jdk',type=Path,default=Path(r'E:\NirnCraft-scratch\toolchains\jdk-25.0.4.1+1'))
    parser.add_argument('--core-jar',type=Path,help='Also validate the actual packaged native core metadata')
    args=parser.parse_args();scratch=args.scratch;output=scratch/'native-shader-audit';output.mkdir(exist_ok=True)
    # Gradle expands pack.mcmeta. Valid source JSON can become invalid packaged JSON,
    # so the gate must inspect processed output and, at release, the actual core jar too.
    source_metadata=ROOT/'mod/src/main/resources/pack.mcmeta'
    processed_metadata=scratch/'build/resources/main/pack.mcmeta'
    metadata_inputs={}
    def check_metadata(label,raw,expected):
        data=json.loads(raw)
        for field in ('min_format','max_format'):
            if data.get('pack',{}).get(field)!=expected.get('pack',{}).get(field):
                raise ValueError(f'{label} changed native pack {field}')
        if data.get('filter')!=expected.get('filter'):
            raise ValueError(f'{label} differs from the authored resource filter')
        metadata_inputs[label]=hashlib.sha256(raw.encode('utf8')).hexdigest()
    metadata_paths=[Path('pack.mcmeta'),Path('resourcepacks/recipe_rules/pack.mcmeta')]
    for relative in metadata_paths:
        source=ROOT/'mod/src/main/resources'/relative
        expected=json.loads(source.read_text(encoding='utf8'))
        check_metadata(relative.as_posix()+':source',source.read_text(encoding='utf8'),expected)
        check_metadata(relative.as_posix()+':processed',(scratch/'build/resources/main'/relative).read_text(encoding='utf8'),expected)
        if args.core_jar:
            with zipfile.ZipFile(args.core_jar) as archive:
                check_metadata(relative.as_posix()+':packaged',archive.read(relative.as_posix()).decode('utf8'),expected)
    lines=(scratch/'network-component-javac.args').read_text().splitlines();classpath=lines[lines.index('"-classpath"')+1].strip('"')
    # The ad-hoc compile cache can contain tool dependencies too (old FastUtil/Gson). One newest coordinate per classifier.
    selected={};extras=[]
    def version_key(value):return tuple((1,int(token)) if token.isdigit() else (0,token) for token in re.split(r'[.\-+]',value))
    for entry in classpath.split(';'):
        if 'arm64' in entry:continue
        # The older component-audit argfile also references the preserved Forge core for comparison.
        # Never let incomplete native compilation silently resolve any NirnCraft class from that jar.
        if Path(entry).is_file() and entry.endswith('.jar'):
            with zipfile.ZipFile(entry) as archive:
                if 'com/nirncraft/NirnCraft.class' in archive.namelist():continue
        parts=entry.replace('\\','/').split('/')
        if 'files-2.1' not in parts:extras.append(entry);continue
        index=parts.index('files-2.1');group,artifact,version=parts[index+1:index+4]
        classifier=parts[-1].removeprefix(artifact+'-'+version)
        key=(group,artifact,classifier)
        if key not in selected or version_key(version)>version_key(selected[key][0]):selected[key]=(version,entry)
    classpath=';'.join(extras+[entry for version,entry in selected.values()])
    classpath=str(scratch/'build/classes/java/main')+';'+classpath
    def invoke(tool,arguments,name):
        argfile=output/(name+'.args');argfile.write_text('\n'.join('"'+str(v).replace('\\','/')+'"' for v in arguments))
        run([str(args.jdk/'bin'/(tool+'.exe')),'@'+str(argfile)])
    invoke('javac',['--release','25','-proc:none','-cp',classpath,'-d',output,ROOT/'tools/migration/NativeShaderAudit263.java',ROOT/'mod/src/main/java/com/nirncraft/fire/client/FireRenderTypes.java'],'compile')
    java=['--enable-native-access=ALL-UNNAMED','-cp',str(output)+';'+classpath,'NativeShaderAudit263']
    invoke('java',java+['describe',output/'pipelines.json'],'describe')
    native=zipfile.ZipFile(scratch/'build/moddev/artifacts/minecraft-patched-26.3.0.90-sources.jar')
    version=json.loads(native.read('version.json'));assert version['id']=='26.3'
    def read(identifier,extension,folder='core'):
        namespace,name=identifier.split(':',1);relative=f'assets/{namespace}/shaders/{name}{extension}' if folder=='core' else f'assets/{namespace}/shaders/include/{name}'
        local=ROOT/'mod/src/main/resources'/relative
        source=local.read_text(encoding='utf8') if local.is_file() else native.read(relative).decode('utf8')
        return re.sub(r'^\s*#include\s*<([^>]+)>\s*$',lambda match:read(match[1],'','include'),source,flags=re.M)
    pipelines=json.loads((output/'pipelines.json').read_text());manifest=[];keys={};links=[]
    profiles=[{}, {'RENDERPEARL_DEPTH_IS_ZERO_TO_ONE':'','RENDERPEARL_INSTANCE_INDEX_INCLUDES_BASE_INSTANCE':''}, {'RENDERPEARL_DEPTH_IS_ZERO_TO_ONE':'','RENDERPEARL_EXPLICIT_DEPTH_INVARIANCE':''}]
    for pipeline in pipelines:
        for profile in profiles:
            pair={}
            for stage,shader in pipeline['shaders'].items():
                defines=pipeline['defines']|profile;key=(stage,shader,tuple(sorted(defines.items())))
                if key not in keys:
                    number=len(manifest);name=f'{shader}/{stage}/{number}';path=output/f'{number}.{stage}.glsl'
                    path.write_text(read(shader,'.vsh' if stage=='vertex' else '.fsh'),encoding='utf8');keys[key]=name
                    manifest.append({'name':name,'stage':stage,'source':str(path),'defines':defines})
                pair[stage]=keys[key]
            links.append((pipeline,pair))
    (output/'manifest.json').write_text(json.dumps(manifest,indent=2))
    invoke('java',java+[output/'manifest.json',output/'compiled.json',processed_metadata,
                       scratch/'build/resources/main/resourcepacks/recipe_rules/pack.mcmeta'],'compile-shaders')
    compiled=json.loads((output/'compiled.json').read_text());units={unit['name']:unit for unit in compiled['compiled']};errors=[]
    for pipeline,pair in links:
        vertex,fragment=units[pair['vertex']],units[pair['fragment']];outputs={v['location']:v for v in vertex['outputs']}
        for variable in vertex['inputs']:
            attribute=pipeline['attributes'].get(variable['name'])
            if attribute is None or attribute['components']<variable['size']:errors.append(f"{pipeline['id']} incompatible vertex input {variable}")
            elif variable['base']!=(7 if attribute['type'].startswith('SINT_') else 8 if attribute['type'].startswith('UINT_') else 13):errors.append(f"{pipeline['id']} mismatched vertex scalar type {variable}")
        for variable in fragment['inputs']:
            other=outputs.get(variable['location'])
            if other is None or (other['base'],other['size'])!=(variable['base'],variable['size']):errors.append(f"{pipeline['id']} stage interface mismatch {variable}")
        seen_uniforms={}
        for unit in (vertex,fragment):
            for uniform in unit['uniforms']:
                expected=pipeline['uniforms'].get(uniform['name'])
                if expected is None:errors.append(f"{pipeline['id']} undeclared pipeline binding {uniform['name']}")
                elif uniform['kind']!=expected['kind']:errors.append(f"{pipeline['id']} mismatched pipeline binding type {uniform['name']}")
                current=(uniform['kind'],uniform['dimensions'])
                if uniform['name'] in seen_uniforms and seen_uniforms[uniform['name']]!=current:errors.append(f"{pipeline['id']} binding differs across stages {uniform['name']}")
                seen_uniforms[uniform['name']]=current
        if any(v['location']>=pipeline['colorTargets'] for v in fragment['outputs']):errors.append(f"{pipeline['id']} missing fragment target")
    input_hash=hashlib.sha256(json.dumps(pipelines,sort_keys=True).encode())
    for unit in manifest:
        input_hash.update(json.dumps({key:value for key,value in unit.items() if key!='source'},sort_keys=True).encode())
        input_hash.update(Path(unit['source']).read_bytes())
    report={'target':'26.3','nativePipelineCount':len(pipelines),'backendProfiles':len(profiles),'compiledShaderUnits':len(manifest),'packFormats':compiled['packFormats'],'recipeRulesFormat':compiled['recipeRulesFormat'],'metadataInputsSha256':metadata_inputs,'inputsSha256':input_hash.hexdigest(),'legacyCoreFallbackAllowed':False,'errors':errors,'gpuRuntimeVerified':False}
    target=ROOT/'dist/code-review/neoforge-migration/native-shader-audit.json';target.write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))
    if errors:raise SystemExit(1)
if __name__=='__main__':main()
