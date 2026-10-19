"""Migrate legacy NBT getters to explicit defaults using balanced Java argument lists."""
from pathlib import Path
import argparse,re
from imports_263 import ROOT,owner

def calls(source,name,rewrite):
    pos=0
    while True:
        match=re.search(r"\b"+re.escape(name)+r"\(",source[pos:])
        if not match: break
        start=pos+match.start(); begin=pos+match.end(); depth=1; quote=None; escaped=False; commas=[]; i=begin
        while i<len(source) and depth:
            c=source[i]
            if quote:
                if escaped: escaped=False
                elif c=="\\": escaped=True
                elif c==quote: quote=None
            elif c in ('"',"'"): quote=c
            elif c in "([{": depth+=1
            elif c in ")]}": depth-=1
            elif c=="," and depth==1: commas.append(i)
            i+=1
        if depth: raise ValueError("Unterminated call "+name)
        args=[]; previous=begin
        for stop in commas+[i-1]: args.append(source[previous:stop]); previous=stop+1
        if len(args)==1 and not args[0].strip(): args=[]
        if name in ("getIntArray","getLongArray","getByteArray") and source[i:].lstrip().startswith((".orElse", ".map", ".isPresent", ".ifPresent")):
            pos=i;continue
        result=rewrite(args,source[start:i])
        source=source[:start]+result+source[i:];pos=start+len(result)
    return source

def migrate(source):
    for name,default in (("getByte","(byte) 0"),("getShort","(short) 0"),("getInt","0"),("getLong","0L"),("getFloat","0f"),("getDouble","0d"),("getBoolean","false"),("getString",'""')):
        source=calls(source,name,lambda args,orig,n=name,d=default:n+"Or("+args[0]+", "+d+")" if len(args)==1 else orig)
    for name,default in (("getIntArray","new int[0]"),("getLongArray","new long[0]"),("getByteArray","new byte[0]")):
        source=calls(source,name,lambda args,orig,d=default:orig+".orElseGet(() -> "+d+")" if len(args)==1 else orig)
    source=source.replace("getAllKeys()","keySet()")
    source=calls(source,"getCompound",lambda args,orig:"getCompoundOrEmpty("+args[0]+")" if len(args)==1 else orig)
    source=calls(source,"getList",lambda args,orig:"getListOrEmpty("+args[0]+")" if len(args) in (1,2) else orig)
    source=calls(source,"putUUID",lambda args,orig:"store("+args[0]+", net.minecraft.core.UUIDUtil.CODEC,"+args[1]+")" if len(args)==2 else orig)
    source=calls(source,"getUUID",lambda args,orig:"read("+args[0]+", net.minecraft.core.UUIDUtil.CODEC).orElseThrow()" if len(args)==1 else orig)
    source=calls(source,"hasUUID",lambda args,orig:"read("+args[0]+", net.minecraft.core.UUIDUtil.CODEC).isPresent()" if len(args)==1 else orig)
    source=re.sub(r"([\w.$]+)\.contains\(([^\n]+?),\s*((?:net\.minecraft\.nbt\.)?Tag\.TAG_\w+)\)",r"com.nirncraft.platform.NbtData.hasType(\1, \2, \3)",source)
    return source

if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument("--owner",required=True,choices=("network","world","root","ui"));args=parser.parse_args()
    changed=0
    for path in (ROOT/"mod/src/main/java/com/nirncraft").rglob("*.java"):
        if owner(path)!=args.owner:continue
        text=path.read_text(encoding="utf-8");result=migrate(text)
        if result!=text:path.write_text(result,encoding="utf-8",newline="\n");changed+=1
    print(args.owner,"NBT changed",changed)
