#!/usr/bin/env python3
"""A fake OpenAI-compatible endpoint, to test the gateway's fallback from the Mac.

The tablet reaches it through `adb reverse`, so no keys and no quota are involved.

    python3 scripts/tools/fake_llm.py 8099 --status 429 --retry-after 30
    python3 scripts/tools/fake_llm.py 8098 --text "[happy] Je suis le deuxieme cerveau."
"""
import argparse
import json
import time
from http.server import BaseHTTPRequestHandler, HTTPServer


def handler_for(args):
    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
            try:
                question = json.loads(body)["messages"][-1]["content"]
            except Exception:
                question = "?"
            print(f"[{args.port}] ask: {question[:80]}", flush=True)
            if args.delay:
                time.sleep(args.delay)
            if args.status != 200:
                payload = json.dumps({"error": {"message": f"fake {args.status}"}}).encode()
                self.send_response(args.status)
                if args.retry_after:
                    self.send_header("Retry-After", str(args.retry_after))
            else:
                payload = json.dumps({
                    "choices": [{"message": {"role": "assistant", "content": args.text}}],
                    "model": "fake",
                }).encode()
                self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def log_message(self, *_):
            pass

    return Handler


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("port", type=int)
    p.add_argument("--status", type=int, default=200)
    p.add_argument("--retry-after", type=int, default=0)
    p.add_argument("--text", default="[happy] Reponse de secours.")
    p.add_argument("--delay", type=float, default=0)
    args = p.parse_args()
    print(f"fake LLM on :{args.port} status={args.status}", flush=True)
    HTTPServer(("127.0.0.1", args.port), handler_for(args)).serve_forever()
