"""Trasforma il JSON di Macrobenchmark (modulo :benchmark) in un report Markdown in
perf-reports/, confrontato con il giro precedente. Solo libreria standard."""
import argparse
import datetime
import json
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
SCENARIOS = ["coldStartup", "homeScroll", "searchTyping", "tabSwitch", "readerScroll", "libraryScroll"]
COMPOSABLES = ["MangaDownloaderAppContent", "AppTopBar", "AppBottomBar", "HomeScreen", "SearchScreen",
               "LibraryScreen", "TutorialOverlay", "ReaderScreen", "VerticalReader"]
# Schermata visibile durante la misura: le altre *Screen non dovrebbero ricomporsi.
VISIBLE = {"homeScroll": "HomeScreen", "searchTyping": "SearchScreen",
           "readerScroll": "ReaderScreen", "libraryScroll": "LibraryScreen"}
SCREENS = ["HomeScreen", "SearchScreen", "LibraryScreen", "ReaderScreen"]
# Swipe per scenario di solo scroll: la radice non dovrebbe ricomporsi più di una volta ciascuno.
SWIPES = {"homeScroll": 8, "readerScroll": 15, "libraryScroll": 5}
NETWORK = {"homeScroll", "searchTyping"}
JANK_LIMIT = 10.0
REGRESSION = 0.20
MIN_DELTA = {"count": 2.0, "ms": 1.0, "pct": 2.0}


def parse(data):
    result = {}
    for bench in data.get("benchmarks", []):
        metrics = bench.get("metrics", {})
        sampled = bench.get("sampledMetrics", {})
        frames = None
        if "frameDurationCpuMs" in sampled:
            dur = sampled["frameDurationCpuMs"]
            overruns = [x for run in sampled.get("frameOverrunMs", {}).get("runs", []) for x in run]
            jank = 100.0 * sum(1 for x in overruns if x > 0) / len(overruns) if overruns else 0.0
            frames = {"p50": dur["P50"], "p90": dur["P90"], "p99": dur["P99"], "jank": jank}
        recomp = {k[len("recomp_"):-len("Count")]: v["median"] for k, v in metrics.items()
                  if k.startswith("recomp_") and k.endswith("Count")}
        root_ms = metrics.get("rootComposeSumMs", {}).get("median")
        startup = {k: v["median"] for k, v in metrics.items() if k.startswith("timeTo")}
        result[bench["name"]] = {"frames": frames, "recomp": recomp, "root_ms": root_ms, "startup": startup}
    return result


def _regressed(cur, prev, kind):
    if prev is None or cur is None or cur - prev < MIN_DELTA[kind]:
        return False
    # Per i conteggi da 0 a N è il peggioramento tipico: un composable che prima veniva saltato.
    if prev == 0:
        return kind == "count"
    return cur > prev * (1 + REGRESSION)


def is_emulator(build):
    """Gli AVD di Google si chiamano sdk_gphone…: lì i tempi dei frame sono solo tendenza."""
    return str(build.get("model", "")).startswith("sdk_")


def signals(cur, prev, emulator=False):
    out = []
    for name, s in cur.items():
        visible = VISIBLE.get(name)
        if visible:
            for screen in SCREENS:
                count = s["recomp"].get(screen, 0)
                if screen != visible and count > 0:
                    out.append(f"`{name}`: **{screen}** si ricompone {count:g} volte senza essere visibile")
        swipes = SWIPES.get(name)
        root = s["recomp"].get("MangaDownloaderAppContent", 0)
        if swipes and root > swipes:
            out.append(f"`{name}`: la radice si ricompone {root:g} volte in {swipes} swipe (più di una per swipe)")
        if not emulator and s["frames"] and s["frames"]["jank"] > JANK_LIMIT:
            out.append(f"`{name}`: {s['frames']['jank']:.1f}% di frame in jank (soglia {JANK_LIMIT:g}%)")
        p = (prev or {}).get(name)
        if not p:
            continue
        for comp, count in s["recomp"].items():
            if _regressed(count, p["recomp"].get(comp), "count"):
                out.append(f"`{name}`: ricomposizioni di {comp} peggiorate da {p['recomp'][comp]:g} a {count:g}")
        if emulator:
            # Su emulatore i ms oscillano di volte intere tra un giro e l'altro: solo rumore.
            continue
        if _regressed(s["root_ms"], p["root_ms"], "ms"):
            out.append(f"`{name}`: composizione radice peggiorata da {p['root_ms']:.1f} a {s['root_ms']:.1f} ms")
        if s["frames"] and p["frames"]:
            if _regressed(s["frames"]["p90"], p["frames"]["p90"], "ms"):
                out.append(f"`{name}`: frame p90 peggiorato da {p['frames']['p90']:.1f} a {s['frames']['p90']:.1f} ms")
            if _regressed(s["frames"]["jank"], p["frames"]["jank"], "pct"):
                out.append(f"`{name}`: jank peggiorato da {p['frames']['jank']:.1f}% a {s['frames']['jank']:.1f}%")
    return out


def _num(value, digits):
    return f"{value:.{digits}f}" if digits else f"{value:g}"


def fmt(cur, prev, digits=0):
    if cur is None:
        return "–"
    text = _num(cur, digits)
    if prev is None:
        return text
    diff = cur - prev
    if abs(diff) < (0.05 if digits else 0.5):
        return text
    sign = "+" if diff > 0 else "−"
    return f"{text} ({sign}{_num(abs(diff), digits)})"


def render(cur, prev, meta):
    prev = prev or {}
    lines = [f"# Report prestazioni — {meta['date']}", ""]
    dirty = " (con modifiche non committate)" if meta["dirty"] else ""
    lines += [f"- Commit `{meta['commit']}` su `{meta['branch']}`{dirty}",
              f"- Dispositivo: {meta['device']} (API {meta['sdk']})",
              "- I millisecondi vengono da un emulatore: leggili come tendenza, non come valori assoluti "
              "(tra un giro e l'altro oscillano anche di volte intere, per questo non generano segnali). "
              "Il dato affidabile sono le ricomposizioni." if meta.get("emulator") else
              "- Tempi dei frame misurati sotto tracing: confrontali tra giri, non con altri strumenti.",
              f"- Scenari che usano la rete (numeri più variabili): {', '.join(f'`{n}`' for n in sorted(NETWORK))}."]
    if meta.get("gradle_exit"):
        lines.append(f"- ⚠️ Gradle è uscito con codice {meta['gradle_exit']}: alcuni scenari possono essere falliti.")
    if prev:
        lines.append("- Tra parentesi la differenza rispetto al giro precedente.")
    lines.append("")

    failed = [n for n in SCENARIOS if n not in cur]
    if failed:
        lines += ["## Scenari falliti", ""]
        reasons = meta.get("failures", {})
        lines += [f"- ❌ `{n}`: {reasons.get(n, 'assente dal JSON (errore durante il benchmark: vedi output di Gradle)')}"
                  for n in failed]
        lines.append("")

    with_frames = [n for n in SCENARIOS if n in cur and cur[n]["frames"]]
    # Macrobenchmark scrive i conteggi anche quando non trova sezioni: "assente" = tutti a zero.
    if with_frames and not any(v > 0 for n in with_frames for v in cur[n]["recomp"].values()):
        lines += ["> ⚠️ **tracing Compose assente**: nessuna sezione di ricomposizione nel trace. "
                  "Controllare `ComposeTraceInstaller` (app/src/benchmark) e il suo elenco di composable.", ""]

    start = cur.get("coldStartup")
    if start and start["startup"]:
        p = prev.get("coldStartup", {}).get("startup", {})
        lines += ["## Avvio a freddo", ""]
        lines += [f"- {k}: {fmt(v, p.get(k), 1)} ms" for k, v in sorted(start["startup"].items())]
        lines.append("")

    lines += ["## Frame", "", "| Scenario | p50 ms | p90 ms | p99 ms | jank % |", "| --- | --- | --- | --- | --- |"]
    for n in with_frames:
        f = cur[n]["frames"]
        pf = (prev.get(n) or {}).get("frames") or {}
        lines.append(f"| `{n}` | {fmt(f['p50'], pf.get('p50'), 1)} | {fmt(f['p90'], pf.get('p90'), 1)} | "
                     f"{fmt(f['p99'], pf.get('p99'), 1)} | {fmt(f['jank'], pf.get('jank'), 1)} |")
    lines.append("")

    lines += ["## Ricomposizioni (mediana per iterazione)", "",
              "| Scenario | " + " | ".join(COMPOSABLES) + " | ms radice |",
              "| --- | " + " | ".join("---" for _ in COMPOSABLES) + " | --- |"]
    for n in with_frames:
        s = cur[n]
        p = prev.get(n) or {"recomp": {}, "root_ms": None}
        visible = VISIBLE.get(n)
        cells = []
        for comp in COMPOSABLES:
            count = s["recomp"].get(comp)
            cell = fmt(count, p["recomp"].get(comp))
            offscreen = visible and comp in SCREENS and comp != visible and (count or 0) > 0
            cells.append(f"**{cell}**" if offscreen else cell)
        lines.append(f"| `{n}` | " + " | ".join(cells) + f" | {fmt(s['root_ms'], p['root_ms'], 1)} |")
    lines.append("")

    found = signals(cur, prev or None, emulator=meta.get("emulator", False))
    lines += ["## Segnali", ""]
    lines += [f"- {s}" for s in found] if found else ["- Nessun segnale."]
    lines.append("")
    return "\n".join(lines)


def failure_reasons(results_dir):
    """Prima riga del messaggio di errore di ogni scenario fallito, dai risultati JUnit di AGP."""
    reasons = {}
    for xml in Path(results_dir).rglob("TEST-*.xml") if Path(results_dir).is_dir() else []:
        try:
            root = ET.parse(xml).getroot()
        except ET.ParseError:
            continue
        for case in root.iter("testcase"):
            failure = case.find("failure")
            if failure is not None:
                text = (failure.get("message") or failure.text or "").strip()
                reasons[case.get("name")] = text.splitlines()[0][:500] if text else "errore senza messaggio"
    return reasons


def load_previous(out_dir):
    try:
        latest = json.loads((out_dir / "latest.json").read_text(encoding="utf-8"))
        return parse(json.loads((out_dir / latest["json"]).read_text(encoding="utf-8")))
    except (OSError, ValueError, KeyError, TypeError):
        return None


def git_meta():
    def git(*args):
        return subprocess.run(["git", *args], cwd=REPO, capture_output=True, text=True).stdout.strip()
    return git("rev-parse", "--short", "HEAD"), git("rev-parse", "--abbrev-ref", "HEAD"), bool(git("status", "--porcelain"))


def timestamp():
    return datetime.datetime.now().strftime("%Y-%m-%d_%H%M")


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("json")
    parser.add_argument("--out-dir", default=str(REPO / "perf-reports"))
    parser.add_argument("--gradle-exit", type=int, default=0)
    parser.add_argument("--test-results", help="cartella dei risultati JUnit (TEST-*.xml) del giro")
    args = parser.parse_args(argv)

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    source = Path(args.json)
    data = json.loads(source.read_text(encoding="utf-8"))
    cur = parse(data)
    prev = load_previous(out_dir)

    stamp = timestamp()
    commit, branch, dirty = git_meta()
    build = data.get("context", {}).get("build", {})
    meta = {"date": stamp.replace("_", " "), "commit": commit, "branch": branch, "dirty": dirty,
            "device": build.get("model", "?"), "sdk": build.get("version", {}).get("sdk", "?"),
            "gradle_exit": args.gradle_exit, "emulator": is_emulator(build),
            "failures": failure_reasons(args.test_results) if args.test_results else {}}

    shutil.copyfile(source, out_dir / f"{stamp}.json")
    report = out_dir / f"{stamp}.md"
    report.write_text(render(cur, prev, meta), encoding="utf-8")
    (out_dir / "latest.json").write_text(json.dumps({"json": f"{stamp}.json", "md": f"{stamp}.md"}), encoding="utf-8")
    print(report)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
