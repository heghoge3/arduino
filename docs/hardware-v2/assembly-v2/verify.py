"""Browser rendering and network checks for the assembly guide. No hardware claims."""
from pathlib import Path
import json
from playwright.sync_api import sync_playwright
p=Path(__file__).resolve().parent
with sync_playwright() as w:
 b=w.chromium.launch(executable_path='/usr/bin/chromium',args=['--no-sandbox'])
 page=b.new_page(viewport={'width':1440,'height':1000},device_scale_factor=1)
 errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
 page.set_content((p/'index.html').read_text())
 assert page.locator('.wire').count()==73
 page.locator('#find').fill('W020')
 assert page.locator('.wire.active').count()==1 and 'W020' in page.locator('#selection').inner_text()
 assert page.locator('.wire.dim').count()==72
 page.locator('#reset').click();assert page.locator('.wire.dim').count()==0
 page.locator('#fit').click();page.locator('#drawing').screenshot(path=str(p/'overview-preview.png'))
 # Render full vector drawings to standalone PDF via browser, independent of webpage scrolling.
 for name in ['all-wires','power-detail','gps-detail','lcd-detail','straps-detail']:
  svg=(p/(name+'.svg')).read_text()
  q=b.new_page();q.set_content('<style>@page{size:A1 portrait;margin:8mm}body{margin:0}svg{width:100%;height:auto}</style>'+svg)
  q.pdf(path=str(p/(name+'.pdf')),prefer_css_page_size=True,print_background=True)
  q.close()
 for width in [390,1440]:
  page.set_viewport_size({'width':width,'height':1000})
  assert page.evaluate('document.documentElement.scrollWidth<=innerWidth+1'),width
 assert not errors,errors
 (p/'checks.json').write_text(json.dumps({'wirePaths':73,'highlight':True,'reset':True,'viewportWidths':[390,1440],'javascriptErrors':errors,'pdf':'Chromium vector print; A1; 5 drawings'},ensure_ascii=False,indent=2))
 b.close()

from pathlib import Path
from playwright.sync_api import sync_playwright
from pypdf import PdfReader,PdfWriter
import json
D=Path(__file__).resolve().parent
with sync_playwright() as w:
 b=w.chromium.launch(executable_path='/usr/bin/chromium',args=['--no-sandbox'])
 p=b.new_page(viewport={'width':1400,'height':1100})
 for name in ['all-wires','power-detail','lcd-detail']:
  p.set_content('<style>body{margin:0}svg{width:1400px;height:auto;display:block}</style>'+(D/(name+'.svg')).read_text())
  p.locator('svg').screenshot(path=str(D/(name+'.png')))
 b.close()
w=PdfWriter()
for name in ['all-wires','power-detail','gps-detail','lcd-detail','straps-detail']:
 r=PdfReader(D/(name+'.pdf'));assert len(r.pages)==1,(name,len(r.pages));w.append(r)
w.write(str(D/'v2-assembly.pdf'))
# Validate the physical wire network, treating taps on a single drawn rail as connected.
rs=json.loads((D/'wires.json').read_text());u={}
def find(x):
 u.setdefault(x,x)
 if u[x]!=x:u[x]=find(u[x])
 return u[x]
def union(a,b):u[find(a)]=find(b)
for r in rs:union(r['from'],r['to'])
for rail in ['pwr_left','pwr_right','gnd_left','gnd_right','sda_bus','scl_bus']:
 for k in list(u):
  if k.startswith(rail+'_'):union(k,rail+'_top')
for mod in ['esp','rtc','bme','mpu','mag','ina','sd']:
 uniontest='3v3' if mod=='esp' else 'vcc'
 assert find(mod+'_'+uniontest)==find('dc_out'),mod
 assert find(mod+'_gnd')==find('tp_out-'),mod
for mod in ['esp','lcd','rtc','bme','mpu','mag','ina']:
 assert find(mod+'_sda')==find('esp_sda'),mod
 assert find(mod+'_scl')==find('esp_scl'),mod
for a,b in [('bat-','tp_out-'),('esp_3v3','esp_gnd'),('esp_sda','esp_scl'),('esp_hold','tp_out+'),('tp_ck','tp_out-'),('tp_sk','tp_out-')]:assert find(a)!=find(b),(a,b)
assert find('lcd_gnd')==find('tp_out-')
assert find('lcd_vcc')==find('bl_d')
assert find('lcd_vcc')!=find('dc_out')
assert find('esp_rx')==find('gps_tx');assert find('esp_tx')==find('gps_rx')
print('73 wires; 7 direct power branches plus switched LCD; 7 SDA/SCL ports; polarity/isolation assertions passed; 5-page PDF')

report=json.loads((D/'checks.json').read_text())
report['networkAssertions']={'directPowerBranches':7,'groundBranches':8,'lcdPowerFromMosfetDrainOnly':True,'i2cParticipants':7,'separateBatteryMinusAndOutputMinus':True,'separateSupplyAndGround':True,'separateSdaAndScl':True,'separateLedCathodesAndGround':True,'crossedGpsUart':True}
(D/'checks.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
# Read the rendered SVG, not just the table: catch overlays that can look like a short.
import re,xml.etree.ElementTree as ET
ns={'s':'http://www.w3.org/2000/svg'};svg=ET.parse(D/'all-wires.svg')
segments=[];terminals={}
for g in svg.findall('.//s:g[@data-id]',ns):
 r=next(x for x in rs if x['id']==g.attrib['data-id'])
 pts=[tuple(map(float,p)) for p in re.findall(r'([\d.]+),([\d.]+)',g.find('s:path',ns).attrib['d'])]
 assert pts==[tuple(p) for p in r['points']],r['id']
 terminals[r['from']]=pts[0];terminals[r['to']]=pts[-1]
 for a,b in zip(pts,pts[1:]):
  assert a[0]==b[0] or a[1]==b[1],(r['id'],a,b)
  segments.append((r['id'],find(r['from']),a,b))
overlaps=[];near=[];touches=[]
for i,(ida,na,a,b) in enumerate(segments):
 for idb,nb,c,d in segments[i+1:]:
  if na==nb:continue
  for axis in [0,1]:
   other=1-axis
   if a[axis]==b[axis] and c[axis]==d[axis]:
    length=min(max(a[other],b[other]),max(c[other],d[other]))-max(min(a[other],b[other]),min(c[other],d[other]))
    distance=abs(a[axis]-c[axis])
    if length>0 and distance==0:overlaps.append([ida,idb,length])
    if length>0 and 0<distance<8:near.append([ida,idb,distance])
 for name,pt in terminals.items():
  if find(name)==na:continue
  if (a[0]==b[0]==pt[0] and min(a[1],b[1])<=pt[1]<=max(a[1],b[1])) or (a[1]==b[1]==pt[1] and min(a[0],b[0])<=pt[0]<=max(a[0],b[0])):touches.append([ida,name])
assert not overlaps,overlaps
assert not near,near
assert not touches,touches
for a,b in [('tp_out+','esp_3v3'),('tp_out+','p1_sys'),('dc_vin','esp_3v3'),('p1_bat','p1_sys'),('p1_bat','esp_sw')]:assert find(a)!=find(b),(a,b)
assert find('tp_out+')==find('ina_in+')==find('p1_bat')
assert find('ina_in-')==find('dc_vin')
report['geometryAudit']={'differentNetCollinearOverlaps':overlaps,'differentNetParallelDistanceBelow8':near,'wiresTouchingForeignTerminals':touches,'renderedSvgMatchesWireRecords':True,'batterySeparatedFrom3v3AndSwitchLogic':True}
(D/'checks.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
print('Rendered SVG audit: no different-net overlaps, no parallel clearance below 8 units, no foreign terminal touches; battery isolated from 3.3V')

bodyHits=[]
for rect in svg.findall('.//s:rect[@data-module]',ns):
 x,y,width,height=[float(rect.attrib[k]) for k in ['x','y','width','height']]
 for wire,net,a,b in segments:
  if a[0]==b[0] and x<a[0]<x+width and min(max(a[1],b[1]),y+height)>max(min(a[1],b[1]),y):bodyHits.append([wire,rect.attrib['data-module']])
  if a[1]==b[1] and y<a[1]<y+height and min(max(a[0],b[0]),x+width)>max(min(a[0],b[0]),x):bodyHits.append([wire,rect.attrib['data-module']])
assert not bodyHits,bodyHits
report['geometryAudit']['wiresThroughModuleBodies']=bodyHits
(D/'checks.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
print('No wire passes through a module body')
