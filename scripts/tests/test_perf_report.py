import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import perf_report  # noqa: E402

FIXTURE = Path(__file__).parent / "fixtures" / "benchmarkData.json"


def bench(name, recomp=None, root_ms=None, frames=True, overrun=None, startup=None):
    metrics = {}
    for comp, value in (recomp or {}).items():
        metrics[f"recomp_{comp}Count"] = {"median": value}
    if root_ms is not None:
        metrics["rootComposeSumMs"] = {"median": root_ms}
    for key, value in (startup or {}).items():
        metrics[key] = {"median": value}
    sampled = {}
    if frames:
        sampled["frameDurationCpuMs"] = {"P50": 10.0, "P90": 20.0, "P99": 30.0, "runs": [[10.0]]}
        sampled["frameOverrunMs"] = {"runs": [overrun if overrun is not None else [-5.0, -3.0, 2.0, -1.0]]}
    return {"name": name, "metrics": metrics, "sampledMetrics": sampled}


def data(*benchmarks):
    return {"context": {"build": {"model": "Pixel 8", "version": {"sdk": 36}}}, "benchmarks": list(benchmarks)}


META = {"date": "2026-09-25 12:00", "commit": "abc1234", "branch": "dev", "dirty": False,
        "device": "Pixel 8", "sdk": 36, "gradle_exit": 0}


class ParseTest(unittest.TestCase):
    def test_extracts_frames_jank_recomp_root(self):
        parsed = perf_report.parse(data(bench("homeScroll", {"HomeScreen": 3.0}, root_ms=4.5)))
        s = parsed["homeScroll"]
        self.assertEqual(s["frames"]["p90"], 20.0)
        self.assertAlmostEqual(s["frames"]["jank"], 25.0)
        self.assertEqual(s["recomp"], {"HomeScreen": 3.0})
        self.assertEqual(s["root_ms"], 4.5)

    def test_root_ms_ignores_root_count(self):
        # Mode.Sum scrive anche "<label>Count", e nel JSON reale viene prima di "<label>SumMs".
        b = bench("homeScroll", {"HomeScreen": 1.0})
        b["metrics"] = {"rootComposeCount": {"median": 10.0}, "rootComposeSumMs": {"median": 3.5}, **b["metrics"]}
        self.assertEqual(perf_report.parse(data(b))["homeScroll"]["root_ms"], 3.5)

    def test_startup_metrics(self):
        parsed = perf_report.parse(data(bench("coldStartup", frames=False, startup={"timeToInitialDisplayMs": 812.0})))
        self.assertEqual(parsed["coldStartup"]["startup"], {"timeToInitialDisplayMs": 812.0})
        self.assertIsNone(parsed["coldStartup"]["frames"])

    def test_real_fixture_parses(self):
        parsed = perf_report.parse(json.loads(FIXTURE.read_text(encoding="utf-8")))
        self.assertIn("homeScroll", parsed)
        self.assertTrue(parsed["homeScroll"]["recomp"])


class SignalsTest(unittest.TestCase):
    def test_offscreen_screen_recomposition(self):
        cur = perf_report.parse(data(bench("searchTyping", {"SearchScreen": 7.0, "HomeScreen": 7.0})))
        self.assertTrue(any("HomeScreen" in s and "searchTyping" in s for s in perf_report.signals(cur, None)))

    def test_visible_screen_is_not_a_signal(self):
        cur = perf_report.parse(data(bench("searchTyping", {"SearchScreen": 7.0})))
        self.assertFalse(any("SearchScreen" in s for s in perf_report.signals(cur, None)))

    def test_root_more_than_once_per_swipe(self):
        cur = perf_report.parse(data(bench("homeScroll", {"MangaDownloaderAppContent": 16.0})))
        self.assertTrue(any("radice" in s and "homeScroll" in s for s in perf_report.signals(cur, None)))

    def test_high_jank(self):
        cur = perf_report.parse(data(bench("tabSwitch", overrun=[1.0, 1.0, -1.0, -1.0])))
        self.assertTrue(any("jank" in s and "tabSwitch" in s for s in perf_report.signals(cur, None)))

    def test_no_absolute_jank_signal_on_emulator(self):
        # Sull'emulatore il jank è quasi sempre ~100%: solo rumore, niente segnale assoluto.
        cur = perf_report.parse(data(bench("tabSwitch", overrun=[1.0, 1.0, -1.0, -1.0])))
        self.assertFalse(any("jank" in s for s in perf_report.signals(cur, None, emulator=True)))

    def test_emulator_ignores_ms_regressions_but_not_counts(self):
        prev = perf_report.parse(data(bench("tabSwitch", {"AppTopBar": 5.0}, root_ms=4.0)))
        cur = perf_report.parse(data(bench("tabSwitch", {"AppTopBar": 8.0}, root_ms=9.0)))
        cur["tabSwitch"]["frames"]["p90"] = 80.0
        found = perf_report.signals(cur, prev, emulator=True)
        self.assertTrue(any("AppTopBar" in s for s in found))
        self.assertFalse(any(" ms" in s for s in found))

    def test_is_emulator(self):
        self.assertTrue(perf_report.is_emulator({"model": "sdk_gphone64_x86_64"}))
        self.assertFalse(perf_report.is_emulator({"model": "Pixel 8"}))

    def test_regression_over_20_percent(self):
        prev = perf_report.parse(data(bench("tabSwitch", {"AppTopBar": 5.0})))
        cur = perf_report.parse(data(bench("tabSwitch", {"AppTopBar": 8.0})))
        self.assertTrue(any("peggiorat" in s and "AppTopBar" in s for s in perf_report.signals(cur, prev)))

    def test_count_rising_from_zero_is_regression(self):
        prev = perf_report.parse(data(bench("libraryScroll", {"AppTopBar": 0.0})))
        cur = perf_report.parse(data(bench("libraryScroll", {"AppTopBar": 20.0})))
        self.assertTrue(any("peggiorat" in s and "AppTopBar" in s for s in perf_report.signals(cur, prev)))

    def test_small_absolute_change_is_not_regression(self):
        prev = perf_report.parse(data(bench("tabSwitch", {"AppTopBar": 1.0})))
        cur = perf_report.parse(data(bench("tabSwitch", {"AppTopBar": 2.0})))
        self.assertFalse(any("peggiorat" in s for s in perf_report.signals(cur, prev)))


class RenderTest(unittest.TestCase):
    def test_missing_scenario_marked_failed(self):
        cur = perf_report.parse(data(bench("homeScroll", {"HomeScreen": 0.0})))
        self.assertIn("❌ `readerScroll`", perf_report.render(cur, None, META))

    def test_no_tracing_warning(self):
        # Macrobenchmark scrive sempre recomp_<X>Count, anche a 0: "assente" = tutti a zero.
        cur = perf_report.parse(data(bench("homeScroll", {c: 0.0 for c in perf_report.COMPOSABLES})))
        self.assertIn("tracing Compose assente", perf_report.render(cur, None, META))

    def test_no_previous_means_no_delta(self):
        cur = perf_report.parse(data(bench("homeScroll", {"HomeScreen": 2.0})))
        out = perf_report.render(cur, None, META)
        self.assertNotIn("(+", out)
        self.assertNotIn("(−", out)

    def test_delta_shown_with_previous(self):
        prev = perf_report.parse(data(bench("homeScroll", {"HomeScreen": 2.0})))
        cur = perf_report.parse(data(bench("homeScroll", {"HomeScreen": 5.0})))
        self.assertIn("5 (+3)", perf_report.render(cur, prev, META))


JUNIT = """<?xml version='1.0' encoding='UTF-8' ?>
<testsuite name="x" tests="2" failures="1">
  <testcase name="homeScroll" classname="c" time="1.0" />
  <testcase name="readerScroll" classname="c" time="2.0">
    <failure>java.lang.IllegalStateException: Elemento "Bench01" non trovato entro 15000ms. A schermo: [Libreria]
	at com.x.AppSetupKt.waitForText(AppSetup.kt:24)</failure>
  </testcase>
</testsuite>"""


class OnlyFilterTest(unittest.TestCase):
    def test_not_requested_scenarios_are_not_failures(self):
        cur = perf_report.parse(data(bench("homeScroll", {"HomeScreen": 1.0})))
        out = perf_report.render(cur, None, dict(META, requested=["homeScroll"]))
        self.assertNotIn("❌", out)
        self.assertIn("Non eseguiti", out)
        self.assertIn("`readerScroll`", out)

    def test_requested_but_missing_is_still_a_failure(self):
        cur = perf_report.parse(data(bench("homeScroll", {"HomeScreen": 1.0})))
        out = perf_report.render(cur, None, dict(META, requested=["homeScroll", "libraryScroll"]))
        self.assertIn("❌ `libraryScroll`", out)

    def test_partial_run_does_not_replace_baseline(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            reports = tmp / "r"
            for i, (payload, extra) in enumerate([
                (data(bench("homeScroll", {"HomeScreen": 2.0})), []),
                (data(bench("homeScroll", {"HomeScreen": 5.0})), ["--scenarios", "homeScroll"]),
                (data(bench("homeScroll", {"HomeScreen": 9.0})), ["--scenarios", "homeScroll"]),
            ]):
                src = tmp / f"in{i}.json"
                src.write_text(json.dumps(payload), encoding="utf-8")
                with mock.patch.object(perf_report, "git_meta", return_value=("abc1234", "dev", False)),                         mock.patch.object(perf_report, "timestamp", return_value=f"2099-01-01_000{i}"):
                    perf_report.main([str(src), "--out-dir", str(reports), *extra])
            # Il terzo giro (parziale) si confronta col primo (completo), non col secondo (parziale).
            third = (reports / "2099-01-01_0002.md").read_text(encoding="utf-8")
            self.assertIn("9 (+7)", third)


class FailureReasonsTest(unittest.TestCase):
    def test_reads_first_line_of_failure(self):
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "sub").mkdir()
            (Path(tmp) / "sub" / "TEST-Pixel_8(AVD) - 16.xml").write_text(JUNIT, encoding="utf-8")
            reasons = perf_report.failure_reasons(Path(tmp))
        self.assertEqual(set(reasons), {"readerScroll"})
        self.assertIn('Elemento "Bench01" non trovato', reasons["readerScroll"])
        self.assertNotIn("AppSetupKt", reasons["readerScroll"])

    def test_missing_dir_gives_no_reasons(self):
        self.assertEqual(perf_report.failure_reasons(Path("non-esiste")), {})

    def test_reason_shown_next_to_failed_scenario(self):
        cur = perf_report.parse(data(bench("homeScroll", {"HomeScreen": 1.0})))
        meta = dict(META, failures={"readerScroll": "Elemento non trovato"})
        self.assertIn("❌ `readerScroll`: Elemento non trovato", perf_report.render(cur, None, meta))


class MainTest(unittest.TestCase):
    def run_main(self, out_dir, payload):
        src = out_dir / "input.json"
        src.write_text(json.dumps(payload), encoding="utf-8")
        with mock.patch.object(perf_report, "git_meta", return_value=("abc1234", "dev", False)):
            return perf_report.main([str(src), "--out-dir", str(out_dir / "perf reports")])

    def test_writes_report_and_compares_second_run(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            self.assertEqual(self.run_main(tmp, data(bench("homeScroll", {"HomeScreen": 2.0}))), 0)
            reports = tmp / "perf reports"
            self.assertTrue((reports / "latest.json").exists())
            self.assertEqual(len(list(reports.glob("*.md"))), 1)
            with mock.patch.object(perf_report, "timestamp", return_value="2099-01-01_0000"):
                self.run_main(tmp, data(bench("homeScroll", {"HomeScreen": 5.0})))
            second = (reports / "2099-01-01_0000.md").read_text(encoding="utf-8")
            self.assertIn("5 (+3)", second)

    def test_broken_latest_is_ignored(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            reports = tmp / "perf reports"
            reports.mkdir()
            (reports / "latest.json").write_text("{non json", encoding="utf-8")
            self.assertEqual(self.run_main(tmp, data(bench("homeScroll", {"HomeScreen": 2.0}))), 0)


if __name__ == "__main__":
    unittest.main()
