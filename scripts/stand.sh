#!/usr/bin/env bash
# The printed desk stand (docs/tablet-stand.md).
#   scripts/stand.sh build   Blender -> hardware/stand/*.stl and preview PNGs (checks fit and mesh)
#   scripts/stand.sh slice [stl...]  Cura's engine on the Ender-3 profile -> print time, filament
#   scripts/stand.sh all     both
set -euo pipefail
cd "$(dirname "$0")/.."

BLENDER="${BLENDER:-/Applications/Blender.app/Contents/MacOS/Blender}"
CURA="${CURA:-/Applications/UltiMaker Cura.app/Contents}"
OUT=hardware/stand
GCODE_DIR="${TMPDIR:-/tmp}/bello-stand"

build() {
  rm -f "$OUT"/bello-stand*.stl
  "$BLENDER" -b --factory-startup -P "$OUT/make_stand.py" -- "$OUT" 2>&1 \
    | grep -E '^(STAND|FIT)|Error|Traceback|AssertionError|File "' || true
  [ -s "$OUT/bello-stand.stl" ] || { echo "no STL produced" >&2; exit 1; }
}

# Cura 5.9 "Standard Quality" for the Creality Ender-3 with generic PLA, as the Cura app on this
# Mac slices it (0.2 mm layers, 2 walls, 20 % infill, 50 mm/s), with the user's brim and
# temperatures and no supports. cura_settings.py evaluates the formulas the bare engine ignores.
PROFILE=(support_enable=False adhesion_type=brim material_print_temperature=210
         material_bed_temperature=65 machine_center_is_zero=True)

slice_one() {
  local stl="$1" res="$CURA/Resources/share/cura/resources" args=() kv
  mkdir -p "$GCODE_DIR"
  local gcode="$GCODE_DIR/$(basename "${stl%.stl}").gcode"
  while IFS= read -r -d '' kv; do args+=(-s "$kv"); done \
    < <(python3 "$OUT/cura_settings.py" "$res" "${PROFILE[@]}" 2>/dev/null)
  CURA_ENGINE_SEARCH_PATH="$res/definitions:$res/extruders" \
  "$CURA/Frameworks/CuraEngine" slice -j "$res/definitions/creality_ender3.def.json" \
    "${args[@]}" -e0 "${args[@]}" -l "$stl" -o "$gcode" >"${gcode%.gcode}.log" 2>&1 \
    || { echo "CuraEngine failed on $stl (see ${gcode%.gcode}.log)" >&2; return 1; }
  # the bare engine leaves the G-code header blank; its log has the totals
  local secs mm3
  secs=$(grep -m1 'Print time (s):' "${gcode%.gcode}.log" | sed 's/.*: //')
  mm3=$(grep -m1 'Filament (mm^3):' "${gcode%.gcode}.log" | sed 's/.*: //')
  LC_ALL=C printf '%-28s %dh%02d  %.0f cm3 of filament (~%.0f g PLA)\n' "$(basename "$stl")" \
    $((secs / 3600)) $((secs % 3600 / 60)) "$(echo "$mm3 / 1000" | bc -l)" "$(echo "$mm3 * 0.00124" | bc -l)"
}

slice() {
  local stls=("$@")
  [ ${#stls[@]} -gt 0 ] || stls=("$OUT"/bello-stand.stl "$OUT"/bello-stand-fit-test.stl)
  for stl in "${stls[@]}"; do slice_one "$stl"; done
}

case "${1:-all}" in
  build) build ;;
  slice) shift; slice "$@" ;;
  all) build; slice ;;
  *) echo "usage: $0 build|slice|all" >&2; exit 2 ;;
esac
