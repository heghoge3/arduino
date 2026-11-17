#!/usr/bin/env python3
"""Read-only audit of this project's EasyEDA Pro V3 archive, using stdlib only.

Reads data, never executes archive contents. Reconstructs schematic connectivity
from wire endpoints/pin transforms/global flags; crossings alone do not join.
This is a narrow independent check, not EasyEDA ERC/DRC or hardware acceptance.
"""
import argparse
import collections
import csv
import hashlib
import json
import math
from pathlib import Path
import zipfile


def read_docs(path):
    docs = []
    with zipfile.ZipFile(path) as archive:
        members = [n for n in archive.namelist() if n.endswith('.epru')]
        if len(members) != 1:
            raise ValueError('Expected one .epru document stream')
        for line in archive.read(members[0]).decode('utf-8').splitlines():
            if not line.strip():
                continue
            head, body = line.split('||', 1)
            meta = json.loads(head)
            data = json.JSONDecoder().raw_decode(body)[0] if body.strip('| \t') else None
            if meta['type'] == 'DOCHEAD':
                docs.append({'head': data, 'records': []})
            else:
                docs[-1]['records'].append({'meta': meta, 'data': data})
    return docs


def records(doc, kind):
    return [r for r in doc['records'] if r['meta']['type'] == kind and r['data'] is not None]


def attributes(doc):
    result = collections.defaultdict(dict)
    for r in records(doc, 'ATTR'):
        d = r['data']
        result[d.get('parentId')][d['key']] = d['value']
    return result


def title(doc):
    return records(doc, 'META')[0]['data'].get('title', '')


def transform(point, component):
    x, y = point['x'], point['y']
    if component.get('isMirror'):
        raise ValueError('Mirrored schematic instances require an explicit transform check')
    angle = -component['rotation'] * math.pi / 180
    return (round(component['x'] + x * math.cos(angle) - y * math.sin(angle), 6),
            round(component['y'] + x * math.sin(angle) + y * math.cos(angle), 6))


def on_segment(point, start, end):
    return (abs((point[0]-start[0])*(end[1]-start[1]) -
                (point[1]-start[1])*(end[0]-start[0])) < 1e-6 and
            min(start[0], end[0])-1e-6 <= point[0] <= max(start[0], end[0])+1e-6 and
            min(start[1], end[1])-1e-6 <= point[1] <= max(start[1], end[1])+1e-6)


def audit(source):
    docs = read_docs(source)
    by_id = {d['head']['uuid']: d for d in docs}
    devices = {d['head']['uuid']: records(d, 'META')[0]['data'] for d in docs
               if d['head']['docType'] == 'DEVICE'}
    pages = [d for d in docs if d['head']['docType'] == 'SCH_PAGE' and
             not any(r['data'].get('isDelete') for r in records(d, 'DELETE_DOC'))]
    boards = [d for d in docs if d['head']['docType'] == 'PCB']
    if len(pages) != 1 or len(boards) != 1:
        raise ValueError('This checker expects one live schematic page and one PCB')
    page, board = pages[0], boards[0]
    own, pcb_own = attributes(page), attributes(board)
    pcb_components = {pcb_own[r['meta']['id']]['Designator']: r for r in records(board, 'COMPONENT')
                      if pcb_own[r['meta']['id']].get('Designator')}
    parents = {}

    def find(p):
        parents.setdefault(p, p)
        if parents[p] != p:
            parents[p] = find(parents[p])
        return parents[p]

    def union(a, b):
        parents[find(a)] = find(b)

    lines = []
    for r in records(page, 'LINE'):
        d = r['data']
        if not d.get('lineGroup'):
            continue
        a, b = (d['startX'], d['startY']), (d['endX'], d['endY'])
        lines.append((a, b))
        union(a, b)
    components, terminals, flags = {}, [], []
    for r in records(page, 'COMPONENT'):
        instance = own[r['meta']['id']]
        if not instance.get('Symbol'):
            continue
        symbol = by_id[instance['Symbol']]
        pin_attrs = attributes(symbol)
        ref = instance.get('Designator')
        if ref:
            props = (devices.get(instance.get('Device'), {}).get('attributes', {}) |
                     {k: v for k, v in instance.items() if v is not None})
            components[ref] = {'symbol': title(symbol),
                               'device': devices.get(instance.get('Device'), {}).get('title'),
                               'deviceId': instance.get('Device'), 'value': props.get('Value'),
                               'pins': [], 'footprint': None}
        for pin in records(symbol, 'PIN'):
            xy = transform(pin['data'], r['data'])
            find(xy)
            if ref:
                p = pin_attrs[pin['meta']['id']]
                terminals.append((ref, str(p['Pin Number']), p.get('Pin Name', ''), xy))
            elif instance.get('Global Net Name'):
                flags.append((xy, instance['Global Net Name']))
    for xy in list(parents):
        for a, b in lines:
            if on_segment(xy, a, b):
                union(xy, a)
    for xy, name in flags:
        union(xy, ('global', name))
    root_pins = collections.defaultdict(list)
    root_names = collections.defaultdict(set)
    for ref, num, name, xy in terminals:
        root_pins[find(xy)].append(f'{ref}.{num}')
    for xy, name in flags:
        root_names[find(xy)].add(name)
    net_names = {root: next(iter(root_names[root])) if len(root_names[root]) == 1
                 else '+'.join(sorted(root_names[root])) if root_names[root]
                 else ' / '.join(sorted(pins)) for root, pins in root_pins.items()}
    pcb_pads = collections.defaultdict(dict)
    active_pcb_ids = {r['meta']['id'] for r in pcb_components.values()}
    active_pad_keys = set()
    for pc in pcb_components.values():
        pa = pcb_own[pc['meta']['id']]
        fp_id = pa.get('Footprint') or devices.get(pa.get('Device'), {}).get('attributes', {}).get('Footprint')
        for pr in records(by_id[fp_id], 'PAD'):
            active_pad_keys.add((pc['meta']['id'], str(pr['data']['num']), pr['meta']['id']))
    for r in records(board, 'PAD_NET'):
        _, cid, num, pad_id = json.loads(r['meta']['id'])
        if cid not in active_pcb_ids or (cid, num, pad_id) not in active_pad_keys:
            continue
        name = r['data']['padNet']
        if num in pcb_pads[cid] and pcb_pads[cid][num] != name:
            raise ValueError(f'Inconsistent duplicate pad net: {cid}.{num}')
        pcb_pads[cid][num] = name
    missing = []
    num_errors = []
    for ref, num, name, xy in terminals:
        pc = pcb_components[ref]
        pcb_net = pcb_pads[pc['meta']['id']].get(num, '')
        root = find(xy)
        if (len(root_pins[root]) > 1 or root_names[root]) and not pcb_net:
            missing.append(f'{ref}.{num} ({name})')
        components[ref]['pins'].append({'number': num, 'name': name,
                                        'schematicNet': net_names[root], 'pcbNet': pcb_net})
    for ref, c in components.items():
        pc = pcb_components[ref]
        pa = pcb_own[pc['meta']['id']]
        device = devices.get(pa.get('Device'), {}).get('attributes', {})
        fp_id = pa.get('Footprint') or device.get('Footprint')
        fp = by_id[fp_id]
        pads = [r['data'] for r in records(fp, 'PAD')]
        c['footprint'] = title(fp)
        c['pads'] = [{'number': p['num'], 'xMm': round(p['centerX']*.0254, 5),
                      'yMm': round(p['centerY']*.0254, 5),
                      'holeMm': round(p['hole']['width']*.0254, 5) if p.get('hole') else None}
                     for p in pads]
        pin_nums = {p['number'] for p in c['pins']}
        pad_nums = {p['num'] for p in pads}
        if pin_nums - pad_nums:
            num_errors.append({'reference': ref, 'missingPadNumbers': sorted(pin_nums-pad_nums)})
    nets = {f'{ref}.{num}': find(xy) for ref, num, _, xy in terminals}
    checks = []

    def same(label, *pins):
        ok = len({nets[p] for p in pins}) == 1
        checks.append({'check': label, 'pins': list(pins), 'pass': ok})

    def separate(label, a, b):
        checks.append({'check': label, 'pins': [a, b], 'pass': nets[a] != nets[b]})

    same('battery positive', 'CN2.1', 'U2.2')
    same('battery negative', 'CN2.2', 'U2.3')
    same('protected battery output', 'U2.1', 'U10.8', 'SW1.2')
    same('converter input after shunt', 'U10.7', 'U9.2')
    same('system 3V3', 'U9.5', 'U1.3', 'U3.1', 'U5.1', 'U6.1', 'U7.1', 'U8.1',
         'U10.1', 'Q1.2', 'Q2.2', 'BUZZER1.1', 'D3.1', 'R1.2', 'R6.2', 'R4.2', 'R8.1')
    same('system ground', 'U2.4', 'U1.2', 'U3.2', 'U4.4', 'U5.2', 'U6.6', 'U7.2',
         'U8.5', 'U9.1', 'U9.6', 'U10.2', 'CN1.2', 'Q3.1', 'R5.2', 'R3.2', 'R8.2',
         'SW1.4', 'H1.2', 'H2.2')
    same('I2C SDA GPIO8', 'U1.11', 'U3.4', 'U5.4', 'U7.4', 'U8.2', 'U10.4', 'CN1.4')
    same('I2C SCL GPIO9', 'U1.12', 'U3.3', 'U5.3', 'U7.3', 'U8.3', 'U10.3', 'CN1.3')
    for label, pins in [
        ('button A GPIO1', ['U1.4', 'H1.1']), ('button B GPIO2', ['U1.5', 'H2.1']),
        ('slider GPIO3', ['U1.6', 'SW1.5', 'R1.1']), ('MPU INT GPIO4', ['U1.7', 'U3.8']),
        ('buzzer PWM GPIO5', ['U1.8', 'R7.1']), ('GPS gate GPIO6', ['U1.9', 'Q2.1', 'R6.1']),
        ('HOLD GPIO7 to D2 anode', ['U1.10', 'D2.2']),
        ('SD CS GPIO10', ['U1.13', 'U6.2']), ('SD MOSI GPIO11', ['U1.14', 'U6.3']),
        ('SD SCK GPIO12', ['U1.15', 'U6.4']), ('SD MISO GPIO13', ['U1.16', 'U6.5']),
        ('GPS TX to ESP RX GPIO44', ['U1.17', 'U4.3']),
        ('ESP TX GPIO43 to GPS RX', ['U1.18', 'U4.2']),
        ('LCD GPIO14 series resistor', ['U1.19', 'R2.1']),
        ('LCD gate and pullup', ['Q1.1', 'R2.2', 'R4.1']),
        ('LCD switched power', ['Q1.3', 'CN1.1']), ('GPS switched power', ['Q2.3', 'U4.5']),
        ('MPU AD0 high', ['U3.7', 'U9.5']), ('slider ON to D1 anode', ['SW1.3', 'D1.2']),
        ('EN and diode cathodes', ['D1.1', 'D2.1', 'U9.3', 'R3.1']),
        ('buzzer base', ['R7.2', 'Q3.2', 'R5.1']),
        ('buzzer collector and flyback anode', ['Q3.3', 'BUZZER1.2', 'D3.2'])]:
        same(label, *pins)
    for label, a, b in [
        ('no direct 3V3-ground short', 'U9.5', 'U2.4'),
        ('battery minus separate from protected ground', 'U2.3', 'U2.4'),
        ('shunt not bypassed in carrier wires', 'U10.7', 'U10.8'),
        ('SDA separate from SCL', 'U1.11', 'U1.12'),
        ('GPS drain not tied to slider input', 'Q2.3', 'SW1.5'),
        ('GPS power switched', 'Q2.3', 'U9.5'), ('LCD power switched', 'Q1.3', 'U9.5'),
        ('EN separate from GPIO7 by diode', 'U9.3', 'U1.10')]:
        separate(label, a, b)
    for ref, value in {'R1':'10kΩ', 'R2':'10kΩ', 'R3':'100kΩ', 'R4':'100kΩ',
                       'R5':'100kΩ', 'R6':'10kΩ', 'R7':'220Ω', 'R8':'100kΩ'}.items():
        checks.append({'check': f'{ref} nominal {value}', 'pass': components[ref]['value'] == value})
    # Correlate nonblank PCB nets with current schematic, independently of net names.
    pcb_roots = collections.defaultdict(set)
    for ref, num, name, xy in terminals:
        net = pcb_pads[pcb_components[ref]['meta']['id']].get(num, '')
        if net:
            pcb_roots[net].add(find(xy))
    conflicts = [name for name, roots in pcb_roots.items() if len(roots) != 1]
    return {'source': source.name, 'sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
            'scope': 'Read-only static extraction; no EasyEDA ERC/DRC or hardware acceptance',
            'components': components, 'componentCount': len(components),
            'symbolPinCount': len(terminals), 'wireSegmentCount': len(lines),
            'schematicNetCount': len(root_pins), 'checks': checks,
            'missingPcbNets': missing, 'pcbNetConflicts': conflicts,
            'missingFootprintPadNumbers': num_errors,
            'pcbTracks': len(records(board, 'LINE')),
            'warning': 'Internal module connections, cable orientation, geometries and USB/EN behaviour need separate checks'}


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('archive', type=Path)
    ap.add_argument('output', type=Path)
    args = ap.parse_args()
    result = audit(args.archive)
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output/'audit.json').write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n')
    with (args.output/'pins.csv').open('w', newline='') as f:
        w = csv.writer(f)
        w.writerow(['Reference','Pin','Name','Schematic net','PCB net','Footprint'])
        for ref, c in result['components'].items():
            for p in c['pins']:
                w.writerow([ref,p['number'],p['name'],p['schematicNet'],p['pcbNet'],c['footprint']])
    print(json.dumps({k: v for k,v in result.items() if k not in ['components','checks']},ensure_ascii=False,indent=2))
    print(f"Baseline checks: {sum(c['pass'] for c in result['checks'])}/{len(result['checks'])}")


if __name__ == '__main__':
    main()
