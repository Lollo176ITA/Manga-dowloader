"""Scrive il feed aggiornamenti letto dall'app (AppUpdateManager.parseUpdateFeedInfo).

Le note sono i punti elenco aggiunti a CHANGELOG.md dalla release precedente dello stesso
canale: testo già scritto per l'utente. Nessun punto nuovo = nessuna nota.
"""

import argparse
import fnmatch
import json
import subprocess


def git(*args: str) -> str:
    return subprocess.run(["git", *args], check=True, capture_output=True, text=True).stdout


def previous_tag(pattern: str, current: str) -> str | None:
    tags = git("tag", "--list", pattern, "--sort=-v:refname").split()
    return next((tag for tag in tags if tag != current and fnmatch.fnmatch(tag, pattern)), None)


def new_changelog_bullets(since: str | None) -> list[str]:
    if since is None:
        return []
    root = git("rev-parse", "--show-toplevel").strip()
    diff = git("-C", root, "diff", "--unified=0", since, "HEAD", "--", "CHANGELOG.md")
    bullets = []
    for line in diff.splitlines():
        if line.startswith("+- "):
            bullets.append(line[3:].strip())
    return [bullet for bullet in bullets if bullet]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--apk-url", required=True)
    parser.add_argument("--tag-pattern", required=True, help="es. 'android-v*'")
    parser.add_argument("--current-tag", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    since = previous_tag(args.tag_pattern, args.current_tag)
    feed = {
        "versionName": args.version_name,
        "apkUrl": args.apk_url,
        "notes": new_changelog_bullets(since),
    }
    with open(args.output, "w", encoding="utf-8") as out:
        json.dump(feed, out, ensure_ascii=False, indent=2)
    print(f"Note dal CHANGELOG dopo {since or '(nessun tag precedente)'}: {len(feed['notes'])}")


if __name__ == "__main__":
    main()
