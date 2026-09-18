#!/usr/bin/env bash
# The SFR TV decoder, straight from the Mac (docs/sfr-tv-box.md) or through Bello.
# Usage: scripts/tv.sh status | versions | on | off | key <name> | channel <n> | keys | ask "mets la 3"
# The box is found as "stb" (the router's DNS), or set TV_HOST=192.168.1.216.
source "$(dirname "$0")/common.sh"
cmd="${1:-status}"; arg="${2:-}"
host="${TV_HOST:-stb}"; port="${TV_PORT:-7682}"
case "$cmd" in
  ask) "$ROOT/scripts/text.sh" "${arg:?phrase}"; sleep "${3:-6}"
       adb_ shell "cat $DEVICE_FILES/logs/bello.log" | grep -E "router:|tv:|assistant: answer" | tail -6; exit 0 ;;
  keys) echo "power home back ok volUp volDown mute channelUp channelDown up down left right playPause stop fastForward fastBackward record 0-9"; exit 0 ;;
esac
python3 - "$host" "$port" "$cmd" "$arg" <<'PY'
import socket, os, base64, json, time, struct, sys
host, port, cmd, arg = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4]

def frame(text):
    data = text.encode(); mask = os.urandom(4); n = len(data)
    hdr = bytes([0x81]) + (bytes([0x80 | n]) if n < 126 else bytes([0x80 | 126]) + struct.pack('>H', n))
    return hdr + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(data))

def read_text(s, seconds=2.0):
    s.settimeout(seconds); buf = b''
    while True:
        chunk = s.recv(65536)
        if not chunk: return None
        buf += chunk
        if len(buf) < 2: continue
        n = buf[1] & 0x7f; i = 2
        if n == 126: n = struct.unpack('>H', buf[2:4])[0]; i = 4
        if len(buf) >= i + n: return buf[i:i + n].decode(errors='replace')

try:
    s = socket.create_connection((host, port), timeout=3)
except OSError as e:
    print(f'cannot reach {host}:{port} — {e}'); sys.exit(1)
key = base64.b64encode(os.urandom(16)).decode()
s.send((f'GET /ws HTTP/1.1\r\nHost: {host}:{port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n'
        f'Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n').encode())
resp = b''
while b'\r\n\r\n' not in resp: resp += s.recv(4096)
if b' 101 ' not in resp.split(b'\r\n')[0]: print('no WebSocket:', resp[:80]); sys.exit(1)

def ask(action, params=None):
    msg = {"action": action, "deviceId": "bello-mac", "requestId": int(time.time() * 1000)}
    if params: msg["params"] = params
    t = time.time(); s.send(frame(json.dumps(msg)))
    while True:
        text = read_text(s)
        if text is None: return None, 0
        reply = json.loads(text)
        if reply.get("requestId") == msg["requestId"]: return reply, (time.time() - t) * 1000

def status():
    r, ms = ask("getStatus"); return (r or {}).get("data", {}).get("power", "?"), ms

if cmd == 'status':
    p, ms = status(); print(f'{p} ({ms:.0f} ms)')
elif cmd == 'versions':
    r, ms = ask("getVersions", {"deviceName": "bello-mac"}); print(json.dumps((r or {}).get("data"), ensure_ascii=False), f'({ms:.0f} ms)')
elif cmd in ('on', 'off'):
    p, _ = status(); want = 'powerOn' if cmd == 'on' else 'powerOff'
    if p == want: print(f'already {p}')
    else:
        r, ms = ask("buttonEvent", {"key": "power"}); time.sleep(1.5); p2, _ = status()
        print(f'power sent ({ms:.0f} ms): {p} -> {p2}')
elif cmd == 'key':
    r, ms = ask("buttonEvent", {"key": arg or 'mute'}); print(f'{arg}: {(r or {}).get("remoteResponseCode")} ({ms:.0f} ms)')
elif cmd == 'channel':
    for d in str(int(arg)):
        r, ms = ask("buttonEvent", {"key": d}); print(f'digit {d}: {(r or {}).get("remoteResponseCode")} ({ms:.0f} ms)'); time.sleep(0.15)
else:
    print('usage: status | versions | on | off | key <name> | channel <n> | keys | ask "…"'); sys.exit(2)
s.close()
PY
