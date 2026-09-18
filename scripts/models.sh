#!/usr/bin/env bash
# List the models a configured provider's key can actually use (they get retired, and a retired
# model answers 404 forever). Usage: scripts/models.sh gemini|groq|mistral|…
source "$(dirname "$0")/common.sh"
id="${1:?provider id, e.g. gemini or groq}"
config="${2:-$ROOT/config/bello.local.json}"
[ -f "$config" ] || { echo "no $config"; exit 1; }

read -r base key < <(python3 - "$config" "$id" <<'PY'
import json, sys
presets = {
    "gemini": "https://generativelanguage.googleapis.com/v1beta/openai",
    "groq": "https://api.groq.com/openai/v1",
    "mistral": "https://api.mistral.ai/v1",
    "cerebras": "https://api.cerebras.ai/v1",
    "openrouter": "https://openrouter.ai/api/v1",
}
config, wanted = json.load(open(sys.argv[1])), sys.argv[2]
for p in config.get("providers", []):
    name = p.get("id", p.get("preset"))
    if name == wanted:
        print(p.get("baseUrl") or presets.get(p.get("preset", name), ""), p.get("key", ""))
        break
else:
    sys.exit(f"no provider '{wanted}' in the config")
PY
)
[ -n "$key" ] || { echo "no key for '$id'"; exit 1; }
curl -s "$base/models" -H "Authorization: Bearer $key" |
  python3 -c "import json,sys; d=json.load(sys.stdin); [print(m['id']) for m in d.get('data', [])]" |
  sort
