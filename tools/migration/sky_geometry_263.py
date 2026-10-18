"""Keep every authored sky vertex while moving its CPU/GPU ownership to native 26.3 buffers."""
import re
from imports_263 import ROOT
from java_text import matching,arguments
p=ROOT/'mod/src/main/java/com/skycraft/atmosphere/client/SkyGeometry.java'
s=p.read_text(encoding='utf8')
if 'VertexBuffer' not in s:raise SystemExit('Sky geometry is already migrated')
s=s.replace('import com.mojang.blaze3d.vertex.Tesselator;','import com.skycraft.render.client.CpuMesh;')
s=s.replace('import com.mojang.blaze3d.vertex.VertexBuffer;','import com.skycraft.render.client.WorldMesh;')
s=s.replace('import com.mojang.blaze3d.vertex.VertexFormat;','import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;')
s=s.replace('BufferBuilder.RenderedBuffer','CpuMesh').replace('VertexBuffer','WorldMesh')
s=s.replace('!buffer.isInvalid()','!buffer.isClosed()').replace('.getFormat().getVertexSize()', '.format().getVertexSize()')
m=re.search(r'private static WorldMesh upload\(CpuMesh rendered\)\s*\{',s);end=matching(s,m.end()-1)
s=s[:m.start()]+'''private static WorldMesh upload(CpuMesh rendered) {
        WorldMesh mesh = new WorldMesh(rendered);
        meshVertexBytes += mesh.vertexBytes();
        return mesh;
    }'''+s[end+1:]
edits=[]
for m in re.finditer(r'\bCpuMesh build\w*\([^)]*\)\s*\{',s):
    start=m.end()-1;end=matching(s,start);body=s[start+1:end]
    init=re.search(r'BufferBuilder bb = Tesselator\.getInstance\(\)\.getBuilder\(\);\s*bb\.begin\(VertexFormat\.Mode\.QUADS, (DefaultVertexFormat\.\w+)\);',body)
    if not init:raise ValueError('Unexpected builder in '+m[0])
    body=body[:init.start()]+'try (var nativeBuilder = CpuMesh.builder(PrimitiveTopology.QUADS, '+init[1]+')) {\n        BufferBuilder bb = nativeBuilder.vertices();'+body[init.end():]
    body=body.replace('return bb.end();','return nativeBuilder.build();')+'\n        }\n    '
    edits.append((start+1,end,body))
for start,end,value in reversed(edits):s=s[:start]+value+s[end:]
edits=[]
for m in re.finditer(r'\bbb\.vertex\(',s):
    end=matching(s,m.end()-1);a=arguments(s[m.end():end])
    if len(a)!=3:raise ValueError('Expected three position coordinates')
    edits.append((m.start(),end+1,'bb.addVertex('+', '.join('(float) ('+x.strip()+')' for x in a)+')'))
for start,end,value in reversed(edits):s=s[:start]+value+s[end:]
s=s.replace('.uv(','.setUv(').replace('.color(','.setColor(').replace('.endVertex()','')
p.write_text(s,encoding='utf8');print('Migrated',len(edits),'position calls; retained all loops/seeds/UV/color calculations')
