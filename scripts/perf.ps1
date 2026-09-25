# Benchmark UI in locale: esegue il modulo :benchmark sull'emulatore acceso e scrive il
# report in perf-reports/ (confrontato col giro precedente).
# Uso: scripts\perf.ps1                              giro completo (~5 min, diventa il riferimento)
#      scripts\perf.ps1 -Only homeScroll,tabSwitch    solo quegli scenari (confronto con l'ultimo completo)
param([string[]]$Only)
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

$gradleArgs = @('-p', $android, ':benchmark:connectedBenchmarkAndroidTest', '--continue')
$reportArgs = @()
if ($Only) {
    # -Only a,b arriva come array, -Only "a,b" come stringa: si normalizza in una lista.
    $names = ($Only -join ',') -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ }
    # Separatore "+": virgole e "|" non sopravvivono al passaggio per gradlew.bat (cmd).
    $gradleArgs += "-Pandroid.testInstrumentationRunnerArguments.perfOnly=$($names -join '+')"
    $reportArgs = @('--scenarios', ($names -join ','))
}
# Con un timeout: se l'emulatore si pianta (riavvio di system_server) il test muore e Gradle
# resterebbe ad aspettare per sempre.
$timeoutMin = if ($Only) { 8 } else { 15 }
$quoted = $gradleArgs | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } }
$gradle = Start-Process -FilePath (Join-Path $android 'gradlew.bat') -ArgumentList $quoted -NoNewWindow -PassThru
$null = $gradle.Handle  # senza, in PowerShell 5.1 ExitCode resta vuoto a fine processo
if (-not $gradle.WaitForExit($timeoutMin * 60 * 1000)) {
    & taskkill /T /F /PID $gradle.Id | Out-Null
    & (Join-Path $android 'gradlew.bat') -p $android --stop | Out-Null
    Write-Host "Benchmark fermato dopo $timeoutMin minuti: l'emulatore non risponde. Riavvialo a freddo e riprova."
    exit 1
}
$gradleExit = $gradle.ExitCode

$json = Get-ChildItem -Path $results -Recurse -Filter '*benchmarkData.json' -ErrorAction SilentlyContinue |
    Select-Object -First 1
if (-not $json) {
    Write-Host "Nessun risultato di benchmark trovato (Gradle exit $gradleExit)."
    exit 1
}

# I risultati JUnit danno al report il motivo di ogni scenario fallito.
python (Join-Path $PSScriptRoot 'perf_report.py') $json.FullName --gradle-exit $gradleExit --test-results $junit @reportArgs
exit $LASTEXITCODE
