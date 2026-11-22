"""Riassume l'esito di LiveSourcesCheckTest per l'issue "Fonti da controllare".

Legge i risultati JUnit e stampa, in Markdown, le fonti fallite con il loro errore.
Le fonti in manutenzione in `sources.json` risultano saltate e vengono solo elencate.
Esce con 1 se almeno una fonte è fallita (o se i risultati mancano), 0 altrimenti.
"""

import glob
import re
import sys
import xml.etree.ElementTree as ET


def source_name(case: ET.Element) -> str:
    match = re.search(r"\[(.+)\]", case.get("name", ""))
    return match.group(1) if match else case.get("name", "?")


def failed_sources(results_glob: str) -> tuple[int, list[tuple[str, str]], list[str]]:
    total = 0
    failures = []
    skipped = []
    for path in glob.glob(results_glob):
        for case in ET.parse(path).getroot().iter("testcase"):
            total += 1
            if case.find("skipped") is not None:
                # In manutenzione da sources.json: l'app non la interroga.
                skipped.append(source_name(case))
                continue
            problem = case.find("failure")
            if problem is None:
                problem = case.find("error")
            if problem is None:
                continue
            message = (problem.get("message") or "errore sconosciuto").strip()
            failures.append((source_name(case), message[:400]))
    return total, failures, skipped


def main() -> int:
    total, failures, skipped = failed_sources(sys.argv[1])
    if total == 0:
        print("Il controllo non ha prodotto risultati: guarda il log del workflow.")
        return 1
    checked = total - len(skipped)
    paused = f"In manutenzione (non controllate): {', '.join(skipped)}." if skipped else ""
    if not failures:
        print(f"Tutte le {checked} fonti controllate rispondono.")
    else:
        print(f"{len(failures)} fonti su {checked} non superano il controllo sui siti veri:\n")
        for source, message in failures:
            print(f"- **{source}**: {message}")
    if paused:
        print(f"\n{paused}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
