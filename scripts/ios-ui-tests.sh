#!/bin/bash
set -euo pipefail

if [[ "${1:-}" == "--help" ]]; then
    echo "Uso: $0 [UUID iPhone] [opzioni xcodebuild, es. -only-testing:MangAppUITests/SettingsUITests/testReaderTogglesAndPersistence]"
    exit 0
fi
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
export PATH="$DEVELOPER_DIR/usr/bin:$PATH"
device_id="${1:-}"
if [[ -n "$device_id" ]]; then shift; fi
device_id="$(xcrun simctl list devices available --json | python3 -c '
import json, sys
requested = sys.argv[1]
devices = [d for runtime, items in json.load(sys.stdin)["devices"].items() if "iOS" in runtime for d in items if d.get("isAvailable") and d["name"].startswith("iPhone")]
if requested:
    devices = [d for d in devices if d["udid"] == requested]
devices.sort(key=lambda d: d["state"] != "Booted")
if not devices:
    sys.exit("Nessun simulatore iPhone disponibile con questo UUID.")
print(devices[0]["udid"])
' "$device_id")"

test_root="$repo_dir/ios-app/build/ui-tests"
products_dir="$test_root/products"
xcodebuild -project "$repo_dir/ios-app/MangApp.xcodeproj" -target MangAppUITests \
    -configuration Debug -sdk iphonesimulator ARCHS=arm64 ONLY_ACTIVE_ARCH=YES \
    "CONFIGURATION_BUILD_DIR=$products_dir" CODE_SIGNING_ALLOWED=NO build

# Il build del target diretto non copia queste dipendenze del runner Xcode 26
# quando il runtime è precedente. Sono librerie ufficiali dell'Xcode selezionato.
platform_dir="$DEVELOPER_DIR/Platforms/iPhoneSimulator.platform/Developer"
runner_frameworks="$products_dir/MangAppUITests-Runner.app/Frameworks"
if [[ -f "$platform_dir/usr/lib/lib_TestingInterop.dylib" ]]; then
    cp "$platform_dir/usr/lib/lib_TestingInterop.dylib" "$runner_frameworks/"
fi
if [[ -d "$platform_dir/Library/Frameworks/_Testing_Foundation.framework" ]]; then
    ditto "$platform_dir/Library/Frameworks/_Testing_Foundation.framework" "$runner_frameworks/_Testing_Foundation.framework"
fi
python3 - "$products_dir" <<'PY'
import plistlib, sys
from pathlib import Path
root = Path(sys.argv[1])
target = {
    'BlueprintName': 'MangAppUITests', 'ProductModuleName': 'MangAppUITests',
    'IsUITestBundle': True, 'ParallelizationEnabled': False,
    'TestBundlePath': '__TESTROOT__/MangAppUITests-Runner.app/PlugIns/MangAppUITests.xctest',
    'TestHostPath': '__TESTROOT__/MangAppUITests-Runner.app',
    'UITargetAppPath': '__TESTROOT__/MangApp.app',
    'DependentProductPaths': ['__TESTROOT__/MangApp.app', '__TESTROOT__/MangAppUITests-Runner.app'],
    'TestLanguage': 'it', 'TestRegion': 'IT', 'SystemAttachmentLifetime': 'keepAlways',
}
spec = {'__xctestrun_metadata__': {'FormatVersion': 2},
        'TestConfigurations': [{'Name': 'iPhone settings', 'IsEnabled': True, 'TestTargets': [target]}]}
with (root / 'MangApp.xctestrun').open('wb') as file:
    plistlib.dump(spec, file)
PY
if ! xcrun simctl list devices booted --json | python3 -c '
import json, sys
sys.exit(0 if any(d["udid"] == sys.argv[1] for items in json.load(sys.stdin)["devices"].values() for d in items) else 1)
' "$device_id"; then
    xcrun simctl boot "$device_id"
fi
xcrun simctl bootstatus "$device_id" -b
result_path="$test_root/results-$(date +%Y%m%d-%H%M%S)-$$.xcresult"
xcodebuild test-without-building -xctestrun "$products_dir/MangApp.xctestrun" \
    -destination "platform=iOS Simulator,id=$device_id,arch=arm64" \
    -parallel-testing-enabled NO -resultBundlePath "$result_path" "$@"
echo "Risultati e screenshot: $result_path"
