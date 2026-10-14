import struct, zlib, sys, os
BAKED = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', '..', 'mod', 'src', 'main', 'resources', 'skycraft', 'baked', 'streetplan.bin')
class R:
    def __init__(s, b): s.b=b; s.p=0
    def i(s): v=struct.unpack_from('>i',s.b,s.p)[0]; s.p+=4; return v
    def utf(s):
        n=struct.unpack_from('>H',s.b,s.p)[0]; s.p+=2; v=s.b[s.p:s.p+n].decode('utf-8','replace'); s.p+=n; return v
    def ints(s):
        n=s.i(); v=list(struct.unpack_from('>%di'%n,s.b,s.p)); s.p+=4*n; return v
    def floats(s):
        n=s.i(); v=list(struct.unpack_from('>%df'%n,s.b,s.p)); s.p+=4*n; return v
    def bytes_(s):
        n=s.i(); v=s.b[s.p:s.p+n]; s.p+=n; return list(struct.unpack('%db'%n,v))
def load(path=BAKED):
    raw=open(path,'rb').read()
    n=struct.unpack_from('>H',raw,0)[0]
    data=zlib.decompress(raw[2+n:])
    r=R(data)
    assert r.i()==0x53504c4e
    ver=r.i(); assert ver==1
    ns=r.i()
    P={'sId':[],'sX':[],'sZ':[],'sReach':[]}
    for _ in range(ns):
        P['sId'].append(r.utf()); P['sX'].append(r.i()); P['sZ'].append(r.i()); P['sReach'].append(r.i())
    for k in ['sNodeOff','sEdgeOff','sDoorOff','sPlotOff','sSpecOff']: P[k]=r.ints()
    for k in ['nx','nz','nh']: P[k]=r.floats()
    P['nClass']=r.bytes_()
    P['nJoinSeg']=r.ints(); P['nJoinVert']=r.ints()
    P['ea']=r.ints(); P['eb']=r.ints(); P['ew']=r.floats(); P['eClass']=r.bytes_(); P['eSurf']=r.bytes_()
    for k in ['dSpec','dx','dy','dz','dNode']: P[k]=r.ints()
    P['dFacing']=r.bytes_()
    n=r.i(); P['dReach']=[bool(x) for x in r.b[r.p:r.p+n]]; r.p+=n
    for k in ['pSpec','pMinX','pMinZ','pMaxX','pMaxZ','pPad']: P[k]=r.ints()
    P['pKind']=r.bytes_()
    P['specInts']=r.ints()
    n=r.i(); P['specNames']=[r.utf() for _ in range(n)]
    P['ns']=ns
    return P
if __name__=='__main__':
    P=load()
    print(P['ns'], len(P['nx']), len(P['ea']), len(P['dSpec']), len(P['pSpec']), len(P['specNames']), len(P['specInts'])//20)
