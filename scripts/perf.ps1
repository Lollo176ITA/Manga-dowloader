# Benchmark UI in locale: esegue il modulo :benchmark sull'emulatore acceso e scrive il
# report in perf-reports/ (confrontato col giro precedente). Uso: scripts\perf.ps1
$repo = Split-Path -Parent $PSScriptRoot
$android = Join-Path $repo 'android-app'
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'

# Solo lo stato "device" è pronto: "offline" e "unauthorized" farebbero fallire Gradle a metà.
$ready = & $adb devices | Select-String -Pattern '^\S+\s+device$'
if (-not $ready) {
    Write-Host "Nessun emulatore pronto (stato 'device'). Avvia l'AVD Pixel_8 e riprova."
    exit 1
}

# Via i risultati del giro precedente: il JSON trovato dopo deve essere di questo giro.
$results = Join-Path $android 'benchmark\build\outputs\connected_android_test_additional_output'
$junit = Join-Path $android 'benchmark\build\outputs\androidTest-results\connected'
foreach ($dir in @($results, $junit)) { if (Test-Path $dir) { Remove-Item -Recurse -Force $dir } }

& (Join-Path $android 'gradlew.bat') -p $android ':benchmark:connectedBenchmarkAndroidTest' '--continue'
$gradleExit = $LASTEXITCODE

$json = Get-ChildItem -Path $results -Recurse -Filter '*benchmarkData.json' -ErrorAction SilentlyContinue |
    Select-Object -First 1
if (-not $json) {
    Write-Host "Nessun risultato di benchmark trovato (Gradle exit $gradleExit)."
    exit 1
}

# I risultati JUnit danno al report il motivo di ogni scenario fallito.
python (Join-Path $PSScriptRoot 'perf_report.py') $json.FullName --gradle-exit $gradleExit --test-results $junit
exit $LASTEXITCODE
