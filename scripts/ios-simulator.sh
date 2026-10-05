#!/bin/bash
set -euo pipefail

if [[ "${1:-}" == "--help" ]]; then
    echo "Uso: $0 [UUID simulatore]"
    echo "Compila, installa e avvia MangApp su un simulatore iOS Apple Silicon."
    exit 0
fi
if [[ "$(uname -m)" != "arm64" ]]; then
    echo "Il progetto configura il simulatore arm64: serve un Mac Apple Silicon." >&2
    exit 1
fi

repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
if [[ ! -d "$DEVELOPER_DIR" ]]; then
    echo "Xcode non trovato: imposta DEVELOPER_DIR alla cartella Contents/Developer." >&2
    exit 1
fi

device_id="${1:-}"
if [[ -z "$device_id" ]]; then
    device_id="$(xcrun simctl list devices available --json | python3 -c '
import json, sys
devices = [d for runtime, items in json.load(sys.stdin)["devices"].items() if "iOS" in runtime for d in items if d.get("isAvailable")]
devices.sort(key=lambda d: (d["state"] != "Booted", not d["name"].startswith("iPhone")))
if not devices:
    sys.exit("Nessun simulatore iOS disponibile. Installa un runtime da Xcode > Settings > Components.")
print(devices[0]["udid"])
')"
fi

products_dir="$repo_dir/ios-app/build/simulator"
# Il target diretto permette di usare anche un runtime precedente all'SDK di Xcode.
xcodebuild -project "$repo_dir/ios-app/MangApp.xcodeproj" -target MangApp \
    -configuration Debug -sdk iphonesimulator ARCHS=arm64 ONLY_ACTIVE_ARCH=YES \
    "CONFIGURATION_BUILD_DIR=$products_dir" CODE_SIGNING_ALLOWED=NO build

if ! xcrun simctl list devices booted --json | python3 -c '
import json, sys
device_id = sys.argv[1]
sys.exit(0 if any(d["udid"] == device_id for items in json.load(sys.stdin)["devices"].values() for d in items) else 1)
' "$device_id"; then
    xcrun simctl boot "$device_id"
fi
xcrun simctl bootstatus "$device_id" -b
xcrun simctl install "$device_id" "$products_dir/MangApp.app"
xcrun simctl launch --terminate-running-process "$device_id" com.lorenzo.mangapp
echo "MangApp avviata su $device_id. Apri Simulator per visualizzarla."
