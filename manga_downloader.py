#!/usr/bin/env python3
"""
Manga chapter downloader.

Supports MangaWorld and Mangapill chapter pages. Given a chapter URL, downloads
every page and merges them into a single PDF (or a single tall JPG).
"""

from __future__ import annotations

import argparse
import concurrent.futures
import io
import os
import re
import sys
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Iterable, List
from urllib.parse import urljoin, urlparse, urlunparse, parse_qsl, urlencode

import requests
from bs4 import BeautifulSoup
from PIL import Image

HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/124.0 Safari/537.36"
    ),
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
    "Accept-Language": "it,en;q=0.8",
}


@dataclass(frozen=True)
class ChapterEntry:
    number_text: str
    number_value: Decimal
    url: str
    slug: str


@dataclass(frozen=True)
class ChapterResult:
    entry: ChapterEntry
    out_path: Path
    status: str
    error: str | None = None


def normalize_chapter_url(url: str) -> str:
    """
    Normalize known reader URLs before parsing.

    MangaWorld needs ``?style=list`` so every page image is present in one HTML
    response. Mangapill already exposes all pages on the chapter URL, so it is
    left unchanged.
    """
    parsed = urlparse(url)
    hostname = (parsed.hostname or "").lower()
    if "mangaworld" not in hostname:
        return url

    query = dict(parse_qsl(parsed.query))
    query["style"] = "list"
    return urlunparse(parsed._replace(query=urlencode(query)))


def unique_in_order(urls: Iterable[str]) -> List[str]:
    seen = set()
    ordered: List[str] = []
    for url in urls:
        if url in seen:
            continue
        seen.add(url)
        ordered.append(url)
    return ordered


def create_session() -> requests.Session:
    return requests.Session()


def fetch_soup(url: str, session: requests.Session) -> BeautifulSoup:
    resp = session.get(url, headers=HEADERS, timeout=30)
    resp.raise_for_status()
    return BeautifulSoup(resp.text, "html.parser")


def fetch_page_image_urls(chapter_url: str, session: requests.Session) -> List[str]:
    url = normalize_chapter_url(chapter_url)
    soup = fetch_soup(url, session)

    selectors = (
        "chapter-page img.js-page",
        "chapter-page picture img",
        "img.page-image",
    )
    urls: List[str] = []
    for selector in selectors:
        for img in soup.select(selector):
            src = img.get("data-src") or img.get("src")
            if src:
                urls.append(urljoin(url, src))

    urls = unique_in_order(urls)

    if not urls:
        raise RuntimeError(
            "No page images found. The site layout may have changed, "
            "or the URL is not a supported chapter reader page."
        )
    return urls


def download_image(url: str, session: requests.Session, referer: str) -> Image.Image:
    headers = dict(HEADERS)
    headers["Referer"] = referer
    resp = session.get(url, headers=headers, timeout=60)
    resp.raise_for_status()
    img = Image.open(io.BytesIO(resp.content))
    img.load()
    return img


def safe_filename(name: str) -> str:
    name = re.sub(r"[^A-Za-z0-9._-]+", "_", name).strip("_")
    return name or "manga"


def parse_chapter_number(value: str) -> Decimal:
    match = re.search(r"\d+(?:\.\d+)?", value)
    if not match:
        raise ValueError(f"Invalid chapter number: {value!r}")
    try:
        return Decimal(match.group(0))
    except InvalidOperation as exc:
        raise ValueError(f"Invalid chapter number: {value!r}") from exc


def format_chapter_number(value: Decimal) -> str:
    text = format(value.normalize(), "f")
    if "." in text:
        text = text.rstrip("0").rstrip(".")
    return text


def derive_default_name(chapter_url: str) -> str:
    parts = [p for p in urlparse(chapter_url).path.split("/") if p]
    meaningful = [p for p in parts if not re.fullmatch(r"[0-9a-f]{24}", p) and p not in {"manga", "read"}]
    return safe_filename("_".join(meaningful) or "manga_chapter")


def infer_mangapill_manga_url(url: str) -> str | None:
    parsed = urlparse(url)
    hostname = (parsed.hostname or "").lower()
    if "mangapill.com" not in hostname:
        return None

    parts = [p for p in parsed.path.split("/") if p]
    if len(parts) >= 2 and parts[0] == "manga":
        return urlunparse(parsed._replace(path=f"/manga/{parts[1]}", query="", fragment=""))

    if len(parts) >= 2 and parts[0] == "chapters":
        manga_id = parts[1].split("-", 1)[0]
        if manga_id:
            return f"{parsed.scheme or 'https'}://{parsed.netloc}/manga/{manga_id}"

    return None


def fetch_mangapill_chapter_entries(target_url: str, session: requests.Session) -> List[ChapterEntry]:
    manga_url = infer_mangapill_manga_url(target_url)
    if not manga_url:
        raise RuntimeError("Batch mode currently supports Mangapill manga or chapter URLs only.")

    soup = fetch_soup(manga_url, session)
    entries: List[ChapterEntry] = []
    seen_urls = set()

    for link in soup.select('#chapters a[href^="/chapters/"]'):
        href = link.get("href")
        if not href:
            continue
        chapter_url = urljoin(manga_url, href)
        if chapter_url in seen_urls:
            continue

        title = (link.get("title") or link.get_text(" ", strip=True) or "").strip()
        match = re.search(r"chapter\s+(\d+(?:\.\d+)?)", title, re.IGNORECASE)
        if not match:
            match = re.search(r"chapter-(\d+(?:\.\d+)?)", chapter_url, re.IGNORECASE)
        if not match:
            continue

        number_text = match.group(1)
        entries.append(
            ChapterEntry(
                number_text=number_text,
                number_value=parse_chapter_number(number_text),
                url=chapter_url,
                slug=safe_filename(urlparse(chapter_url).path.rstrip("/").split("/")[-1]),
            )
        )
        seen_urls.add(chapter_url)

    if not entries:
        raise RuntimeError("No chapter links found on the Mangapill manga page.")

    return sorted(entries, key=lambda entry: entry.number_value)


def save_as_pdf(images: List[Image.Image], out_path: Path) -> None:
    rgb_images = [img.convert("RGB") for img in images]
    first, rest = rgb_images[0], rgb_images[1:]
    first.save(out_path, format="PDF", save_all=True, append_images=rest)


def save_as_long_jpg(images: List[Image.Image], out_path: Path) -> None:
    rgb_images = [img.convert("RGB") for img in images]
    max_width = max(img.width for img in rgb_images)
    scaled = [
        img if img.width == max_width
        else img.resize((max_width, round(img.height * max_width / img.width)), Image.LANCZOS)
        for img in rgb_images
    ]
    total_height = sum(img.height for img in scaled)
    canvas = Image.new("RGB", (max_width, total_height), (255, 255, 255))
    y = 0
    for img in scaled:
        canvas.paste(img, (0, y))
        y += img.height
    canvas.save(out_path, format="JPEG", quality=92, optimize=True)


def build_temp_output_path(out_path: Path) -> Path:
    return out_path.with_name(f"{out_path.name}.part")


def download_chapter(chapter_url: str, session: requests.Session, out_path: Path, output_format: str) -> None:
    print(f"Fetching chapter page: {chapter_url}", file=sys.stderr)
    image_urls = fetch_page_image_urls(chapter_url, session)
    print(f"Found {len(image_urls)} pages.", file=sys.stderr)

    images: List[Image.Image] = []
    temp_path = build_temp_output_path(out_path)
    try:
        for i, img_url in enumerate(image_urls, 1):
            print(f"  [{i}/{len(image_urls)}] {img_url}", file=sys.stderr)
            images.append(download_image(img_url, session, referer=chapter_url))

        out_path.parent.mkdir(parents=True, exist_ok=True) if out_path.parent != Path("") else None
        if temp_path.exists():
            temp_path.unlink()
        if output_format == "pdf":
            save_as_pdf(images, temp_path)
        else:
            save_as_long_jpg(images, temp_path)
        os.replace(temp_path, out_path)
    finally:
        for image in images:
            image.close()

    print(f"Saved: {out_path.resolve()}", file=sys.stderr)


def parse_range(range_text: str) -> tuple[Decimal, Decimal]:
    match = re.fullmatch(r"\s*(\d+(?:\.\d+)?)\s*-\s*(\d+(?:\.\d+)?)\s*", range_text)
    if not match:
        raise ValueError("Range must look like START-END, for example 121-249.")

    start = parse_chapter_number(match.group(1))
    end = parse_chapter_number(match.group(2))
    if start > end:
        raise ValueError("Range start must be less than or equal to range end.")
    return start, end


def build_batch_directory_name(entries: List[ChapterEntry], start: Decimal, end: Decimal) -> str:
    series_prefix = safe_filename(entries[0].slug.rsplit("-chapter-", 1)[0] or "manga")
    return f"{series_prefix}_chapters_{format_chapter_number(start)}_{format_chapter_number(end)}"


def build_batch_output_path(out_dir: Path, entry: ChapterEntry, output_format: str, pad_width: int) -> Path:
    chapter_label = format_chapter_number(entry.number_value)
    if chapter_label.isdigit():
        chapter_label = chapter_label.zfill(pad_width)
    filename = f"chapter_{safe_filename(chapter_label)}.{output_format}"
    return out_dir / filename


def download_batch_chapter(
    entry: ChapterEntry,
    out_dir: Path,
    output_format: str,
    pad_width: int,
) -> ChapterResult:
    out_path = build_batch_output_path(out_dir, entry, output_format, pad_width)
    if out_path.exists():
        print(f"Skipping existing file: {out_path}", file=sys.stderr)
        return ChapterResult(entry=entry, out_path=out_path, status="skipped")

    session = create_session()
    try:
        download_chapter(entry.url, session, out_path, output_format)
    except Exception as exc:
        return ChapterResult(entry=entry, out_path=out_path, status="failed", error=str(exc))
    finally:
        session.close()

    return ChapterResult(entry=entry, out_path=out_path, status="downloaded")


def main() -> int:
    parser = argparse.ArgumentParser(description="Download a MangaWorld or Mangapill chapter into one PDF or JPG.")
    parser.add_argument(
        "url",
        help=(
            "Chapter or manga URL, e.g. "
            "https://www.mangaworld.mx/manga/.../read/.../1 or "
            "https://mangapill.com/chapters/11-10121000/20-seiki-shounen-chapter-121 or "
            "https://mangapill.com/manga/11"
        ),
    )
    parser.add_argument(
        "-f", "--format", choices=("pdf", "jpg"), default="pdf",
        help="Output format (default: pdf).",
    )
    parser.add_argument(
        "-r", "--range",
        dest="chapter_range",
        help="Download a chapter range from a Mangapill manga/chapter URL, e.g. 121-249.",
    )
    parser.add_argument(
        "-o", "--output",
        help="Output file path for single chapter, or output directory for batch range mode.",
    )
    parser.add_argument(
        "-j", "--jobs",
        type=int,
        default=1,
        help="Number of chapters to download in parallel in batch mode (default: 1).",
    )
    args = parser.parse_args()

    if args.chapter_range:
        if args.jobs < 1:
            raise ValueError("--jobs must be at least 1.")

        session = create_session()
        start, end = parse_range(args.chapter_range)
        try:
            all_entries = fetch_mangapill_chapter_entries(args.url, session)
        finally:
            session.close()
        selected_entries = [entry for entry in all_entries if start <= entry.number_value <= end]
        if not selected_entries:
            raise RuntimeError("No chapters found in the requested range.")

        out_dir = Path(args.output) if args.output else Path(build_batch_directory_name(selected_entries, start, end))
        out_dir.mkdir(parents=True, exist_ok=True)
        pad_width = max(len(format_chapter_number(entry.number_value).split(".", 1)[0]) for entry in selected_entries)

        print(
            f"Downloading {len(selected_entries)} chapters into {out_dir.resolve()} with {args.jobs} job(s)",
            file=sys.stderr,
        )
        pending_entries: List[ChapterEntry] = []
        for index, entry in enumerate(selected_entries, 1):
            out_path = build_batch_output_path(out_dir, entry, args.format, pad_width)
            print(
                f"[{index}/{len(selected_entries)}] Chapter {format_chapter_number(entry.number_value)} -> {out_path.name}",
                file=sys.stderr,
            )
            if out_path.exists():
                print(f"Skipping existing file: {out_path}", file=sys.stderr)
                continue
            pending_entries.append(entry)

        if not pending_entries:
            print("Nothing to do. All chapter files already exist.", file=sys.stderr)
            return 0

        failures: List[ChapterResult] = []
        with concurrent.futures.ThreadPoolExecutor(max_workers=args.jobs) as executor:
            futures = {
                executor.submit(download_batch_chapter, entry, out_dir, args.format, pad_width): entry
                for entry in pending_entries
            }
            for future in concurrent.futures.as_completed(futures):
                result = future.result()
                if result.status == "failed":
                    failures.append(result)

        if failures:
            failed_labels = ", ".join(format_chapter_number(item.entry.number_value) for item in failures)
            raise RuntimeError(f"Some chapters failed: {failed_labels}")
        return 0

    session = create_session()
    default_name = derive_default_name(args.url)
    out_path = Path(args.output) if args.output else Path(f"{default_name}.{args.format}")
    try:
        download_chapter(args.url, session, out_path, args.format)
    finally:
        session.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
