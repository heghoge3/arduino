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
for mod in ['esp','lcd','rtc','bme','mpu','mag','ina','sd']:
 uniontest='3v3' if mod=='esp' else 'vcc'
 assert find(mod+'_'+uniontest)==find('dc_out'),mod
 assert find(mod+'_gnd')==find('tp_out-'),mod
for mod in ['esp','lcd','rtc','bme','mpu','mag','ina']:
 assert find(mod+'_sda')==find('esp_sda'),mod
 assert find(mod+'_scl')==find('esp_scl'),mod
for a,b in [('bat-','tp_out-'),('esp_3v3','esp_gnd'),('esp_sda','esp_scl'),('esp_hold','tp_out+'),('tp_ck','tp_out-'),('tp_sk','tp_out-')]:assert find(a)!=find(b),(a,b)
assert find('esp_rx')==find('gps_tx');assert find('esp_tx')==find('gps_rx')
print('73 wires; 8 power/ground branches; 7 SDA/SCL ports; polarity/isolation assertions passed; 5-page PDF')

report=json.loads((D/'checks.json').read_text())
report['networkAssertions']={'powerGroundBranches':8,'i2cParticipants':7,'separateBatteryMinusAndOutputMinus':True,'separateSupplyAndGround':True,'separateSdaAndScl':True,'separateLedCathodesAndGround':True,'crossedGpsUart':True}
(D/'checks.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
