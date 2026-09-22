#!/usr/bin/env bash
# Generate one page picture from the Mac, with the keys of config/bello.local.json — to try a
# prompt before Bello does (docs/page-images.md). Saves under .cache/images/ and opens it.
#
#   image.sh "<English prompt>" [cloudflare|pollinations|anonymous]   (cloudflare by default)
source "$(dirname "$0")/common.sh"
prompt="${1:?an English image prompt}"
provider="${2:-cloudflare}"
config="$ROOT/config/bello.local.json"
out_dir="$CACHE/images"
mkdir -p "$out_dir"
out="$out_dir/$provider-$(date +%H%M%S)-$$.jpg"

# Reads images.providers[] of the local config: prints "<key> <accountId>" for the provider.
creds() {
  [ -f "$config" ] || { echo "no $config" >&2; exit 1; }
  python3 - "$config" "$1" <<'PY'
import json, sys
config, wanted = json.load(open(sys.argv[1])), sys.argv[2]
for p in config.get("images", {}).get("providers", []):
    if p.get("preset", p.get("id")) == wanted:
        print(p.get("key") or p.get("apiKey") or "-", p.get("accountId") or "-")
        break
else:
    sys.exit(f"no images provider '{wanted}' in the config")
PY
}
enc() { python3 -c 'import sys, urllib.parse; print(urllib.parse.quote(sys.argv[1], safe=""))' "$1"; }

start=$(python3 -c 'import time; print(time.time())')
case "$provider" in
  cloudflare)
    read -r key account < <(creds cloudflare)
    body="$(python3 -c 'import json, sys; print(json.dumps({"prompt": sys.argv[1], "steps": 4}))' "$prompt")"
    curl -sS "https://api.cloudflare.com/client/v4/accounts/$account/ai/run/@cf/black-forest-labs/flux-1-schnell" \
      -H "Authorization: Bearer $key" -H "Content-Type: application/json" -d "$body" |
      python3 -c '
import base64, json, sys
d = json.load(sys.stdin)
image = (d.get("result") or {}).get("image")
if not image:
    sys.exit("no image: " + json.dumps(d.get("errors") or d)[:300])
open(sys.argv[1], "wb").write(base64.b64decode(image))' "$out"
    ;;
  pollinations|anonymous)
    seed=$RANDOM
    if [ "$provider" = pollinations ]; then
      read -r key _ < <(creds pollinations)
      url="https://gen.pollinations.ai/image/$(enc "$prompt")?model=flux&width=1024&height=768&seed=$seed&nologo=true&enhance=false"
      auth=(-H "Authorization: Bearer $key")
    else
      url="https://image.pollinations.ai/prompt/$(enc "$prompt")?width=1024&height=768&seed=$seed&nologo=true"
      auth=()
    fi
    curl -sS -D "$out.headers" -o "$out" ${auth[@]+"${auth[@]}"} -A "Bello/1.0 (+https://github.com/manuelsiuro/bello)" "$url"
    grep -iE "^(HTTP|content-type|x-model-used|x-auth-status)" "$out.headers" | tr -d '\r'
    rm -f "$out.headers"
    ;;
  *) sed -n '2,5p' "$0"; exit 1 ;;
esac
end=$(python3 -c 'import time; print(time.time())')

file "$out" | grep -q -E "JPEG|PNG|WebP" || { echo "not an image:"; head -c 300 "$out"; echo; rm -f "$out"; exit 1; }
size=$(wc -c <"$out" | tr -d ' ')
dims=$(sips -g pixelWidth -g pixelHeight "$out" | awk '/pixel/{print $2}' | paste -sd x -)
echo "$out  $dims  $size bytes  $(python3 -c "print(f'{$end - $start:.1f} s')")"
[ -n "${NO_OPEN:-}" ] || open "$out"
