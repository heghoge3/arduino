#!/usr/bin/env python3
"""Optional figures: requires schemdraw and matplotlib; does not alter netlists."""
from pathlib import Path
import argparse,csv
import matplotlib
matplotlib.use('Agg')
matplotlib.rcParams['svg.fonttype']='none'
matplotlib.rcParams['svg.hashsalt']='power-review'
import matplotlib.pyplot as plt
import schemdraw
import schemdraw.elements as e

HERE=Path(__file__).resolve().parent

def schematic():
    d=schemdraw.Drawing(unit=2,fontsize=10,show=False)
    def line(a,b): d.add(e.Line().at(a).to(b))
    def dot(p): d.add(e.Dot().at(p))
    def label(p,t,loc='top'): d.add(e.Label().right().at(p).label(t,loc=loc))
    def ground(p): d.add(e.Ground().at(p))
    # Every transistor has an explicit direction; net labels repeat the same node.
    q=d.add(e.PFet().right().at((1,10)))
    label((-.1,9.2),'QP\nAO3401',loc='left')
    label((1.15,10),'S',loc='right');label((1.15,8.5),'D',loc='right')
    line(q.source,(1,11));line((1,11),(5,11))
    label((2.8,11.3),'VPROT: protected battery 3.0-4.2 V')
    d.add(e.Resistor().at((3.5,11)).down().to((3.5,9.25)).label('RG 100k',loc='top'))
    d.add(e.Capacitor().at((5,11)).down().to((5,9.25)).label('C1 100n',loc='bottom'))
    line(q.gate,(5,9.25));dot((3.5,9.25));dot((5,9.25));dot((3.5,11))
    line(q.drain,(1,8));dot((1,8));line((1,8),(-.5,8))
    label((-.5,8),'VSW\nto INA / converter',loc='left')
    d.add(e.Resistor().at((1,8)).down().to((1,6.2)).label('RB 100k',loc='top'));ground((1,6.2))
    d.add(e.Resistor().at((5,9.25)).down().to((5,7)).label('RS 10k',loc='bottom'))
    n=d.add(e.BjtNpn().right().at((5,7)).anchor('collector'))
    label((5.45,6.15),'Q1\n2N3904',loc='right')
    line(n.emitter,(5,5.1));ground((5,5.1))
    by=n.base[1]
    line((4,by),n.base);dot((4,by))
    d.add(e.Resistor().at((4,by)).down().to((4,4.6)).label('R2 100k',loc='top'));ground((4,4.6))
    d.add(e.Resistor().at((2,by)).right().to((4,by)).label('R1 10k'))
    line((2,by),(2,3.4));dot((2,4.6))
    d.add(e.Diode().at((-1,4.6)).right().to((1,4.6)).label('D1 1N4148'))
    line((1,4.6),(2,4.6));label((-1,4.6),'START',loc='left')
    d.add(e.Diode().at((-1,3.4)).right().to((1,3.4)).label('D2 1N4148',loc='bottom'))
    line((1,3.4),(2,3.4));label((-1,3.4),'HOLD GPIO',loc='left')
    # A has a single NO contact. START also drives D1 in the left panel.
    label((9,11),'VPROT')
    d.add(e.Switch().at((9,11)).down().length(2).label('A',loc='top'))
    line((9,9),(9,8));dot((9,8));label((9,8),'START',loc='left')
    d.add(e.Resistor().at((9,8)).right().to((11,8)).label('R3 10k'))
    n2=d.add(e.BjtNpn().right().at((11.5,8)).anchor('base'))
    line((11,8),n2.base);dot((11,8))
    d.add(e.Resistor().at((11,8)).down().to((11,6.3)).label('R4 100k',loc='top'));ground((11,6.3))
    label((12.7,7.35),'Q2\n2N3904',loc='right')
    line(n2.emitter,(n2.emitter[0],6.3));ground((n2.emitter[0],6.3))
    cy=n2.collector[1];line(n2.collector,(14,cy));dot((14,cy))
    d.add(e.Resistor().at((14,11)).down().to((14,cy)).label('internal',loc='top'))
    label((14,11),'3.3 V');label((14,cy),'GPIO A',loc='right')
    d.add(e.Resistor().at((17,11)).down().to((17,cy)).label('internal',loc='top'))
    label((17,11),'3.3 V');dot((17,cy));label((17,cy),'GPIO B',loc='right')
    d.add(e.Switch().at((17,cy)).down().length(2).label('B',loc='bottom'));ground((17,cy-2))
    label((15.4,12),'ESP32 internal pull-ups\nnot additional BOM parts')
    # Local bypass is a separate labelled branch, not in the signal path.
    d.add(e.Capacitor().at((7.4,10.5)).down().length(1.5).label('C2\n100n',loc='top'))
    label((7.4,10.5),'VPROT');ground((7.4,9))
    label((8,13.7),'POWER LATCH REVIEW — functional schematic, not a verified assembly drawing')
    label((8,1.6),'Check actual part pinouts. START / VPROT labels denote common nets.\nAll GND symbols mean protected OUT-. USB isolation is NOT included.\nFirmware must configure GPIO A and B as INPUT_PULLUP before reading.')
    d.save(HERE/'schematic.svg');d.save(HERE/'schematic.png',dpi=150)


def plot_traces(output):
    fig,axes=plt.subplots(3,1,figsize=(9,7),layout='constrained')
    for name,lab,col in [('baseline_cycle','Previous proposal','#ba4935'),('review_cycle','Reviewed proposal','#137f8c')]:
        with (output/f'{name}.csv').open() as f: rows=list(csv.DictReader(f))
        t=[float(r['time'])*1000 for r in rows]
        axes[0].plot(t,[float(r['path_current']) for r in rows],label=lab,color=col)
        axes[1].plot(t,[float(r['out']) for r in rows],color=col)
        axes[2].plot(t,[float(r['out']) for r in rows],color=col)
    axes[0].set(xlim=(9.9,11.5),ylabel='Switch path current, A')
    axes[1].set(xlim=(9.9,11.5),ylabel='Switched supply, V')
    axes[2].set(xlim=(59,95),ylabel='Switched supply, V',xlabel='Simulation time, ms')
    for ax in axes: ax.grid(alpha=.25)
    axes[0].legend()
    fig.suptitle('Approximate simulation — not measured on the device\n4.2 V source, 0.15 ohm source resistance, 47 uF input capacitor, 33 ohm load')
    fig.savefig(HERE/'switching-comparison.svg')
    fig.savefig(HERE/'switching-comparison.png',dpi=130)
    plt.close(fig)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True)
    a=p.parse_args();schematic();plot_traces(a.output)
    for path in HERE.glob('*.svg'):
        path.write_text('\n'.join(line.rstrip() for line in path.read_text().splitlines())+'\n')
