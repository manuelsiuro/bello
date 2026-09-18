#!/usr/bin/env bash
# The details on the phone (FR-PAGE): a page served by the tablet, a QR code on the face.
#
#   page.sh demo            publish the built-in recipe and show its code — no provider, no Wi-Fi needed
#   page.sh status          server state, Wi-Fi address, whether a code is on the face
#   page.sh off             hide the code and stop the server
#   page.sh open            forward the port and open the last published page on the Mac
#   page.sh ask ["question"] the whole flow through the providers: ask (crêpes by default), then say "oui"
source "$(dirname "$0")/common.sh"
send() { adb_ shell am start -n "$PKG/.ui.MainActivity" --es page "$1" >/dev/null; }
log() { adb_ shell "cat $DEVICE_FILES/logs/bello.log" | tr -d '\r'; }

case "${1:-status}" in
  demo)
    send demo
    sleep 3
    log | grep -E "PAGE_SERVER|PAGE_PUBLISH|PAGE_SHOWN|PAGE_DEMO" | tail -4
    ;;
  status)
    send status
    sleep 1.5
    log | grep "PAGE_STATUS" | tail -1
    ;;
  off)
    send off
    sleep 1.5
    log | grep -E "PAGE_HIDDEN|PAGE_SERVER_DOWN" | tail -2
    ;;
  open)
    line="$(log | grep "PAGE_PUBLISHED" | tail -1)"
    [ -n "$line" ] || { echo "no page published yet — try: page.sh demo" >&2; exit 1; }
    url="$(sed -E 's/.*url=(http:[^ ]+).*/\1/' <<<"$line")"
    port="$(sed -E 's#http://[^:]+:([0-9]+)/.*#\1#' <<<"$url")"
    path="$(sed -E 's#http://[^/]+(/.*)#\1#' <<<"$url")"
    adb_ forward "tcp:$port" "tcp:$port" >/dev/null
    echo "$url  →  http://127.0.0.1:$port$path"
    open "http://127.0.0.1:$port$path"
    ;;
  ask)
    "$ROOT/scripts/ask.sh" "${2:-Donne-moi la recette des crêpes}" 15
    "$ROOT/scripts/text.sh" oui
    sleep 40
    log | grep -E "PAGE_|LLM_OK|assistant: answer|announcement" | tail -8
    ;;
  *) sed -n '2,8p' "$0"; exit 1 ;;
esac
