"""Resolve Cura's settings for the Ender-3 the way the Cura app does, for the bare CuraEngine.

CuraEngine on its own only reads `default_value`; the app first evaluates every `value` formula
(speed_wall = speed_print / 2, ...) through the definition chain and the profile stack. This does
the same for one extruder and prints `key=value` pairs separated by NUL, for scripts/stand.sh.

    python3 cura_settings.py <cura resources dir> [key=value ...]
"""

import configparser
import json
import math
import os
import sys

RES = sys.argv[1]
OVERRIDES = dict(a.split('=', 1) for a in sys.argv[2:])

MACHINE = 'creality_ender3'
EXTRUDER = 'creality_base_extruder_0'
# The stack the Cura app on this Mac uses for the Ender-3, lowest priority first:
# 0.4 mm nozzle variant, Standard Quality, PLA 0.4 standard.
PROFILES = [
    'variants/creality/creality_base_0.4.inst.cfg',
    'quality/creality/base/base_global_standard.inst.cfg',
    'quality/creality/base/base_0.4_PLA_standard.inst.cfg',
]


def load_definition(name, folder):
    with open(os.path.join(RES, folder, name + '.def.json')) as f:
        d = json.load(f)
    if 'inherits' in d:
        folder_up = 'definitions'
        settings = load_definition(d['inherits'], folder_up)
    else:
        settings = {}

    def walk(tree):
        for key, s in tree.items():
            settings[key] = {k: s[k] for k in ('default_value', 'value', 'type') if k in s}
            walk(s.get('children', {}))

    walk(d.get('settings', {}))
    for key, o in d.get('overrides', {}).items():
        entry = settings.setdefault(key, {})
        if 'value' in o:
            entry['value'] = o['value']
        if 'default_value' in o:
            entry['default_value'] = o['default_value']
            if 'value' not in o:
                entry.pop('value', None)
    return settings


settings = load_definition(MACHINE, 'definitions')
for key, s in load_definition(EXTRUDER, 'extruders').items():
    settings.setdefault(key, {}).update(s)

for path in PROFILES:
    cp = configparser.ConfigParser(interpolation=None)
    cp.read(os.path.join(RES, path))
    for key, raw in (cp['values'].items() if cp.has_section('values') else []):
        entry = settings.setdefault(key, {})
        if raw.startswith('='):
            entry['value'] = raw[1:]
        else:
            entry['default_value'] = raw
            entry.pop('value', None)
for key, raw in OVERRIDES.items():
    settings.setdefault(key, {}).update(default_value=raw)
    settings[key].pop('value', None)


def coerce(key, v):
    t = settings.get(key, {}).get('type')
    if isinstance(v, str):
        try:
            if t == 'float':
                return float(v)
            if t == 'int':
                return int(float(v))
            if t == 'bool':
                return v.strip().lower() in ('true', '1', 'yes')
        except ValueError:
            pass
    return v


resolved, failed = {}, []


class Scope(dict):
    def __missing__(self, key):
        return value(key)


def value(key):
    if key in resolved:
        return resolved[key]
    if key not in settings:
        raise KeyError(key)
    s = settings[key]
    resolved[key] = coerce(key, s.get('default_value'))   # guards against cycles
    if 'value' in s:
        try:
            resolved[key] = coerce(key, eval(str(s['value']), FUNCS, Scope()))
        except Exception:   # noqa: BLE001 — a formula we cannot evaluate keeps its default
            failed.append(key)
    return resolved[key]


FUNCS = {
    'math': math,
    'extruderValue': lambda ext, key: value(key),
    'extruderValues': lambda key: [value(key)],
    'resolveOrValue': lambda key: value(key),
    'defaultExtruderPosition': lambda: '0',
    'anyExtruderWithMaterial': lambda key: '0',
    'anyExtruderNrWithOrDefault': lambda key: '0',
    'valueFromContainer': lambda key, *a: value(key),
    'valueFromExtruderContainer': lambda key, *a: value(key),
    'map': map, 'sum': sum, 'max': max, 'min': min, 'round': round, 'int': int, 'float': float,
    'any': any, 'all': all, 'len': len, 'str': str, 'bool': bool, 'list': list, 'abs': abs,
    'filter': filter, 'sorted': sorted, 'set': set, 'range': range,
}
FUNCS['__builtins__'] = {}

for key in list(settings):
    value(key)
for key, v in resolved.items():
    if v is None:
        continue
    sys.stdout.write(f'{key}={v}\0')
print(f'resolved {len(resolved)} settings, {len(failed)} formulas kept their default',
      file=sys.stderr)
