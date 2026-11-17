from pathlib import Path
import json,html,csv
D=Path(__file__).resolve().parent
C={'pwr':'#b83026','gnd':'#344054','sda':'#087e80','scl':'#355cbb','spi':'#7445ad','gps':'#25803e','io':'#b04b80','hold':'#b66b05','stop':'#bd2d29','led':'#986400','bat':'#a05308'}
SIDE={};P={};boxes=[];labels=[];wires=[];junctions=[];records=[]
def txt(x,y,s,size=18,fill='#1c293a',anchor='start'):
 return f'<text x="{x}" y="{y}" font-size="{size}" fill="{fill}" text-anchor="{anchor}">{html.escape(s)}</text>'
def box(id,x,y,w,h,title,sub=''):
 boxes.append(f'<rect data-module="{id}" x="{x}" y="{y}" width="{w}" height="{h}" rx="9" fill="#fff" stroke="#7890a5" stroke-width="2"/>')
 labels.append(txt(x+18,y+30,title,22));labels.append(txt(x+18,y+53,sub,14,'#5d6b7a'))
 return x,y,w,h
def port(id,b,side,y,name,kind):
 x=b[0] if side=='L' else b[0]+b[2];P[id]=(x,y,name,kind);SIDE[id]=side
 labels.append(f'<circle cx="{x}" cy="{y}" r="6" fill="{C[kind]}" stroke="white" stroke-width="2"/>')
 labels.append(txt(x+(12 if side=='L' else -12),y+6,name,18,C[kind], 'start' if side=='L' else 'end'))
def point(id,x,y,name,kind):P[id]=(x,y,name,kind);junctions.append((x,y,C[kind]))
def wire(a,b,via=[],kind=None,blocked=False):
 x,y,an,ak=P[a];xx,yy,bn,bk=P[b];k=kind or ak;assert ak==bk or kind,(a,b,ak,bk)
 n=f'W{len(records)+1:03}';pts=[(x,y)]+via+[(xx,yy)];d='M'+' L'.join(f'{u},{v}' for u,v in pts)
 color=C['stop'] if blocked else C[k]
 wires.append(f'<g class="wire" data-id="{n}" data-kind="{k}" tabindex="0" role="button" aria-label="{n}: {html.escape(an)} — {html.escape(bn)}"><title>{n}: {html.escape(an)} → {html.escape(bn)}</title><path d="{d}" fill="none" stroke="white" stroke-width="9"/><path d="{d}" fill="none" stroke="{color}" stroke-width="3" {"stroke-dasharray=\"9,7\"" if blocked else ""}/><path class="hit" d="{d}" fill="none" stroke="transparent" stroke-width="18"/></g>')
 # Label at both ends: each wire has a unique ID even where colors repeat.
 if b in SIDE:
  labels.append(txt(xx+(-12 if SIDE[b]=='L' else 12),yy-12,n,13,color,'end' if SIDE[b]=='L' else 'start'))
 elif a in SIDE:
  labels.append(txt(x+(-12 if SIDE[a]=='L' else 12),y-12,n,13,color,'end' if SIDE[a]=='L' else 'start'))
 elif abs(x-xx)<1:
  labels.append(f'<text x="{x-9}" y="{(y+yy)/2}" font-size="13" fill="{color}" transform="rotate(-90 {x-9} {(y+yy)/2})">{n}</text>')
 records.append({'id':n,'from':a,'fromLabel':an,'to':b,'toLabel':bn,'kind':k,'blocked':blocked,'points':pts})
def tap(rail,y):
 x=rails[rail][0];key=f'{rail}_{y}'
 if key not in P:point(key,x,y,rails[rail][1],rail.split('_')[0])
 return key
# Each rail is a separate physical conductor. No paired-wire edges.
rails={'pwr_left':(450,'+3,3 В / SYS_3V3'),'gnd_left':(490,'GND / OUT−'),'pwr_right':(1330,'+3,3 В / SYS_3V3'),'gnd_right':(1370,'GND / OUT−'),'sda_bus':(1410,'SDA / GPIO8'),'scl_bus':(1450,'SCL / GPIO9')}
for k,(x,name) in rails.items():
 start=590 if k.startswith('pwr') else 630 if k.startswith('gnd') else 650 if k.startswith('sda') else 670
 point(k+'_top',x,start,name,k.split('_')[0]);point(k+'_bottom',x,2540,name,k.split('_')[0]);wire(k+'_top',k+'_bottom')
 labels.append(f'<text x="{x-8}" y="2630" font-size="14" fill="{C[k.split(chr(95))[0]]}" transform="rotate(-90 {x-8} 2630)">{html.escape(name)}</text>')
b=box('bat',40,100,250,170,'Аккумулятор LiPo','1 ячейка; полярность проверить')
port('bat+',b,'R',175,'CELL+ · до 4,2 В','bat');port('bat-',b,'R',225,'CELL− / минус','gnd')
b=box('tp',390,100,300,360,'TP4056 + защита','USB: вход 5 В; OUT+: батарея')
for id,side,y,name,k in [('b+','L',175,'B+','bat'),('b-','L',225,'B−','gnd'),('out+','R',175,'OUT+ · до 4,2 В','bat'),('out-','R',225,'OUT−','gnd'),('ca','L',295,'LED CHRG: анод*','pwr'),('ck','L',335,'LED CHRG: катод*','led'),('sa','L',385,'LED STDBY: анод*','pwr'),('sk','L',425,'LED STDBY: катод*','led')]:port('tp_'+id,b,side,y,name,k)
wire('bat+','tp_b+');wire('bat-','tp_b-')
b=box('ina',790,100,300,360,'INA219','VIN± — силовые; VCC — логика')
for id,side,y,name,k in [('in+','L',175,'VIN+ · силовой вход','bat'),('in-','R',175,'VIN− · после шунта','bat'),('vcc','L',295,'VCC','pwr'),('gnd','L',335,'GND','gnd'),('sda','L',385,'SDA','sda'),('scl','L',425,'SCL','scl')]:port('ina_'+id,b,side,y,name,k)
wire('tp_out+','ina_in+')
b=box('dc',1690,100,280,300,'XL / TPS63020','Выход настроить на 3,3 В')
for id,side,y,name,k in [('vin','L',180,'VIN · от батареи','bat'),('gnd','L',230,'GND','gnd'),('en','L',350,'EN преобразователя','hold'),('out','R',180,'OUT 3,3 В','pwr')]:port('dc_'+id,b,side,y,name,k)
wire('ina_in-','dc_vin',[(1130,175),(1130,70),(1640,70),(1640,180)])
wire('dc_out','pwr_right_top',[(1990,180),(1990,590)])
wire('pwr_right_top','pwr_left_top')
wire('tp_out-','gnd_left_top',[(710,225),(710,630)])
wire('gnd_left_top','gnd_right_top')
wire(tap('gnd_right',630),'dc_gnd',[(1630,630),(1630,230)])
p1a=box('p1a',1200,100,380,180,'P1A · S1A + диоды','Только разрешение EN; не питание 3,3 В')
p1b=box('p1b',1200,310,380,180,'P1B · S1B + резисторы','Отдельная группа, только логика 3,3 В')
port('p1_bat',p1a,'R',175,'S1A.COM · плюс АКБ','bat')
port('p1_hold',p1a,'L',235,'GPIO7 → D2','hold')
port('p1_en',p1a,'R',195,'Катоды D1/D2 → EN','hold')
port('p1_sys',p1b,'L',385,'RSW/RB · +3,3 В','pwr')
port('p1_state',p1b,'L',425,'S1B.COM → GPIO3','io')
port('p1_gnd',p1b,'L',465,'S1B.OFF / резисторы → GND','gnd')
wire('tp_out+','p1_bat',[(730,175),(730,500),(1605,500),(1605,175)])
wire('p1_en','dc_en',[(1660,195),(1660,350)])
wire('p1_gnd',tap('gnd_right',630),[(1180,465),(1180,630)])
wire('p1_sys',tap('pwr_right',590),[(1155,385),(1155,590)])
wire('ina_vcc',tap('pwr_left',590),[(750,295),(750,590)])
wire('ina_gnd',tap('gnd_left',630),[(760,335),(760,630)])
wire('ina_sda','sda_bus_top',[(770,385),(770,535),(1410,535)])
wire('ina_scl','scl_bus_top',[(780,425),(780,565),(1450,565)])
# Status LED wires: unknown pads shown as prohibited-to-solder pending identification.
b=box('ch',40,420,250,160,'LED «Заряд»','Вместо штатного LED CHRG')
port('ch_a',b,'R',495,'Анод +','pwr');port('ch_k',b,'R',550,'Катод K · не GND','led')
wire('tp_ca','ch_a',[(350,295),(350,495)],blocked=True);wire('tp_ck','ch_k',[(370,335),(370,550)],blocked=True)
b=box('ready',40,650,350,160,'LED «Готово»','Вместо штатного LED STDBY')
port('rd_a',b,'R',720,'Анод +','pwr');port('rd_k',b,'R',775,'Катод K · не GND','led')
wire('tp_sa','rd_a',[(330,385),(330,615),(410,615),(410,720)],blocked=True);wire('tp_sk','rd_k',[(310,425),(310,595),(430,595),(430,775)],blocked=True)
# Central MCU: schematic terminals, never claim physical placement of pads.
esp=box('esp',650,700,380,1770,'ESP32-S3-Zero','Условный вид; GPIO ≠ № ножки')
for id,side,y,name,k in [('3v3','R',790,'3V3','pwr'),('gnd','R',840,'GND','gnd'),('sda','R',900,'GPIO8 · SDA','sda'),('scl','R',950,'GPIO9 · SCL','scl'),('cs','L',1010,'GPIO10 · CS','spi'),('mosi','L',1050,'GPIO11 · MOSI','spi'),('sck','L',1090,'GPIO12 · SCK','spi'),('miso','L',1130,'GPIO13 · MISO','spi'),('rx','L',1420,'GPIO44 · RX','gps'),('tx','L',1460,'GPIO43 · TX','gps'),('gps','L',1730,'GPIO6 · GPS ON=LOW','gps'),('a','L',1970,'GPIO1 · кнопка A','io'),('b','L',2130,'GPIO2 · кнопка B','io'),('buzz','L',2370,'GPIO5 · PWM','io'),('int','R',1790,'GPIO4 · MPU INT','io'),('sw','R',2030,'GPIO3 · положение','io'),('hold','R',2130,'GPIO7 · HOLD','hold'),('bl','R',2260,'GPIO14 · LED ON=LOW','io')]:port('esp_'+id,esp,side,y,name,k)
wire('esp_3v3',tap('pwr_right',790));wire('esp_gnd',tap('gnd_right',840));wire('esp_sda',tap('sda_bus',900));wire('esp_scl',tap('scl_bus',950))
labels.extend([txt(685,1195,'USB ESP32: только сервис',18),txt(685,1230,'GPIO19/20 — штатный USB',17),txt(685,1265,'BOOT GPIO0 / RESET доступны',17),txt(685,1300,'GPIO21 — встроенный RGB',17),txt(685,1515,'Не подавайте 5 В на GPIO!',18,C['stop']),txt(685,1550,'5V ESP не соединять с LiPo.',16,C['stop']),txt(685,1590,'Уровни всех сигналов 3,3 В.',16)])
# Left hand peripherals.
sd=box('sd',40,860,350,320,'MicroSD · SPI','Проверить VCC платы: цель 3,3 В')
for id,y,name,k in [('vcc',930,'VCC','pwr'),('gnd',970,'GND','gnd'),('cs',1010,'CS / SS','spi'),('mosi',1050,'DI / MOSI','spi'),('sck',1090,'CLK / SCK','spi'),('miso',1130,'DO / MISO','spi')]:port('sd_'+id,sd,'R',y,name,k)
for k in ['cs','mosi','sck','miso']:wire('sd_'+k,'esp_'+k)
wire('sd_vcc',tap('pwr_left',930));wire('sd_gnd',tap('gnd_left',970))
gps=box('gps',40,1270,350,240,'GPS ATGM336H / AT6558','Питание 3,3 В подтверждено владельцем')
for id,y,name,k in [('vcc',1340,'VCC · питание от ключа','pwr'),('gnd',1380,'GND','gnd'),('tx',1420,'TX модуля → RX ESP','gps'),('rx',1460,'RX модуля ← TX ESP','gps')]:port('gps_'+id,gps,'R',y,name,k)
wire('gps_gnd',tap('gnd_left',1380));wire('gps_tx','esp_rx');wire('gps_rx','esp_tx')
q=box('qgps',40,1570,350,220,'Ключ GPS · схема Б','AO3401 — кандидат, проверить')
port('qgps_s',q,'R',1640,'S · исток','pwr');port('qgps_d',q,'R',1680,'D · сток','pwr');port('qgps_g',q,'R',1730,'Управление GPIO6','gps')
wire('qgps_s',tap('pwr_left',1640),blocked=True);wire('qgps_d','gps_vcc',[(525,1680),(525,1340)],blocked=True);wire('qgps_g','esp_gps')
labels.append(txt(60,1535,'Антенна → RF-разъём GPS (не GPIO)',17,C['gps']))
for prefix,y,gpio in [('a',1900,'a'),('b',2060,'b')]:
 b=box(prefix,40,y,350,140,'Кнопка '+prefix.upper(),'Без фиксации; пару прозвонить')
 port(prefix+'_1',b,'R',y+70,'Контакт 1 · сигнал','io');port(prefix+'_2',b,'R',y+110,'Контакт 2 · земля','gnd')
 wire(prefix+'_1','esp_'+gpio);wire(prefix+'_2',tap('gnd_left',y+110))
b=box('buzz',40,2290,350,180,'Пассивная пищалка','! Драйвер не выбран; не к GPIO' )
port('buzz_in',b,'R',2370,'Вход будущего драйвера','io');wire('buzz_in','esp_buzz',blocked=True)
labels.append(txt(60,2410,'Нужны модель, напряжение и ток.',16,C['stop']))
# Six I2C boards incl. INA above; individual VCC/GND/SDA/SCL wires.
for key,title,sub,y in [('lcd','LCD GM12864-59N','4 контакта; I2C 0x3E / 0x3F',720),('rtc','DS3231 · часы','I2C 0x68; VBAT — штатная батарейка',990),('bme','BME280','I2C 0x76 / 0x77; см. схему Г',1260),('mpu','GY-521 / MPU6050','! AD0=HIGH → адрес 0x69',1530),('mag','GY-271 · компас','! Точный чип ещё не подтверждён',1900)]:
 b=box(key,1530,y,430,300 if key=='mpu' else 250,title,sub)
 for suffix,dy,name,k,rail in [('vcc',70,'VCC','pwr','pwr_right'),('gnd',110,'GND','gnd','gnd_right'),('sda',150,'SDA / SDI' if key=='bme' else 'SDA','sda','sda_bus'),('scl',190,'SCL / SCK' if key=='bme' else 'SCL','scl','scl_bus')]:
  port(key+'_'+suffix,b,'L',y+dy,name,k)
  if key=='lcd' and suffix=='vcc':
   P['bl_d']=(1960,2440,'D AO3401 · LCD_VCC_SW','pwr')
   wire('bl_d','lcd_vcc',[(1990,2440),(1990,2500),(1490,2500),(1490,y+dy)],blocked=True)
  else:wire(tap(rail,y+dy),key+'_'+suffix,blocked=key=='lcd' and suffix in ['sda','scl'])
 if key=='mpu':
  port('mpu_int',b,'L',1790,'INT · активный HIGH','io');wire('esp_int','mpu_int',[(1270,1790)])
 labels.append(txt(1550,y+229,{'lcd':'VCC от ключа; SDA/SCL при OFF проверить','rtc':'NC/SQW/32K к ESP не подключать','bme':'CSB и SDO: режим I2C, схема Г','mpu':'AD0 к VDDIO, не к 5 В; схема Г','mag':'SDA/SCL не менять местами'}[key],15,'#805316'))
wire('esp_sw','p1_state',[(1105,2030),(1105,425)])
wire('esp_hold','p1_hold',[(1130,2130),(1130,235)])
b=box('bl',1530,2290,430,180,'Ключ всего LCD · схема В','! ПО 0.1.33; I2C OFF ещё проверить')
for id,y,name,k in [('s',2360,'S · SYS_3V3','pwr'),('g',2410,'Через 10k к G · GPIO14','io')]:port('bl_'+id,b,'L',y,name,k)
wire(tap('pwr_right',2360),'bl_s');wire('esp_bl','bl_g',[(1285,2260),(1285,2410)])
port('bl_d',b,'R',2440,'D → VCC всего LCD','pwr')
# Continuous rails have connection dots only at real taps. Branches cross without dots.
svgstyle='''<style>text{font-family:DejaVu Sans,Arial,sans-serif}.wire{cursor:pointer;outline:none}.wire:focus path:nth-child(2){stroke-width:6}.dim{opacity:.08}.wire.active path:nth-child(2){stroke-width:7}</style>'''
svg=f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 2040 2660" role="img" aria-label="V2: общая монтажная схема, каждый провод отдельно">{svgstyle}<rect width="2040" height="2660" fill="#f6f8fb"/>'+txt(40,35,'V2 · ЦЕЛЕВАЯ СХЕМА · КЛЮЧ ВСЕГО LCD (ПО 0.1.33 / wiring3, ЖЕЛЕЗО НЕ ПРИНЯТО)',25)+txt(40,62,'Это расположение символов, НЕ физический порядок выводов. Красный пунктир: СТОП, сначала проверка.',17,C['stop'])+''.join(boxes)+''.join(wires)+''.join(f'<circle cx="{x}" cy="{y}" r="5" fill="{col}" stroke="white" stroke-width="1"/>' for x,y,col in junctions)+''.join(labels)+txt(40,2640,'Точка = соединение. Пересечение без точки = НЕ соединение. W001… — номера проводов, не номера ножек.',18)+'</svg>'
(D/'all-wires.svg').write_text(svg)
(D/'wires.json').write_text(json.dumps(records,ensure_ascii=False,indent=2))
with (D/'wires.csv').open('w',newline='') as f:
 w=csv.writer(f,lineterminator="\n");w.writerow(['Провод','Откуда','Контакт','Куда','Контакт','Статус'])
 for r in records:w.writerow([r['id'],r['from'],r['fromLabel'],r['to'],r['toLabel'],'СТОП: проверить' if r['blocked'] else 'По электрической базе; экземпляр проверить'])
# Detailed supplementary circuits are literal SVG, never shared-wire block arrows.
def detail(title,w,h,body):
 return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w} {h}" role="img" aria-label="{title}"><style>text{{font-family:DejaVu Sans,Arial,sans-serif;font-size:18px}}.p{{stroke:#b83026}}.g{{stroke:#344054}}.h{{stroke:#b66b05}}.s{{stroke:#b04b80}}.p,.g,.h,.s{{fill:none;stroke-width:4;stroke-linejoin:round}}.r{{fill:white;stroke:#1c293a;stroke-width:2}}.t{{fill:#1c293a}}.warn{{fill:#bd2d29}}</style><rect width="100%" height="100%" fill="#fff"/>'+txt(30,35,title,25)+body+'</svg>'
switch=detail('А · ПОЛЗУНОК: обе группы отдельно, каждый элемент виден',1100,1000,'''
<text x="30" y="70" class="warn">МАКЕТ. COM/ON/OFF на реальном ползунке найти прозвонкой!</text>
<text x="30" y="115">TP4056 OUT+</text><text x="30" y="140">(плюс батареи)</text><path class="bat" d="M60 150H250"/>
<rect class="r" x="230" y="100" width="230" height="170"/><text x="255" y="125">S1A · группа 1</text>
<path class="bat" d="M250 150L430 160M430 160H510"/><circle cx="250" cy="150" r="6" fill="#b83026"/><circle cx="430" cy="160" r="6" fill="#b83026"/>
<text x="250" y="185">COM</text><text x="385" y="145">ON</text><circle cx="430" cy="225" r="6" fill="white" stroke="#1c293a" stroke-width="2"/><text x="260" y="260" style="font-size:15px">OFF: не подключать</text>
<path class="h" d="M510 160H550M583 160H860V380H1000"/><path d="M550 143L583 160L550 177Z" fill="white" stroke="#b66b05" stroke-width="3"/><path class="h" d="M583 138V182"/>
<text x="515" y="105">D1 · 1N4148</text><text x="520" y="210">A</text><text x="580" y="210">K = полоска</text>
<text x="30" y="330">ESP32 GPIO7 · HOLD</text><path class="h" d="M60 380H550M583 380H860"/><path d="M550 363L583 380L550 397Z" fill="white" stroke="#b66b05" stroke-width="3"/><path class="h" d="M583 358V402"/>
<text x="515" y="325">D2 · 1N4148</text><text x="520" y="430">A</text><text x="580" y="430">K = полоска</text>
<circle cx="860" cy="380" r="7" fill="#b66b05"/><text x="900" y="355">EN DC/DC</text>
<path class="h" d="M860 380V470"/><rect class="r" x="843" y="470" width="34" height="70"/><text x="900" y="500">REN 100 кОм</text><path class="g" d="M860 540V580H950"/><text x="955" y="586">GND</text>
<text x="30" y="610">SYS_3V3 · +3,3 В</text><path class="p" d="M60 650H170"/><rect class="r" x="170" y="633" width="110" height="34"/><text x="155" y="705">RSW 10 кОм</text>
<path class="s" d="M280 650H460V800M460 650H770"/><circle cx="460" cy="650" r="6" fill="#b04b80"/><text x="560" y="635">ESP32 GPIO3</text>
<rect class="r" x="430" y="745" width="300" height="175"/><text x="450" y="775">S1B · группа 2</text><path class="s" d="M460 800L690 810"/><circle cx="460" cy="800" r="6" fill="#b04b80"/><text x="475" y="835">COM</text>
<circle cx="690" cy="810" r="6" fill="white" stroke="#1c293a" stroke-width="2"/><text x="475" y="865">ON: не подключать</text>
<circle cx="690" cy="895" r="6" fill="#344054"/><path class="g" d="M690 895H790V950H950"/><text x="540" y="910">OFF</text><text x="960" y="957">GND</text>
<text x="30" y="965">Один ползунок переключает S1A и S1B вместе. Группы электрически НЕ соединять.</text>
<path class="p" d="M900 660V735"/><text x="850" y="640">SYS_3V3</text><rect class="r" x="883" y="735" width="34" height="70"/><text x="935" y="770">RB 100 кОм</text><path class="g" d="M900 805V850H1010"/><text x="1010" y="857">GND</text>
''')
# Identical port treatment, distinct nominal values; no guessed breakout pin numbers.
def mosfet(gps=False):
 title='Б · КЛЮЧ GPS: LOW включает питание' if gps else 'В · ПИТАНИЕ ВСЕГО LCD: LOW включает модуль'
 note='Питание 3,3 В подтверждено владельцем. Ключ и OFF новой сборки проверить.' if gps else 'Ключ разрывает VCC всего модуля. Работа SDA/SCL при OFF ещё не подтверждена.'
 resistor='RPU GPS 10 кОм (R6)' if gps else 'RPU 100 кОм'
 rg='''<path class="s" d="M60 340H450"/>''' if gps else '''<path class="s" d="M60 340H160M260 340H450"/><rect class="r" x="160" y="323" width="100" height="34"/><text x="150" y="400">RG 10 кОм</text>'''
 return detail(title,1100,850,f'''
<text x="30" y="75" class="warn">{note}</text>
<text x="465" y="115">SYS_3V3 · +3,3 В</text><path class="p" d="M550 130V250M550 170H360V220"/><circle cx="550" cy="170" r="6" fill="#b83026"/>
<rect class="r" x="450" y="250" width="200" height="180"/><text x="475" y="295">AO3401</text><text x="565" y="275">S · исток</text><text x="465" y="330">G · затвор</text><text x="565" y="415">D · сток</text>
<rect class="r" x="345" y="220" width="30" height="70"/><text x="80" y="245">{resistor}</text><path class="s" d="M360 290V340"/>{rg}<circle cx="360" cy="340" r="6" fill="#b04b80"/>
<text x="30" y="315">GPIO{6 if gps else 14}</text>
<path class="p" d="M550 430V530"/><text x="575" y="485">{'GPS_PWR' if gps else 'LCD_VCC_SW'}</text>
<rect class="r" x="400" y="530" width="380" height="180"/>
<text x="425" y="570">{'GPS: VCC' if gps else 'Весь LCD: контакт VCC'}</text>
<text x="425" y="610">{'3,3 В выбраны для V2' if gps else 'Контроллер и подсветка вместе'}</text>
<text x="425" y="660">{'GPS: GND' if gps else 'LCD: контакт GND'}</text>
<path class="g" d="M550 710V770H800"/><text x="815" y="778">GND</text>
<text x="30" y="815">{'R6: 10 кОм между G и S, как в V1; отдельный резистор GPS-ключа.' if gps else 'Pull-up стоит между G и S, со стороны затвора ПОСЛЕ RG.'}</text>
<text x="690" y="210" class="warn">Не перепутать S и D!</text>
<text x="690" y="245">Ножки переходника</text><text x="690" y="275">определить прозвонкой.</text>
''')
strap=detail('Г · I2C: адреса, кнопки и недопустимые соединения',1100,880,'''
<text x="35" y="95">DS3231 занимает адрес 0x68. MPU6050 должен быть 0x69!</text>
<text x="35" y="145">Проверенная логическая VDDIO модуля MPU</text><path class="p" d="M60 180H650"/><text x="680" y="186">MPU: AD0</text>
<text x="35" y="225" class="warn">Сначала проверить штатный резистор AD0 к земле. Не замкнуть питание.</text>
<text x="35" y="295">BME280: CSB → подтверждённая VDDIO для I2C.</text>
<path class="p" d="M60 330H650"/><text x="680" y="336">BME: CSB</text>
<text x="35" y="390">BME SDO → GND для 0x76 ИЛИ VDDIO для 0x77. Выбрать ОДНО.</text>
<text x="35" y="435">Не добавлять перемычку поверх неизвестной заводской обвязки.</text>
<text x="35" y="505">Кнопка A: GPIO1 ─── контакт / контакт ─── GND</text>
<text x="35" y="545">Кнопка B: GPIO2 ─── контакт / контакт ─── GND</text>
<text x="35" y="585">Нажато = LOW. Входы подтянуты программой. К батарее НЕ подключать.</text>
<text x="35" y="665" class="warn">GND нагрузки = TP4056 OUT−. Не соединять OUT− с B− перемычкой.</text>
<text x="35" y="710" class="warn">5V USB ESP32 не соединять с OUT+, LiPo или SYS_3V3.</text>
<text x="35" y="755">Плюс и минус никогда не являются одним проводом.</text>
<text x="35" y="805">Общий цвет — тип сигнала. Соединение есть только по точке/одинаковому имени сети.</text>
''')
for name,data in [('power-detail',switch),('gps-detail',mosfet(True)),('lcd-detail',mosfet()),('straps-detail',strap)]:(D/(name+'.svg')).write_text(data)
style='''body{font:17px/1.55 system-ui,sans-serif;color:#142536;background:#edf1f6;margin:0}main{max-width:1400px;margin:auto;padding:24px}h1{font-size:32px;margin:0 0 12px}h2{margin:32px 0 12px}p{max-width:85ch}a{color:#075b99}nav{display:flex;gap:12px;flex-wrap:wrap;margin:20px 0}.warning{background:#fff1dc;border-left:6px solid #ac5000;padding:16px}.danger{background:#ffe9e7;border-left:6px solid #bd2d29;padding:16px}.tools{position:sticky;top:0;background:#fff;border:1px solid #b4c2d0;padding:12px;z-index:4;display:flex;gap:10px;flex-wrap:wrap;align-items:center}button,input{font:inherit;padding:9px}button{cursor:pointer}#selection{flex-basis:100%;min-height:30px;font-weight:600}.canvas{overflow:auto;border:1px solid #98aabb;background:white;max-height:82vh;overscroll-behavior:contain}.canvas>svg{width:2040px;max-width:none;height:auto;display:block}.detail{background:#fff;border:1px solid #b4c2d0;margin-bottom:16px;overflow:auto}.detail svg{display:block;width:100%;min-width:820px;height:auto}table{width:100%;border-collapse:collapse;background:#fff}td,th{text-align:left;padding:10px;border:1px solid #c5cfda;font-size:14px}th{background:#dfe7ef}.tablewrap{overflow:auto}code{font-family:monospace}.pill{display:inline-block;padding:4px 10px;background:white;border:2px solid currentColor;margin:4px}.active-row{background:#fff1c9}.wire.dim{opacity:.06}.wire.active{opacity:1}.wire.active path:nth-child(2){stroke-width:7}@media print{body{background:white}.tools,nav,button,.noprint{display:none}.canvas{max-height:none;overflow:visible;border:0}.canvas>svg{width:100%}.detail svg{min-width:0}.detail{break-before:page}.tablewrap{overflow:visible}main{padding:0}.warning,.danger{break-inside:avoid}#wiretable{font-size:10px}}'''
def human(id):
 names={'bat':'Аккумулятор','tp':'TP4056','ina':'INA219','dc':'Преобразователь','p1':'Узел ползунка P1','esp':'ESP32','ch':'LED Заряд','rd':'LED Готово','sd':'SD-модуль','gps':'GPS-модуль','qgps':'Ключ GPS','a':'Кнопка A','b':'Кнопка B','buzz':'Будущий драйвер пищалки','lcd':'Дисплей LCD','rtc':'Часы DS3231','bme':'BME280','mpu':'MPU6050','mag':'Компас','bl':'Ключ подсветки'}
 for rail,(_,name) in rails.items():
  if id.startswith(rail+'_'):return 'Общая шина '+name
 return names.get(id.split('_')[0],id)
for r in records:
 r['fromModule']=human(r['from']);r['toModule']=human(r['to'])
(D/'wires.json').write_text(json.dumps(records,ensure_ascii=False,indent=2))
rows=''.join(f'<tr id="row-{r["id"]}" data-wire="{r["id"]}"><td><button data-pick="{r["id"]}">{r["id"]}</button></td><td>{html.escape(r["fromLabel"])}<br>{html.escape(r["fromModule"])}</td><td>{html.escape(r["toLabel"])}<br>{html.escape(r["toModule"])}</td><td>{"⛔ Сначала проверить" if r["blocked"] else "По базе; площадку сверить"}</td></tr>' for r in records)
legend=''.join(f'<span class="pill" style="color:{C[k]}">{t}</span>' for k,t in [('bat','Батарея / до 4,2 В'),('pwr','Только +3,3 В'),('gnd','Минус / GND'),('sda','SDA'),('scl','SCL'),('spi','SD / SPI'),('gps','GPS / UART'),('io','Кнопки / сигналы'),('hold','EN / HOLD'),('led','Катод LED: не GND')])
page='''<!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>V2 · Монтаж по одному проводу</title><style>'''+style+'''</style><main><div class="danger"><strong>Уточнение 17.11.2026:</strong> два отдельных LED на этом рисунке — прежний вариант. Для нового общего анода использовать <a href="../charging-indication.md">отдельную схему с двумя резисторами</a>. Драйвер пищалки ещё не показан: <a href="../electrical-baseline.md#additional-parts">предложение и учёт деталей</a>. Эти узлы здесь не являются окончательной монтажной схемой.</div>
<h1>V2 · Монтаж по одному проводу</h1><p>Каждый плюс, минус и сигнал нарисован отдельной линией. Нажмите провод или его номер в таблице — остальные станут бледными, а здесь появятся оба конца.</p>
<p>Основа: <a href="../electrical-baseline.md">действующая электрическая база</a>. Выбранная цель: отключать весь VCC LCD. Исходники 0.1.33 / wiring3 адаптированы. Опубликованная 0.1.32 / wiring2 поддерживает только LED-ветвь! Эта страница заменяет неудобные блок-схемы 15.11 как инструкцию чтения соединений. Не физическое размещение деталей и не готовая PCB.</p>
<div class="danger"><b>Исправление r2: OUT+ TP4056 — батарея, не выход 5 В.</b> Коричневые линии до 4,2 В и красные линии 3,3 В разделены. P1A и P1B — разные группы, между их плюсами нет перемычки. 5 В подаются только на штатный USB-C зарядника.</div><div class="danger"><b>⛔ Стоп перед пайкой.</b> Расположение контактов на рисунке условное: ищите написанное имя на своём модуле. Пунктир — соединение, которое ещё нельзя паять без проверки. LCD VCC идёт от стока ключа. Совместимость I2C при отключённом VCC, площадки LED зарядника и драйвер пищалки ещё не подтверждены.</div>
<nav><a href="#overview">Общая схема</a><a href="#power">А · Ползунок</a><a href="#gps">Б · Ключ GPS</a><a href="#lcd">В · Питание LCD</a><a href="#straps">Г · Адреса/кнопки</a><a href="#wires">Все провода</a><a href="all-wires.svg" download>Скачать SVG</a><a href="wires.csv" download>Список CSV</a></nav>
<div>'''+legend+'''</div><h2 id="overview">Одна общая схема</h2>
<div class="tools"><button id="minus" aria-label="Уменьшить схему">−</button><button id="plus" aria-label="Увеличить схему">+</button><button id="fit">Вписать по ширине</button><button id="reset">Показать все провода</button><label>Провод <input id="find" placeholder="W028" size="7"></label><div id="selection" role="status" aria-live="polite">Выберите провод. Точка = соединение; пересечение без точки = не соединять.</div></div>
<div class="canvas" id="drawing">'''+svg+'''</div>
<div class="warning"><b>Почему два минуса?</b> CELL− идёт только в B− зарядника. Общая земля нагрузки берётся с OUT−. Отдельная чёрно-серая линия между ними не нарисована, потому что такой перемычки быть не должно. <b>Почему красный и тёмный провода отдельные?</b> Это разные электрические цепи, не один кабель.</div>
<h2 id="power">А · Питание с ползунком — крупно</h2><p>Основная V2 включается ползунком. Кнопки A/B — только для интерфейса. Старой схемы включения A+B здесь нет. На рисунке обе группы в ON; OFF переключает обе одновременно.</p><div class="detail">'''+switch+'''</div>
<div class="danger"><b>⛔ Обе полоски D1/D2 смотрят к EN преобразователя.</b> GPIO7 не соединять с батарейным EN без D2. EN преобразователя — не RESET ESP32. При ON ветвь D1 держит питание при перезагрузке OTA; при OFF программа сохраняет SD через HOLD. Работу этого узла ещё нужно проверить на макете.</div>
<h2 id="gps">Б · Ключ GPS — крупно</h2><div class="detail">'''+mosfet(True)+'''</div><p>Владелец подтвердил работу того же GPS от 3 и 5 В; для V2 выбраны 3,3 В. Проверить распайку и отключение через ключ в новой сборке. Схема G/S/D не устанавливает номера контактов вашего переходника. GPS TX идёт в RX ESP32 (GPIO44), GPS RX — в TX ESP32 (GPIO43).</p>
<h2 id="lcd">В · Выбранный вариант: питание всего LCD</h2><div class="detail">'''+mosfet()+'''</div><div class="danger"><b>Выбор владельца: без вмешательства во внутренние дорожки LCD.</b> SYS_3V3 → S AO3401; D → штатный VCC дисплея; G через 10 кОм к GPIO14; 100 кОм между G и S. GND дисплея остаётся общим. SDA/SCL — общая шина с датчиками: допускаемость сигналов при VCC=0 ещё нужно подтвердить. Исходники 0.1.33 / wiring3 выполняют подачу VCC до probe и повторный power-on init. Прежняя опубликованная 0.1.32 к этому монтажу не подходит. Пунктир означает «не вводить в эксплуатацию до проверки I2C на модуле и подготовки совместимого комплекта прошивки». Если потребуется изоляция SDA/SCL, это отдельная пока не выбранная цепь.</div>
<h2 id="straps">Г · Адреса и кнопки</h2><div class="detail">'''+strap+'''</div>
<h2>LED зарядки: проверка перед переносом</h2><ol><li>Без питания найти LED цепи CHRG и STDBY на конкретной плате. Не назначать их по названиям LED1/LED2.</li><li>Зафиксировать анод и катод каждой площадки. На внешнем LED короткая ножка/плоская грань часто означает катод, но проверить тестером.</li><li>Снять штатный LED, сохранить его резистор. Анод внешнего — к площадке анода, катод — к площадке катода. По два отдельных провода.</li><li>Не оставлять два LED параллельно. Катод возвращается на управляющую площадку зарядника, НЕ на общую землю. Проверить заряд и завершение при OFF прибора, а не просто наличие USB.</li></ol>
<h2>Перед включением</h2><ol><li>Без батареи и USB проверить отсутствие короткого замыкания питания и правильность полярностей. Прозвонить два независимых контакта ползунка.</li><li>Проверить DC/DC отдельно: 3,3 В, затем EN от каждой ветви. Не снимать перемычку с модуля по чужому фото.</li><li>Подключать модули по очереди, проверяя питание и I2C. У MPU адрес 0x69; у RTC 0x68.</li><li>На тестовой SD проверить OFF посреди записи и следующий ON, затем reset при ON и OTA. Ошибка SD может оставить питание включённым.</li><li>USB ESP32 не считать безопасно развязанным от внешних 3,3 В: сначала проверить E11 в базе. Размеры и посадки модулей ещё нужно измерить.</li></ol>
<h2>Как читать названия</h2><ul><li><b>SYS_3V3</b> — общий плюс 3,3 В после преобразователя.</li><li><b>GND / GND_SYS</b> — общий минус нагрузки от OUT− зарядника. Это не перемычка к B−.</li><li><b>COM</b> — общий контакт одной группы ползунка. ON/OFF — какие контакты с ним замыкаются.</li><li><b>A / K</b> — анод / катод диода. Полоска корпуса — катод K.</li><li><b>G / S / D</b> — затвор / исток / сток транзистора. По виду переходника их не назначать.</li></ul><h2 id="wires">Каждый провод: откуда → куда</h2><p>Номер W — номер линии на схеме, не номер ножки. Все проверки экземпляра из электрической базы действуют и для сплошных линий.</p><div class="tablewrap"><table id="wiretable"><thead><tr><th>Провод</th><th>Откуда</th><th>Куда</th><th>Статус</th></tr></thead><tbody>'''+rows+'''</tbody></table></div>
<p>Файлы V1 <code>docs/assembly.html</code> и <code>docs/wiring.html</code> использованы как образец подачи. Их GPIO, DS1302, OLED, компас и старые советы по преобразователю сюда не переносились. Для V2 действуют проверки <a href="../electrical-baseline.md#pre-pcb-checks">E01–E16</a>.</p></main><script>const data='''+json.dumps(records,ensure_ascii=False)+''';const nodes=[...document.querySelectorAll('.wire')];const svg=document.querySelector('#drawing svg');let zoom=1;function pick(id){const r=data.find(x=>x.id===id);if(!r)return;nodes.forEach(x=>{x.classList.toggle('dim',x.dataset.id!==id);x.classList.toggle('active',x.dataset.id===id)});document.querySelectorAll('#wiretable tr').forEach(x=>x.classList.toggle('active-row',x.dataset.wire===id));document.getElementById('selection').textContent=id+' · '+r.fromModule+' · '+r.fromLabel+' → '+r.toModule+' · '+r.toLabel+(r.blocked?' · ⛔ НЕ ПАЯТЬ ДО ПРОВЕРКИ':'');document.getElementById('find').value=id}nodes.forEach(x=>{x.addEventListener('click',()=>pick(x.dataset.id));x.addEventListener('keydown',e=>{if(['Enter',' '].includes(e.key)){e.preventDefault();pick(x.dataset.id)}})});document.querySelectorAll('[data-pick]').forEach(x=>x.onclick=()=>{pick(x.dataset.pick);document.getElementById('overview').scrollIntoView()});document.getElementById('find').oninput=e=>pick(e.target.value.trim().toUpperCase());document.getElementById('reset').onclick=()=>{nodes.forEach(x=>x.classList.remove('dim','active'));document.querySelectorAll('.active-row').forEach(x=>x.classList.remove('active-row'));document.getElementById('selection').textContent='Все провода показаны. Точка = соединение; пересечение без точки = не соединять.';document.getElementById('find').value=''};function resize(){svg.style.width=(2040*zoom)+'px'}document.getElementById('plus').onclick=()=>{zoom=Math.min(2.5,zoom+.2);resize()};document.getElementById('minus').onclick=()=>{zoom=Math.max(.35,zoom-.2);resize()};document.getElementById('fit').onclick=()=>{zoom=document.getElementById('drawing').clientWidth/2040;resize()};</script></html>'''
(D/'index.html').write_text(page)
print('Built',len(records),'separate wire paths;',len(P),'terminals/taps')
# Human-facing wire list uses module names, not internal drawing identifiers.
with (D/'wires.csv').open('w',newline='') as f:
 w=csv.writer(f,lineterminator="\n");w.writerow(['Провод','Модуль откуда','Контакт откуда','Модуль куда','Контакт куда','Статус'])
 for r in records:w.writerow([r['id'],r['fromModule'],r['fromLabel'],r['toModule'],r['toLabel'],'СТОП: сначала проверить' if r['blocked'] else 'Сверить площадку по маркировке'])
