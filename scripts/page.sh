#!/usr/bin/env bash
# The details on the phone (FR-PAGE): a page served by the tablet, a QR code on the face.
#
#   page.sh demo            publish the built-in recipe and show its code — no chat provider, no Wi-Fi needed
#                           (with a picture when config.json has an "images" block)
#   page.sh status          server state, Wi-Fi address, whether a code is on the face
#   page.sh off             hide the code and stop the server
#   page.sh open            forward the port and open the last published page on the Mac
#   page.sh ask ["question"] the whole flow through the providers: ask (crêpes by default), then say "oui"
#   page.sh images [on|off] the page's picture: switch it, or show the picture services and their state
source "$(dirname "$0")/common.sh"
send() { adb_ shell am start -n "$PKG/.ui.MainActivity" --es page "$1" >/dev/null; }
log() { adb_ shell "cat $DEVICE_FILES/logs/bello.log" | tr -d '\r'; }

case "${1:-status}" in
  demo)
    before=$(log | wc -l)
    send demo
    # A picture takes a few seconds more: wait for this page, up to the images' budget.
    for _ in $(seq 1 30); do
      sleep 1
      log | tail -n +"$((before + 1))" | grep -qE "PAGE_PUBLISH|PAGE_DEMO" && break
    done
    log | tail -n +"$((before + 1))" | grep -E "PAGE_SERVER|PAGE_PUBLISH|PAGE_SHOWN|PAGE_DEMO|IMAGE_" | tail -5
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
    log | grep -E "PAGE_|LLM_OK|IMAGE_|assistant: answer|announcement" | tail -10
    ;;
  images)
    case "${2:-status}" in
      on|off) send "images:$2" ;;
      status) send images ;;
      *) echo "usage: $0 images [on|off]"; exit 1 ;;
    esac
    sleep 1.5
    log | grep -E "PAGE_IMAGES|IMAGE_(OK|FAIL|NONE)" | tail -4
    ;;
  *) sed -n '2,10p' "$0"; exit 1 ;;
esac
