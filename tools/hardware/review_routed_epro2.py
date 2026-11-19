"""Summarize routed PCB geometry from review_epro2.py docs.json; no DRC engine."""
import json,math,collections,html,argparse
from pathlib import Path
parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('docs_json',type=Path);parser.add_argument('output_dir',type=Path);args=parser.parse_args();args.output_dir.mkdir(parents=True,exist_ok=True)
D=json.loads(args.docs_json.read_text());by={d['head']['uuid']:d for d in D};P=next(d for d in D if d['head']['docType']=='PCB');M=.0254
R=lambda d,t:[r for r in d['records'] if r['meta']['type']==t and r['data'] is not None]
attrs=collections.defaultdict(dict)
for r in R(P,'ATTR'):attrs[r['data']['parentId']][r['data']['key']]=r['data']['value']
dev={d['head']['uuid']:R(d,'META')[0]['data'] for d in D if d['head']['docType']=='DEVICE'}
C={attrs[r['meta']['id']]['Designator']:{'id':r['meta']['id'],'place':r['data'],'attrs':attrs[r['meta']['id']]} for r in R(P,'COMPONENT') if attrs[r['meta']['id']].get('Designator')}
def transform(pt,c):
 a=c['angle']*math.pi/180;x,y=pt
 return (c['x']+x*math.cos(a)-y*math.sin(a),c['y']+x*math.sin(a)+y*math.cos(a))
def points(path):
 if len(path)>=2 and isinstance(path[0],(int,float)) and isinstance(path[1],(int,float)):
  nums=path[:2]+[x for x in path[2:] if isinstance(x,(int,float))]
  if any(isinstance(x,str) and x!='L' for x in path):return []
  return list(zip(nums[::2],nums[1::2]))
 return []
board=points(next(r['data']['path'] for r in R(P,'POLY') if r['data']['polyType']=='BOARD_OUTLINE'))
def segdist(p,a,b):
 dx,dy=b[0]-a[0],b[1]-a[1];f=max(0,min(1,((p[0]-a[0])*dx+(p[1]-a[1])*dy)/(dx*dx+dy*dy))) if dx or dy else 0
 return math.hypot(p[0]-a[0]-f*dx,p[1]-a[1]-f*dy)
edges=list(zip(board,board[1:]));out={'boardVerticesMm':[[round(x*M,5),round(y*M,5)] for x,y in board], 'boardBoundingBoxMm':[round((max(x for x,y in board)-min(x for x,y in board))*M,4),round((max(y for x,y in board)-min(y for x,y in board))*M,4)],'holes':[],'trackWidths':{},'components':{},'powerVias':[]}
for i,r in enumerate(R(P,'FILL'),1):
 d=r['data']
 for a in d.get('path',[]):
  if a[0]=='CIRCLE' and d['layerId']==12:
   _,x,y,radius=a
   distances=[segdist((x,y),a,b) for a,b in edges];out['holes'].append({'id':r['meta']['id'],'xMm':round(x*M,5),'yMm':round(y*M,5),'diameterMm':round(2*radius*M,5),'minimumMaterialToEdgeMm':round((min(distances)-radius)*M,5)})
lines=[r['data'] for r in R(P,'LINE')];vias=[r['data'] for r in R(P,'VIA')]
for n in sorted(set(x['netName'] for x in lines)):
 ls=[x for x in lines if x['netName']==n];vs=[x for x in vias if x['netName']==n]
 out['trackWidths'][n]={'segments':len(ls),'widthsMm':sorted(set(round(x['width']*M,6) for x in ls)),'totalLengthMm':round(sum(math.hypot(x['endX']-x['startX'],x['endY']-x['startY'])*M for x in ls),3),'vias':len(vs)}
 if n in ['VCC','GND','$1N12','$1N13','$1N15','$1N21','$1N56','$1N61']:
  out['powerVias'] += [{'net':n,'holeMm':round(v['holeDiameter']*M,5),'copperDiameterMm':round(v['viaDiameter']*M,5)} for v in vs]
# Extract current placed footprints, not the cached thumbnail.
for ref,c in C.items():
 fp=c['attrs'].get('Footprint') or dev[c['attrs']['Device']]['attributes']['Footprint'];c['fp']=by[fp];pad=R(c['fp'],'PAD')
 paths=[]
 for r in R(c['fp'],'POLY'):
  if r['data'].get('layerId')==3:
   q=points(r['data'].get('path',[]))
   if q:paths.append([transform(v,c['place']) for v in q])
 for r in R(c['fp'],'FILL'):
  if r['data'].get('layerId')!=3:continue
  for a in r['data'].get('path',[]):
   if a[0]=='R':
    _,x,y,w,h,*_=a;paths.append([transform(v,c['place']) for v in [(x,y),(x+w,y),(x+w,y-h),(x,y-h),(x,y)]])
 c['paths']=paths
 out['components'][ref]={'originMm':[round(c['place']['x']*M,5),round(c['place']['y']*M,5)],'angle':c['place']['angle'],'footprint':R(c['fp'],'META')[0]['data']['title'],'pads':[{'number':r['data']['num'],'localXMm':round(r['data']['centerX']*M,5),'localYMm':round(r['data']['centerY']*M,5),'holeMm':round(r['data']['hole']['width']*M,5) if r['data'].get('hole') else None} for r in pad]}
# Render only geometry read from current records. This is a review drawing, not manufacturing output.
X=lambda x:x*M;Y=lambda y:-y*M
xmin=min(X(x) for x,y in board)-5;ymin=min(Y(y) for x,y in board)-5;W=out['boardBoundingBoxMm'][0]+10;H=out['boardBoundingBoxMm'][1]+10
els=[f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{xmin} {ymin} {W} {H}" width="1600" role="img" aria-label="Current PCB geometry review"><rect x="{xmin}" y="{ymin}" width="{W}" height="{H}" fill="#fafafa"/>']
els.append('<polygon points="'+' '.join(f'{X(x)},{Y(y)}' for x,y in board)+'" fill="#151d2a" stroke="#8a3ea8" stroke-width=".18"/>')
for layer in [2,1]:
 for d in lines:
  if d['layerId']!=layer:continue
  els.append(f'<path d="M {X(d["startX"])} {Y(d["startY"])} L {X(d["endX"])} {Y(d["endY"])}" stroke="{"#6399ff" if layer==2 else "#e45f5f"}" stroke-width="{d["width"]*M}" stroke-linecap="round" opacity=".8"/>')
for ref,c in C.items():
 for q in c['paths']:
  els.append('<polyline points="'+' '.join(f'{X(x)},{Y(y)}' for x,y in q)+'" fill="none" stroke="#ffd870" stroke-width=".10"/>')
 for r in R(c['fp'],'PAD'):
  p=r['data'];x,y=transform((p['centerX'],p['centerY']),c['place']);pad=p['defaultPad'];angle=-c['place']['angle']-p.get('padAngle',0);w,h=pad['width']*M,pad['height']*M
  if pad['padType']=='ELLIPSE':els.append(f'<ellipse cx="{X(x)}" cy="{Y(y)}" rx="{w/2}" ry="{h/2}" fill="#a7a7a7" transform="rotate({angle} {X(x)} {Y(y)})"/>')
  else:els.append(f'<rect x="{X(x)-w/2}" y="{Y(y)-h/2}" width="{w}" height="{h}" fill="#a7a7a7" transform="rotate({angle} {X(x)} {Y(y)})"/>')
  if p.get('hole'):els.append(f'<circle cx="{X(x)}" cy="{Y(y)}" r="{p["hole"]["width"]*M/2}" fill="#151d2a"/>')
 x,y=c['place']['x'],c['place']['y'];els.append(f'<text x="{X(x)+1}" y="{Y(y)-1}" font-family="sans-serif" font-size="1.1" fill="#fff8c0">{html.escape(ref)}</text>')
for v in vias:els.append(f'<circle cx="{X(v["centerX"])}" cy="{Y(v["centerY"])}" r="{v["viaDiameter"]*M/2}" fill="#b7b7b7"/><circle cx="{X(v["centerX"])}" cy="{Y(v["centerY"])}" r="{v["holeDiameter"]*M/2}" fill="#151d2a"/>')
for i,h in enumerate(out['holes'],1):
 x,y=h['xMm'],-h['yMm'];els.append(f'<circle cx="{x}" cy="{y}" r="{h["diameterMm"]/2}" fill="white" stroke="#45dd99" stroke-width=".12"/><text x="{x-6}" y="{y+3}" font-family="sans-serif" font-size="1.1" fill="#23ae6f">Hole {i}: {h["diameterMm"]:.1f} mm</text>')
els.append(f'<text x="{xmin+1}" y="{ymin+H-1}" font-family="sans-serif" font-size="1.2" fill="#334155">Static geometry review: {out["boardBoundingBoxMm"][0]:.2f} x {out["boardBoundingBoxMm"][1]:.2f} mm; not Gerber/DRC</text></svg>')
q=args.output_dir;(q/'routing.json').write_text(json.dumps(out,ensure_ascii=False,indent=2)+'\n');(q/'board-review.svg').write_text(''.join(els))
print(json.dumps({k:v for k,v in out.items() if k not in ['components','trackWidths']},ensure_ascii=False,indent=2))
print('H1/H2 row spacing mm:',abs(out['components']['H1']['originMm'][1]-out['components']['H2']['originMm'][1]))
