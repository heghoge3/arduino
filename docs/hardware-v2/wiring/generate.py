"""Derived wiring sheets. Source of truth: ../electrical-baseline.md."""
from pathlib import Path
import subprocess,re,csv
from reportlab.platypus import SimpleDocTemplate,Paragraph,Table,TableStyle,Spacer
from reportlab.lib.pagesizes import A3,landscape
from reportlab.lib.styles import getSampleStyleSheet
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.lib import colors
from pypdf import PdfReader,PdfWriter
from xml.sax.saxutils import escape
D=Path(__file__).resolve().parent
header='''digraph wiring { graph [rankdir=LR, bgcolor="white", fontname="DejaVu Sans", fontsize=16, labelloc=t, pad=0.35, nodesep=0.55, ranksep=0.85, size="16,10.5", ratio=compress]; node [shape=box, style="rounded", fontname="DejaVu Sans", fontsize=12, margin="0.18,0.12"]; edge [fontname="DejaVu Sans", fontsize=10, arrowsize=0.7];'''
power=r'''
graph [rankdir=TB,label="V2 / 0.1.32 wiring2 — Лист 1. Питание и ползунок\nМ: схема макета, аппаратной приёмки нет. Имена контактов, НЕ порядок ножек."];
bat [label="LiPo 1S\nCELL+ / CELL−"];
tp [label="TP4056 + защита\nB+ / B− — батарея\nOUT+ = VPROT\nOUT− = GND_SYS"];
usb [label="Наружный USB-C\nзарядной платы"];
ina [label="INA219, шунт\nVIN+ → VIN−\nVCC = SYS_3V3\nGND = GND_SYS"];
dc [label="XL / TPS63020, выход 3,3 В\nVIN = VBAT_LOAD\nGND = GND_SYS\nOUT = SYS_3V3\nEN = EN_DCDC"];
rail [label="SYS_3V3\nESP32: 3V3\nLCD: VCC\nSD: VCC\nRTC/BME/MPU/компас/INA: VCC\nВходы ключей GPS и LED\nВсе допустимости VCC проверить"];
bat -> tp [label="CELL+ → B+\nCELL− → B−"];
usb -> tp [label="Штатный USB-C, 5 В"];
tp -> ina [label="OUT+ → VIN+ (VPROT)"];
ina -> dc [label="VIN− → VIN (VBAT_LOAD)"];
dc -> rail [label="OUT → SYS_3V3, 3,3 В"];
s1a [label="S1A, первая независимая группа\nCOM = VPROT\nON → анод D1\nOFF = не подключать"];
d1 [label="D1 1N4148\nA → |>| → K\nK — сторона полоски"];
d2 [label="D2 1N4148\nA → |>| → K\nK — сторона полоски"];
esp [label="ESP32 GPIO7\nHOLD: HIGH держит EN"];
tp -> s1a [label="OUT+ (до шунта)"];
s1a -> d1 [label="ON → A"];
d1 -> dc [label="K → EN_DCDC"];
esp -> d2 [label="GPIO7 → A"];
d2 -> dc [label="K → EN_DCDC"];
ren [label="REN 100 кОм\nEN_DCDC → GND_SYS (М)"];
dc -> ren [label="EN"];
rb [label="RB 100 кОм\nSYS_3V3 → GND_SYS (М)"];
rail -> rb;
s1b [label="S1B, вторая группа\nCOM = POWER_SWITCH / GPIO3\nON = не подключать\nOFF = GND_SYS"];
rsw [label="RSW 10 кОм (М)\nSYS_3V3 → GPIO3"];
rail -> rsw;
rsw -> s1b [label="POWER_SWITCH"];
note [shape=note,color="#b33121",label="Проверить E02/E03/E05/E06/E08/E11\nB− НЕ соединять с OUT− перемычкой.\nGPIO7 НЕ соединять с EN без D2.\nGPIO3 НЕ соединять с батареей.\nEN модуля может быть связан с VIN — проверить!\nUSB ESP32: развязка не определена.\nНе объединять 5V USB ESP32 с LiPo/VPROT.\nON/OFF — запрос выключения, не разрыв батареи.\nВсе GND нагрузки = OUT−; штатный USB TP4056 не переделывать."];
rail -> note [style=invis];
}
'''
signals=r'''
graph [label="V2 / 0.1.32 wiring2 — Лист 2. Сигналы всех модулей\nК: номера GPIO сверены с прошивкой. П: порядок площадок и уровни модулей проверить."];
esp [label="ESP32-S3-Zero\nВсе GPIO — логика 3,3 В\nПитание 3V3 = SYS_3V3\nGND = GND_SYS"];
bus [label="Общая I2C, 100 кГц\nSDA = GPIO8\nSCL = GPIO9\nPull-up только к SYS_3V3\nУчесть резисторы всех модулей"];
esp -> bus [dir=both,label="8 SDA / 9 SCL"];
lcd [label="LCD GM12864-59N / ST7567\nSDA → SDA8; SCL → SCL9\nVCC=SYS_3V3; GND=GND_SYS\n0x3E или 0x3F\nТолько 4 внешних контакта"];
rtc [label="DS3231, 0x68\nSDA → SDA8; SCL → SCL9\nVCC=SYS_3V3; GND=GND_SYS\nNC не подключать; штатный резерв VBAT\nПроверить батарейку/зарядную цепь"];
bme [label="BME280, 0x76/0x77\nSDI/SDA → SDA8; SCK/SCL → SCL9\nVCC=SYS_3V3; GND=GND_SYS\nCSB=HIGH для I2C (проверить strap)\nSDO=GND: 0x76; VDDIO: 0x77"];
mpu [label="GY-521 / MPU6050, 0x69\nSDA → SDA8; SCL → SCL9\nVCC=SYS_3V3; GND=GND_SYS\nAD0=HIGH к подтверждённой VDDIO\nINT → GPIO4, HIGH; XDA/XCL не подключать"];
mag [label="GY-271, точный чип проверить\nSDA → SDA8; SCL → SCL9\nVCC=SYS_3V3; GND=GND_SYS\nГотовый драйвер QMC5883P: 0x2C\n0x0D/0x1E не доказывают работу курса"];
ina [label="INA219, 0x40\nSDA → SDA8; SCL → SCL9\nVCC=SYS_3V3; GND=GND_SYS\nVIN+/VIN− — лист 1, не питание VCC"];
bus -> {lcd rtc bme mpu mag ina} [dir=both,label="SDA / SCL"];
sd [label="MicroSD, SPI 4 МГц\nCS/SS → GPIO10\nDI/MOSI → GPIO11\nCLK/SCK → GPIO12\nDO/MISO → GPIO13\nVCC=SYS_3V3; GND=GND_SYS\nCD/DET не используется"];
esp -> sd [dir=both,label="10 CS; 11 MOSI; 12 SCK; 13 MISO"];
gps [label="GPS ATGM336H / AT6558\nTX модуля → GPIO44 (RX ESP)\nRX модуля ← GPIO43 (TX ESP)\nGND=GND_SYS; VCC=GPS_PWR (П!)\nUART 9600 8N1, 3,3 В\nАнтенна → только RF-разъём GPS"];
esp -> gps [dir=both,label="UART 43 / 44"];
btn [label="Две кнопки без фиксации\nA: GPIO1 ↔ контакт ↔ GND_SYS\nB: GPIO2 ↔ контакт ↔ GND_SYS\nINPUT_PULLUP, LOW = нажато\nПару контактов прозвонить"];
esp -> btn [dir=back,label="1 / 2"];
power [label="Ползунок / питание\nGPIO3 → S1B.COM; RSW 10k → SYS_3V3\nGPIO7 → A D2 → K EN_DCDC\nПолная силовая цепь — лист 1"];
esp -> power [dir=both,label="3 SWITCH / 7 HOLD"];
keys [label="Ключ GPS: GPIO6 (LOW=ON)\nКлюч LED LCD: GPIO14 (LOW=ON)\nСхемы и ограничения — лист 3"];
esp -> keys [label="6 / 14"];
buzz [color="#b33121",label="Пассивный зуммер\nGPIO5 → вход драйвера (TBD)\nПитание/ток/тип излучателя неизвестны\nПрямое подключение к GPIO не утверждено"];
esp -> buzz [label="5 PWM"];
service [label="Оставить сервисный доступ\nBOOT GPIO0; RESET; USB ESP32\nGPIO19 D− / GPIO20 D+ — штатный USB\nGPIO21 занят встроенным RGB\nСвободных удобных GPIO не подтверждено"];
esp -> service [style=dashed,arrowhead=none,label="внутренние связи платы ESP"];
}
'''
keys=r'''
graph [label="V2 — Лист 3. Ключи, зарядные LED и незакрытые узлы\nМ: макет. Не назначать номера ножек по рисунку похожего корпуса."];
sys [label="SYS_3V3"];
qbl [label="Q_BL AO3401, P-MOS\nS → SYS_3V3\nD → отдельный плюс LED LCD\nG → RG_BL 10k → GPIO14\nRPU_BL 100k между G и S\nLOW на GPIO14 = свет ON"];
led [label="LCD: штатная LED-цепь\nСохранить ограничитель тока\nLED− → штатный возврат/GND после проверки\nНайти и отделить постоянный плюс LED!\nТочки пайки на экземпляре неизвестны"];
sys -> qbl [label="S"];
qbl -> led [label="D → LED-питание"];
lcd [label="Питание контроллера LCD остаётся постоянным:\nVCC=SYS_3V3; GND=GND_SYS; SDA8/SCL9\nНЕ коммутировать весь VCC вместо LED"];
qgps [label="Q_GPS: верхний ключ P-MOS (М)\nS → SYS_3V3; D → GPS_PWR\nG управляется GPIO6, LOW=ON\nPull-up G→S обязателен для OFF при Hi-Z\nAO3401 — кандидат; номиналы не утверждены\nПроверить S/D/G реальной детали"];
sys -> qgps [label="S (только если GPS допускает 3,3 В)"];
gps [color="#b33121",label="GPS_PWR → GPS VCC\nНа карточке есть 5V: проверить вход/LDO\nЕсли от 3,3 В модуль не работает —\nэту ветвь НЕ разводить как окончательную\nПроверить UART HIGH и подпитку при OFF"];
qgps -> gps [label="D"];
tp [label="Зарядная плата TP4056\nСнять штатный LED CHRG\nСнять штатный LED STDBY\nШтатные резисторы СОХРАНИТЬ"];
red [label="Внешний LED «Заряд»\nАнод → площадка анода CHRG LED\nКатод → площадка катода CHRG LED"];
green [label="Внешний LED «Готово»\nАнод → площадка анода STDBY LED\nКатод → площадка катода STDBY LED"];
tp -> red [label="2 провода; полярность прозвонить"];
tp -> green [label="2 провода; полярность прозвонить"];
note [shape=note,color="#b33121",label="LED1/LED2 не назначать CHRG/STDBY по номеру!\nНе параллелить внешние и штатные LED.\nLED зарядки не питаются от SYS_3V3.\nНет утверждённой цепи драйвера зуммера.\nНет подтверждённой развязки USB ESP32.\nНет измеренных посадок/порядка выводов.\nЗакрыть E01–E16 перед заказом PCB.\nЧертежи — электрические связи, не Gerber."];
}
'''
for name,body in [('01-power',power),('02-signals',signals),('03-keys-led',keys)]:
 p=D/(name+'.dot');p.write_text(header+body)
 for fmt in ['svg','pdf','png']:
  subprocess.run(['dot','-T'+fmt,'-Gdpi=140',str(p),'-o',str(D/(name+'.'+fmt))],check=True)
pdfmetrics.registerFont(TTFont('DV','/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'))
styles=getSampleStyleSheet();styles['Normal'].fontName='DV';styles['Normal'].fontSize=8;styles['Normal'].leading=11
story=[Paragraph('V2: таблицы контактов из единой электрической базы',styles['Normal']),Spacer(1,12)]
text=(D.parent/'electrical-baseline.md').read_text();section=text[text.index('### 2.2.'):text.index('## 3.')]
tables=[];current=[]
for line in section.splitlines()+['']:
 if line.startswith('|'):
  cells=[x.strip() for x in line.strip('|').split('|')]
  if all(re.fullmatch(r'[:\- ]+',x) for x in cells):continue
  current.append(cells)
 elif current:tables.append(current);current=[]
with (D/'contacts.csv').open('w',newline='') as f:
 w=csv.writer(f);[w.writerow(row) for t in tables for row in t]
for rows in tables:
 n=len(rows[0]);widths=([145,135,35,135,220,440] if n==6 else [180,185,35,235,475])
 data=[[Paragraph(escape(re.sub(r'\[([^]]+)\]\([^)]+\)',r'\1',c).replace('**','').replace('`','')),styles['Normal']) for c in row] for row in rows]
 t=Table(data,colWidths=widths,repeatRows=1,hAlign='LEFT');t.setStyle(TableStyle([('GRID',(0,0),(-1,-1),.35,colors.grey),('BACKGROUND',(0,0),(-1,0),colors.HexColor('#e7edf1')),('VALIGN',(0,0),(-1,-1),'TOP'),('TOPPADDING',(0,0),(-1,-1),6),('BOTTOMPADDING',(0,0),(-1,-1),6)]));story.extend([t,Spacer(1,18)])
SimpleDocTemplate(str(D/'04-contact-tables.pdf'),pagesize=landscape(A3),leftMargin=24,rightMargin=24,topMargin=26,bottomMargin=26).build(story)
w=PdfWriter()
for n in ['01-power.pdf','02-signals.pdf','03-keys-led.pdf','04-contact-tables.pdf']:w.append(str(D/n))
w.write(str(D/'v2-wiring.pdf'))
print('Generated wiring PDF, SVG, PNG and derived CSV')
