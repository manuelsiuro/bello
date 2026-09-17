#!/usr/bin/env python3
"""Evaluate JavaScript in the tablet WebView via Chrome DevTools Protocol.

Usage: adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>
       python3 tools/cdp.py 'document.title'
       python3 tools/cdp.py -f script.js
"""
import json
import sys
import urllib.request

import websocket


def evaluate(expr, port=9222):
    pages = json.load(urllib.request.urlopen(f"http://127.0.0.1:{port}/json"))
    page = next(p for p in pages if p["type"] == "page")
    ws = websocket.create_connection(page["webSocketDebuggerUrl"], timeout=60, suppress_origin=True)
    ws.send(json.dumps({"id": 1, "method": "Runtime.evaluate",
                        "params": {"expression": expr, "returnByValue": True, "awaitPromise": True}}))
    while True:
        msg = json.loads(ws.recv())
        if msg.get("id") == 1:
            ws.close()
            res = msg.get("result", {})
            if "exceptionDetails" in res:
                return "EXCEPTION: " + json.dumps(res["exceptionDetails"])[:2000]
            return res.get("result", {}).get("value")


if __name__ == "__main__":
    expr = open(sys.argv[2]).read() if sys.argv[1] == "-f" else sys.argv[1]
    out = evaluate(expr)
    print(out if isinstance(out, str) else json.dumps(out, indent=2, ensure_ascii=False))
