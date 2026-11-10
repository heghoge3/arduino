#!/usr/bin/env python3
"""Electrical review only: ngspice plus Python stdlib. Does not build device firmware.

Run: python run_review.py --ngspice /path/to/ngspice --output /tmp/power-review
Models are deliberately approximate; see README.md before interpreting results.
"""
import argparse
import csv
import itertools
import json
from pathlib import Path
import re
import subprocess

HERE = Path(__file__).resolve().parent
COLUMNS = ['time', 'vp', 'out', 'gate', 'ain', 'hold', 'path_current', 'rail']


def run_spice(binary, folder, text):
    folder.mkdir(parents=True, exist_ok=True)
    source = folder / 'case.cir'
    source.write_text(text)
    run = subprocess.run([binary, '-b', str(source)], text=True, capture_output=True, timeout=30, cwd=folder)
    (folder / 'ngspice.log').write_text(run.stdout + run.stderr)
    if run.returncode or re.search(r'(?im)^(fatal|error)', run.stdout + run.stderr):
        raise RuntimeError(f'ngspice failed: {folder}')
    return run.stdout


def header(vbat=3.6, temp=25, beta=100, stress=False, vth=-0.9):
    return f'''Power latch electrical review (not a full device model)
.param BIS={1e-15 if stress else 1e-14} BETA={beta}
.param DIS={1e-12 if stress else 2.5e-9} DN={1.6 if stress else 1.75} VTH={vth}
.temp {temp}
.include "{HERE / 'models.lib'}"
.include "{HERE / 'latch.cir'}"
'''


def dc_case(binary, output, case_id, *, vbat=3.6, temp=25, beta=100,
            pull=45000, tol=1, stress=False, a=0, hold=0, rail=3.3,
            load=33, bleed=100000, leak=0, weak_hold=False, fault=None):
    folder = output / 'dc' / f'{case_id:04d}'
    # Low-level contact switching; rail is forced to test partially powered GPIO states.
    # Injected leakage is an independent stress source, not a prediction of a real part.
    net = header(vbat, temp, beta, stress) + f'''
Vbat vp 0 {vbat}
Vrail rail 0 {rail}
Vhold drv 0 {hold}
RH drv hold {45000 if weak_hold else 100}
Vbtn ctl 0 {a}
SA vp start ctl 0 BUTTON
XL vp out start hold ain 0 LATCH RIN={10000*tol} RBE={100000/tol} RGATE={100000/tol} RSTOP={10000*tol} CGATE=100n RBLEED={bleed*tol}
RA rail ain {pull}
Rload out 0 {load}
Bleak vp out I={leak}*tanh(max(v(vp)-v(out),0))
.control
op
print v(out) v(xl.gate) v(ain) v(start) v(hold) i(vbat) i(vhold) i(vrail)
quit
.endc
.end
'''
    if fault:
        folder.mkdir(parents=True, exist_ok=True)
        bad = (HERE/'latch.cir').read_text()
        if fault == 'D1_short': bad=bad.replace('D1 start or_in D_REVIEW','RFAULT start or_in 0.001')
        elif fault == 'D2_short': bad=bad.replace('D2 hold or_in D_REVIEW','RFAULT hold or_in 0.001')
        else: raise ValueError(fault)
        (folder/'fault.cir').write_text(bad)
        net=net.replace(str(HERE/'latch.cir'),str(folder/'fault.cir'))
    text = run_spice(binary, folder, net)
    values = {m.group(1): float(m.group(2)) for m in re.finditer(
        r'(?m)^([vi]\([^)]+\))\s*=\s*([-+\deE.]+)', text)}
    if len(values) != 8:
        raise RuntimeError(f'Missing DC output: {folder}')
    return values


def transient(binary, output, name, *, old=False, event='cycle', cap=47e-6,
              rbatt=.15, vbat=4.2, load=33, temp=25, beta=100,
              gate_cap=None, vth=-.9, rise=1e-6):
    folder = output / 'transient' / name
    if event == 'stuck':
        button = 'PWL(0 0 10m 0 10.001m 1 90m 1 90.001m 0)'
    elif event == 'insert':
        button = '0'
    elif event == 'bounce':
        button = 'PWL(0 0 10m 0 10.001m 1 12m 1 12.001m 0 13m 0 13.001m 1 30m 1 30.001m 0)'
    else:
        button = 'PWL(0 0 10m 0 10.001m 1 30m 1 30.001m 0)'
    request = 'PWL(0 0 25m 0 25.001m 1 60m 1 60.001m 0)'
    if event in ('cancel', 'insert'):
        request = '0'
    if event == 'late_hold':
        request = 'PWL(0 0 65m 0 65.001m 1)'
    if event == 'handoff_same_time':
        request = 'PWL(0 0 30m 0 30.001m 1 60m 1 60.001m 0)'
    if event == 'reset':
        request = 'PWL(0 0 25m 0 25.001m 1)'
    gate_cap = gate_cap if gate_cap is not None else (10e-9 if old else 100e-9)
    reset_enable = 'PWL(0 1 60m 1 60.001m 0)' if event == 'reset' else '1'
    net = header(vbat, temp, beta, vth=vth) + f'''
Vbat batt 0 PWL(0 0 {rise} {vbat})
RBAT batt vp {rbatt}
Vbtn ctl 0 {button}
SA vp start ctl 0 BUTTON
Vrequest req 0 {request}
* Smooth, idealised converter-output proxy. No energy/UVLO/startup accuracy implied.
Brail rail 0 V=3.3*min(1,max(0,(v(out)-1.8)/0.9))
Bhold drv 0 V=v(req)*v(rail)
Vreset en 0 {reset_enable}
SH drv hold en 0 BUTTON
CH hold 0 5p
XL vp out start hold ain 0 LATCH CGATE={gate_cap} RSTOP={.001 if old else 10000} RBLEED={1e12 if old else 100000}
RA rail ain {10000 if old else 45000}
Cload out 0 {cap}
Rload out 0 {load}
.control
set wr_singlescale
set wr_vecnames
tran 2u 140m
wrdata trace.txt v(vp) v(out) v(xl.gate) v(ain) v(hold) i(v.xl.vpath) v(rail)
quit
.endc
.end
'''
    run_spice(binary, folder, net)
    rows = [[float(x) for x in line.split()] for line in (folder / 'trace.txt').read_text().splitlines()[1:]]
    def at(t):
        return min(rows, key=lambda row: abs(row[0]-t))
    peak = max(row[6] for row in rows)
    # Approximate energy for the entire PMOS path, including model resistances.
    energy = sum(.5*((r[1]-r[2])*r[6]+(l[1]-l[2])*l[6])*(r[0]-l[0])
                 for l, r in zip(rows, rows[1:]) if r[0] >= .06)
    return {'peak_path_a': peak, 'lowest_vp_start_v': min(r[1] for r in rows if .01 < r[0] < .02),
            'out_40ms_v': at(.04)[2], 'out_80ms_v': at(.08)[2], 'out_140ms_v': rows[-1][2],
            'out_before_button_max_v': max(r[2] for r in rows if r[0] < .009),
            'turnoff_path_energy_j_approx': energy}, rows


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--ngspice', default='ngspice')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output = args.output.resolve()
    args.output.mkdir(parents=True, exist_ok=True)
    version = subprocess.check_output([args.ngspice, '--version'], text=True)
    result = {'engine': next(s.strip() for s in version.splitlines() if 'ngspice-' in s),
              'limitations': 'Engineering component models; no physical board, real regulator, firmware, SOA or thermal validation.',
              'dc': {}, 'transients': {}, 'negative_controls': {}}
    records = []
    margins = {'gate_on_min_abs_v': 100, 'gate_off_max_abs_v': 0,
               'a_high_min_fraction': 1, 'a_low_max_fraction': 0,
               'unpowered_a_max_abs_v': 0, 'hold_backfeed_max_a': 0}
    case_id = 0
    configs = itertools.product([3.0, 3.6, 4.2], [-20, 25, 60], [40, 300], [30000, 100000], [.95, 1.05])
    states = [(0,0,0),(1,0,0),(1,0,3.3),(0,2.64,3.3),(1,3.3,3.3),(0,0,3.3)]
    for vb, temp, beta, pull, tol in configs:
        for a, hold, rail in states:
            case_id += 1
            vals = dc_case(args.ngspice, args.output, case_id, vbat=vb, temp=temp,
                           beta=beta, pull=pull, tol=tol, stress=tol>1, a=a, hold=hold, rail=rail,
                           load=vb)  # ~1 A load, not a claimed device draw
            gate_abs = vb-vals['v(xl.gate)']
            on = bool(a or hold)
            if on:
                assert gate_abs >= 2.5, (case_id,'gate',vals)
                assert vals['v(out)'] > .9*vb, (case_id,'output',vals)
                margins['gate_on_min_abs_v'] = min(margins['gate_on_min_abs_v'],gate_abs)
            else:
                assert abs(gate_abs) < .1 and abs(vals['v(out)']) < .1, (case_id,'off',vals)
                margins['gate_off_max_abs_v'] = max(margins['gate_off_max_abs_v'],abs(gate_abs))
            if rail:
                ratio=vals['v(ain)']/rail
                assert ratio < .25 if a else ratio > .75, (case_id,'input',vals)
                if a: margins['a_low_max_fraction']=max(margins['a_low_max_fraction'],ratio)
                else: margins['a_high_min_fraction']=min(margins['a_high_min_fraction'],ratio)
            else:
                margins['unpowered_a_max_abs_v']=max(margins['unpowered_a_max_abs_v'],abs(vals['v(ain)']))
                assert abs(vals['v(ain)']) < .3, (case_id,'unpowered input',vals)
            if not hold:
                margins['hold_backfeed_max_a']=max(margins['hold_backfeed_max_a'],vals['i(vhold)'])
            records.append({'case':case_id,'vbat':vb,'temp':temp,'beta':beta,'pull':pull,'tol':tol,
                            'a':a,'hold':hold,'rail':rail,**vals})
    result['dc']={'passing_cases':case_id,'selected_margins':margins,
                  'pullup_sweep_note':'30k..100k is a chosen sensitivity interval, not Espressif guaranteed min/max.'}
    with (args.output/'dc.csv').open('w') as f:
        writer=csv.DictWriter(f,fieldnames=records[0].keys(),lineterminator='\n');writer.writeheader();writer.writerows(records)
    print('DC cases passed:',case_id,flush=True)

    scenarios=[('baseline_cycle',{'old':True}),('review_cycle',{}),
               ('cancel',{'event':'cancel'}),('stuck_a',{'event':'stuck'}),
               ('reset_hiz',{'event':'reset'}),('bounce_a',{'event':'bounce'}),
               ('same_time_handoff',{'event':'handoff_same_time'}),
               ('late_hold',{'event':'late_hold'}),
               ('low_battery',{'vbat':3,'rbatt':.15}),
               ('larger_input_cap',{'cap':220e-6}),
               ('high_load',{'load':4.2}),
               ('cold_low_gain',{'temp':-20,'beta':40}),
               ('stiff_battery',{'rbatt':.05}),('high_source_resistance',{'rbatt':1}),
               ('battery_insert',{'event':'insert','rise':1e-9,'vth':-.5}),
               ('battery_insert_without_gate_cap',{'event':'insert','rise':1e-9,'vth':-.5,'gate_cap':1e-15})]
    for name,kw in scenarios:
        values,rows=transient(args.ngspice,args.output,name,**kw)
        result['transients'][name]=values
        assert abs(values['out_140ms_v']) < .1, (name, 'final off state', values)
        assert values['out_before_button_max_v'] < .1, (name, 'unrequested start', values)
        if name in ('cancel','reset_hiz','late_hold','battery_insert'):
            assert abs(values['out_140ms_v']) < .1,(name,values)
        elif name not in ('baseline_cycle','battery_insert_without_gate_cap'):
            assert values['out_40ms_v'] > .8*kw.get('vbat',4.2),(name,values)
        if name=='stuck_a': assert values['out_80ms_v']>4,(name,values)
        if name in ('baseline_cycle','review_cycle'):
            # Keep manageable trace for plotting/review; full trace remains in output tree.
            with (args.output/f'{name}.csv').open('w') as f:
                writer=csv.writer(f,lineterminator='\n');writer.writerow(COLUMNS);writer.writerows(rows[::10])
    assert result['transients']['review_cycle']['peak_path_a'] < result['transients']['baseline_cycle']['peak_path_a']

    # Negative controls: known integration risks; these are expected failures of an unrestricted claim.
    case_id+=1
    weak=dc_case(args.ngspice,args.output,case_id,a=0,hold=3.3,weak_hold=True)
    assert weak['v(out)'] > 3
    result['negative_controls']['wrong_gpio_weak_pullup'] = weak
    for name,bleed in [('no_bleed',1e12),('with_bleed',100000)]:
        case_id+=1
        vals=dc_case(args.ngspice,args.output,case_id,vbat=4.2,a=0,hold=0,rail=0,
                     bleed=bleed,load=1e12,leak=5e-6)
        result['negative_controls'][name]=vals
    assert result['negative_controls']['no_bleed']['v(out)']>2.5
    assert result['negative_controls']['with_bleed']['v(out)']<.6
    for fault,a,hold in [('D1_short',0,3.3),('D2_short',1,0)]:
        case_id+=1
        vals=dc_case(args.ngspice,args.output,case_id,a=a,hold=hold,fault=fault)
        result['negative_controls'][fault]=vals
    assert result['negative_controls']['D1_short']['v(ain)'] < .825
    assert result['negative_controls']['D2_short']['i(vhold)'] > .01
    case_id+=1
    vals=dc_case(args.ngspice,args.output,case_id,a=1,hold=0,rail=0,pull=1e12)
    assert abs(vals['v(ain)'])<.3
    result['dc']['unpowered_gpio_pullup_disabled']=vals
    folder=args.output/'reverse_supply'
    net=header()+'''
Vbat vp 0 3.3
Vusb ext 0 4.2
Rusb ext out 1
Vhold hold 0 0
Rstart start 0 1e9
XL vp out start hold ain 0 LATCH RSTOP=10k CGATE=100n RBLEED=100k
Rin ain 0 45k
.control
op
print v(out) i(vbat)
quit
.endc
.end
'''
    text=run_spice(args.ngspice,folder,net)
    reverse=float(re.search(r'(?m)^i\(vbat\)\s*=\s*([-+\deE.]+)',text)[1])
    assert reverse>0.01
    result['negative_controls']['forced_reverse_supply']={'battery_charge_current_a_proxy':reverse,
        'note':'4.2 V forced on drain through 1 ohm; NOT a model of actual ESP USB/regulator path.'}
    (args.output/'results.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result,indent=2))

if __name__=='__main__': main()
