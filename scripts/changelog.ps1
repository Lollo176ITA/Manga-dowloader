<#
.SYNOPSIS
  Sposta le voci completate (- [x]) da MIGLIORIE.md a CHANGELOG.md, in formato
  "Keep a Changelog" (per giorno e per tipo).

.DESCRIPTION
  Per ogni voce di primo livello marcata "- [x]" in MIGLIORIE.md lo script cerca:
    - una DATA di completamento nel testo della voce (preferisce quella vicina a
      "fatto/verificato/completato"; in mancanza la prima data ISO YYYY-MM-DD);
    - una riga sintetica per il changelog, nella forma:
          - **Changelog:** <Categoria> <middot> <sintesi in una riga>
      dove <Categoria> e' una tra: Aggiunto, Migliorato, Corretto, Rimosso,
      Sicurezza, Interno (sinonimi accettati), e <middot> e' il carattere "·".

  Solo le voci [x] che hanno SIA una data SIA la riga "Changelog:" vengono spostate
  in CHANGELOG.md (raggruppate per giorno decrescente, poi per categoria). Le altre
  restano in MIGLIORIE.md e vengono segnalate (cosi' la convenzione e' rispettata e
  non si perde nulla). I sotto-punti dettagliati (Perche'/Dove/Fatto) NON finiscono
  nel changelog: in changelog va solo la sintesi.

  L'intestazione di CHANGELOG.md (titolo, eventuale direttiva markdownlint, nota)
  viene PRESERVATA dal file esistente. Idempotente: rilanciarlo senza voci nuove
  non cambia i file.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\changelog.ps1
#>
[CmdletBinding()]
param(
    [string]$Migliorie = "MIGLIORIE.md",
    [string]$Changelog = "CHANGELOG.md"
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
function Resolve-RepoPath([string]$p) {
    if ([System.IO.Path]::IsPathRooted($p)) { return $p }
    return Join-Path $repoRoot $p
}
$migPath = Resolve-RepoPath $Migliorie
$chgPath = Resolve-RepoPath $Changelog
if (-not (Test-Path $migPath)) { throw "MIGLIORIE non trovato: $migPath" }

$utf8 = New-Object System.Text.UTF8Encoding($false)
$middot = [char]0x00B7  # "·": separatore Categoria/sintesi (niente literal non-ASCII nello script)

# Ordine canonico delle categorie in uscita.
$catOrder = @('Aggiunto', 'Migliorato', 'Corretto', 'Rimosso', 'Sicurezza', 'Interno')

function Normalize-Category([string]$c) {
    $cl = $c.Trim().ToLowerInvariant()
    switch -Regex ($cl) {
        'aggiunt|^add|nuov'              { return 'Aggiunto' }
        'miglior|modific|chang|cambiat'  { return 'Migliorato' }
        'corret|^fix|bug'                { return 'Corretto' }
        'rimoss|remov'                   { return 'Rimosso' }
        'sicurez|secur'                  { return 'Sicurezza' }
        'intern|refactor|test|tooling|doc' { return 'Interno' }
        default                          { return 'Migliorato' }
    }
}

function Find-CompletionDate([string]$text) {
    $m = [regex]::Match($text, '(?:fatto|verificat\w*|completat\w*|done)[^\r\n]*?(\d{4}-\d{2}-\d{2})', 'IgnoreCase')
    if ($m.Success) { return $m.Groups[1].Value }
    $m2 = [regex]::Match($text, '\d{4}-\d{2}-\d{2}')
    if ($m2.Success) { return $m2.Value }
    return $null
}

# date -> ([ordered] categoria -> List[string] di righe "- ...")
$byDate = [ordered]@{}
function Add-Entry([string]$date, [string]$cat, [string]$text) {
    if (-not $byDate.Contains($date)) { $byDate[$date] = [ordered]@{} }
    $catMap = $byDate[$date]
    if (-not $catMap.Contains($cat)) { $catMap[$cat] = New-Object System.Collections.Generic.List[string] }
    if (-not $catMap[$cat].Contains($text)) { $catMap[$cat].Add($text) }
}

# --- Lettura MIGLIORIE (preserva CRLF/LF) ---
$rawMig = [System.IO.File]::ReadAllText($migPath, $utf8)
$nl = if ($rawMig.Contains("`r`n")) { "`r`n" } else { "`n" }
$migLines = $rawMig -split "`r?`n"

$kept = New-Object System.Collections.Generic.List[string]
$movedCount = 0
$skipped = New-Object System.Collections.Generic.List[string]

$i = 0; $n = $migLines.Count
while ($i -lt $n) {
    $line = $migLines[$i]
    if ($line -match '^- \[(x| )\] ') {
        $isDone = $line -match '^- \[x\] '
        $block = New-Object System.Collections.Generic.List[string]
        $block.Add($line)
        $j = $i + 1
        while ($j -lt $n -and $migLines[$j] -match '^\s+\S') { $block.Add($migLines[$j]); $j++ }
        $title = $line
        $tm = [regex]::Match($line, '\*\*(.+?)\*\*')
        if ($tm.Success) { $title = $tm.Groups[1].Value }

        $moved = $false
        if ($isDone) {
            $blockText = ($block -join "`n")
            $date = Find-CompletionDate $blockText
            $clm = [regex]::Match($blockText, '(?m)^\s*-\s*\*\*Changelog:\*\*\s*(.+?)\s*$')
            if (($null -ne $date) -and $clm.Success) {
                $payload = $clm.Groups[1].Value
                $sep = $payload.IndexOf($middot)
                if ($sep -ge 0) {
                    $cat = Normalize-Category $payload.Substring(0, $sep)
                    $summary = $payload.Substring($sep + 1).Trim()
                } else {
                    $cat = 'Migliorato'
                    $summary = $payload.Trim()
                }
                Add-Entry $date $cat ("- " + $summary)
                $movedCount++
                $moved = $true
            } elseif ($null -eq $date) {
                $skipped.Add("$title (manca la data)")
            } else {
                $skipped.Add("$title (manca la riga '- **Changelog:** Categoria $middot sintesi')")
            }
        }
        if (-not $moved) { foreach ($b in $block) { $kept.Add($b) } }
        $i = $j
    } else {
        $kept.Add($line); $i++
    }
}

# --- Lettura CHANGELOG esistente: header preservato + parsing voci ---
$headerLines = @('# Changelog', '', '<!-- markdownlint-disable MD024 -->', '', '> Voci completate, spostate da MIGLIORIE.md via scripts/changelog.ps1.')
if (Test-Path $chgPath) {
    $rawChg = [System.IO.File]::ReadAllText($chgPath, $utf8)
    $chgLines = $rawChg -split "`r?`n"
    $firstDate = -1
    for ($x = 0; $x -lt $chgLines.Count; $x++) {
        if ($chgLines[$x] -match '^##\s+\d{4}-\d{2}-\d{2}') { $firstDate = $x; break }
    }
    if ($firstDate -ge 0) {
        $headerLines = $chgLines[0..($firstDate - 1)]
        $curDate = $null; $curCat = $null
        $k = $firstDate
        while ($k -lt $chgLines.Count) {
            $cl = $chgLines[$k]
            $hd = [regex]::Match($cl, '^##\s+(\d{4}-\d{2}-\d{2})')
            $hc = [regex]::Match($cl, '^###\s+(.+?)\s*$')
            if ($hd.Success) { $curDate = $hd.Groups[1].Value; $curCat = $null; $k++; continue }
            if ($hc.Success) { $curCat = Normalize-Category $hc.Groups[1].Value; $k++; continue }
            if ($cl -match '^- ' -and $null -ne $curDate -and $null -ne $curCat) {
                $blk = New-Object System.Collections.Generic.List[string]
                $blk.Add($cl); $k++
                while ($k -lt $chgLines.Count -and $chgLines[$k] -match '^\s+\S') { $blk.Add($chgLines[$k]); $k++ }
                Add-Entry $curDate $curCat ($blk -join $nl)
                continue
            }
            $k++
        }
    } elseif ($chgLines.Count -gt 0) {
        $headerLines = $chgLines
    }
}

if ($movedCount -eq 0) {
    Write-Output "Nessuna voce completata pronta da spostare (servono data + riga '- **Changelog:** ...'). File invariati."
    if ($skipped.Count -gt 0) { Write-Output "Da completare in MIGLIORIE.md:"; foreach ($s in $skipped) { Write-Output "  - $s" } }
    return
}

# --- Emissione CHANGELOG ---
$hdr = New-Object System.Collections.Generic.List[string]
foreach ($h in $headerLines) { $hdr.Add($h) }
while ($hdr.Count -gt 0 -and $hdr[$hdr.Count - 1] -match '^\s*$') { $hdr.RemoveAt($hdr.Count - 1) }

$out = New-Object System.Collections.Generic.List[string]
foreach ($h in $hdr) { $out.Add($h) }
$out.Add("")
foreach ($date in ($byDate.Keys | Sort-Object -Descending)) {
    $out.Add("## $date")
    $out.Add("")
    $catMap = $byDate[$date]
    $emitted = New-Object System.Collections.Generic.List[string]
    foreach ($cat in $catOrder) {
        if ($catMap.Contains($cat)) {
            $out.Add("### $cat"); $out.Add("")
            foreach ($e in $catMap[$cat]) { $out.Add($e) }
            $out.Add("")
            $emitted.Add($cat)
        }
    }
    foreach ($cat in $catMap.Keys) {
        if (-not $emitted.Contains($cat)) {
            $out.Add("### $cat"); $out.Add("")
            foreach ($e in $catMap[$cat]) { $out.Add($e) }
            $out.Add("")
        }
    }
}
$chgText = ($out -join $nl).TrimEnd() + $nl
[System.IO.File]::WriteAllText($chgPath, $chgText, $utf8)

# --- Riscrittura MIGLIORIE: rimuove le sezioni "## ..." rimaste senza voci ---
$keptLines = (($kept -join $nl) -split "`r?`n")
$preamble = New-Object System.Collections.Generic.List[string]
$sections = New-Object System.Collections.Generic.List[object]
$cur = $null
foreach ($l in $keptLines) {
    if ($l -match '^##\s') {
        if ($null -ne $cur) { $sections.Add($cur) }
        $cur = [PSCustomObject]@{ Header = $l; Lines = (New-Object System.Collections.Generic.List[string]); HasItem = $false }
    } elseif ($null -eq $cur) {
        $preamble.Add($l)
    } else {
        $cur.Lines.Add($l)
        if ($l -match '^- ') { $cur.HasItem = $true }
    }
}
if ($null -ne $cur) { $sections.Add($cur) }

$result = New-Object System.Collections.Generic.List[string]
foreach ($p in $preamble) { $result.Add($p) }
foreach ($s in $sections) {
    if (-not $s.HasItem) { continue }
    $result.Add($s.Header)
    foreach ($l in $s.Lines) { $result.Add($l) }
}
$migOut = ($result -join $nl)
$migOut = [regex]::Replace($migOut, "($([regex]::Escape($nl))){3,}", ($nl + $nl))
$migOut = $migOut.TrimEnd() + $nl
[System.IO.File]::WriteAllText($migPath, $migOut, $utf8)

# --- Report ---
Write-Output "Spostate $movedCount voci nel changelog."
if ($skipped.Count -gt 0) {
    Write-Output ""
    Write-Output "Lasciate in MIGLIORIE.md (convenzione incompleta):"
    foreach ($s in $skipped) { Write-Output "  - $s" }
}
