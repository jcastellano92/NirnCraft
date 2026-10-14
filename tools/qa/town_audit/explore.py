import sys, os; sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sp import load
import collections
P=load()
ARCH=["HOUSE","MANOR","LONGHOUSE","INN","SMITHY","STORE","ALCHEMIST","TEMPLE","SHRINE","PALACE","BARRACKS","STABLES","FARMHOUSE","MILL","HALL_OF_DEAD","TOWER","TENT","FIELD","WALL","GATE","STALL","DOCK","MINE","GRAVEYARD","STREET","PLAZA","WELL","LAMP","GARDEN","TERRACE","BRIDGE","CANAL","LUMBER","HOMESTEAD_PLOT","LANDMARK","GUILD_HALL","JAIL","BUILDING","YARD","PEN","RESERVED","QUARRY","CAMP"]
USE=["HOVEL","COTTAGE","HOUSE","TOWNHOUSE","MANOR","FARMHOUSE","INN","GENERAL_STORE","SMITHY","ALCHEMIST","JEWELLER","BOOKSELLER","TAILOR","BUTCHER","FLETCHER","TANNERY","MAGE_TOWER","FENCE_DEN","STABLES","BARN","MILL","SAWMILL","SMELTER","WAREHOUSE","FISHERY","TEMPLE","HALL_OF_DEAD","GUARDHOUSE","BARRACKS","KEEP","LONGHOUSE","GUILD_HALL","SHACK","WATCHTOWER"]
def spec(i):
    o=i*20; a=P['specInts'][o:o+20]
    return dict(kind=ARCH[a[0]] if a[0]<len(ARCH) else a[0], culture=a[1], style=a[2], x=a[3],z=a[4],x2=a[5],z2=a[6],w=a[7],d=a[8],h=a[9],facing=a[10],floor=a[11],floor2=a[12],flags=a[13],variant=a[14],margin=a[15],use=USE[a[16]] if 0<=a[16]<len(USE) else a[16],wealth=a[17],gseed=a[18],slope=a[19],name=P['specNames'][i])
if __name__=='__main__':
    import sys
    sid=sys.argv[1]
    s=P['sId'].index(sid)
    print(sid, P['sX'][s],P['sZ'][s],P['sReach'][s])
    for i in range(P['sSpecOff'][s],P['sSpecOff'][s+1]):
        d=spec(i)
        if d['kind'] in ('STREET','WALL'): continue
        print(i-P['sSpecOff'][s], d['kind'],d['use'],d['name'],d['x'],d['z'],'w',d['w'],'d',d['d'],'h',d['h'],'f',d['floor'],'fl',d['flags'],'wl',d['wealth'],'gs',d['gseed'])
