#!/usr/bin/env python3
"""Copies the bulb's Matter fabric from the computer's matter.js controller into the launcher's secrets.xml
(matter_bulb), so the TV can drive the bulb itself. Run after pairing the bulb (see README.md); nothing is printed.

    python3 tools/lights/matter-creds.py [node id, default 5000] [bulb's IPv6 link-local address] [bulb's IPv4]
"""
import json, os, re, sys
from xml.sax.saxutils import escape

node = sys.argv[1] if len(sys.argv) > 1 else '5000'
addrs = [a for a in sys.argv[2:]]
store = os.path.expanduser('~/.matter/shell-0')
fabric = json.load(open(os.path.join(store, 'fabrics.fabrics')))[0]
val = lambda k: json.loads(fabric[k])['__value__']
keys = fabric['keyPair']
priv = json.loads(keys['privateKey'])['__value__'] if isinstance(keys, dict) else None

icac = json.loads(fabric['intermediateCACert'])
creds = ';'.join([
    'fabric=' + val('fabricId'), 'node=' + val('nodeId'), 'peer=' + node, 'ep=1',
    'ipk=' + val('operationalIdentityProtectionKey'), 'root=' + val('rootPublicKey'),
    'noc=' + val('operationalCert'), 'icac=' + icac.get('__value__', ''), 'key=' + priv,
    # The TV reaches a link-local IPv6 address through its Wi-Fi interface.
    'addr=' + ','.join(a + '%wlan0' if a.startswith('fe80') and '%' not in a else a for a in addrs),
])

path = os.path.join(os.path.dirname(__file__), '../../launcher/res/values/secrets.xml')
xml = open(path).read()
line = '<string name="matter_bulb" translatable="false">%s</string>' % escape(creds)
if 'name="matter_bulb"' in xml:
    xml = re.sub(r'<string name="matter_bulb"[^>]*>.*?</string>', lambda _: line, xml, flags=re.S)
else:
    xml = xml.replace('</resources>', '    ' + line + '\n</resources>')
open(path, 'w').write(xml)
print('matter_bulb written to launcher/res/values/secrets.xml')
