import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from truth import *
import numpy as np, collections, json
sid=sys.argv[1]
s=P['sId'].index(sid)
R=int(sys.argv[2]) if len(sys.argv)>2 else 175
cx,cz=P['sX'][s],P['sZ'][s]
x1,z1,x2,z2=cx-R,cz-R,cx+R,cz+R
w=load_world()
Y0,Y1=60,200
a=w.box(x1,z1,x2,z2,Y0,Y1)   # [y,z,x]
passable,fluid,support=classify(w)
Pm=passable[a]; Sm=support[a]; Fm=fluid[a]
ny,nz,nx=a.shape
# standable[y,z,x]: block at y is support, y+1,y+2 passable (or fluid-free)
st=np.zeros_like(Sm)
st[:-3]=Sm[:-3]&Pm[1:-2]&Pm[2:-1]
# level = y+1 absolute
np.save('st_%s.npy'%sid, st)
print('standable cells', st.sum(), a.shape)
# gate start: gate nodes
ns0=P['sNodeOff'][s]
starts=[]
for n in range(P['sNodeOff'][s],P['sNodeOff'][s+1]):
    if P['nClass'][n]==1:
        starts.append((int(P['nx'][n]),int(P['nz'][n]),int(P['nh'][n])))
print('gates',starts)
def cell_levels(x,z):
    xi,zi=x-x1,z-z1
    if not(0<=xi<nx and 0<=zi<nz): return []
    return [y for y in range(ny) if st[y,zi,xi]]
seen=np.zeros_like(st)
dq=collections.deque()
for (gx,gz,gh) in starts:
    for dx in range(-2,3):
        for dz in range(-2,3):
            for y in cell_levels(gx+dx,gz+dz):
                if abs(y+Y0-gh)<=2 and not seen[y,gz+dz-z1,gx+dx-x1]:
                    seen[y,gz+dz-z1,gx+dx-x1]=True; dq.append((y,gz+dz-z1,gx+dx-x1))
print('seeds',len(dq))
while dq:
    y,zi,xi=dq.popleft()
    for dx,dz in((1,0),(-1,0),(0,1),(0,-1)):
        nxi,nzi=xi+dx,zi+dz
        if not(0<=nxi<nx and 0<=nzi<nz): continue
        for ny2 in range(max(0,y-3),min(ny,y+2)):   # up <=1, down <=3
            if st[ny2,nzi,nxi] and not seen[ny2,nzi,nxi]:
                seen[ny2,nzi,nxi]=True; dq.append((ny2,nzi,nxi))
print('reached', seen.sum(), 'of', st.sum())
np.save('seen_%s.npy'%sid, seen)
# doors
res=[]
base=P['sDoorOff'][s]
for d in range(P['sDoorOff'][s],P['sDoorOff'][s+1]):
    spec_i=P['dSpec'][d]; sp=spec(P['sSpecOff'][s]+spec_i)
    face=P['dFacing'][d]
    ox,oz=[(0,1),(-1,0),(0,-1),(1,0)][face]   # Direction 2D: S,W,N,E
    x,y,z=P['dx'][d]+ox,P['dy'][d],P['dz'][d]+oz
    ok=False
    for xx,zz in ((x,z),(x+ox,z+oz)):
        xi,zi=xx-x1,zz-z1
        if 0<=xi<nx and 0<=zi<nz:
            for yy in range(ny):
                if seen[yy,zi,xi] and abs(yy+Y0+1-y)<=3: ok=True
    res.append(dict(spec=spec_i,kind=sp['kind'],use=sp['use'],name=sp['name'],x=P['dx'][d],z=P['dz'][d],reach_world=ok,reach_plan=P['dReach'][d]))
json.dump(res,open('truth_%s.json'%sid,'w'),indent=1)
n=len(res); k=sum(r['reach_world'] for r in res)
print('doors',n,'reach_world',k)
for r in res:
    if not r['reach_world'] and r['kind'] not in ('YARD',): print('  UNREACH',r)
