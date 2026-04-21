#!/usr/bin/env python3
"""
MangaWorld downloader.

Given a chapter URL like
    https://www.mangaworld.mx/manga/2726/20th-century-boys/read/62633081bc201e40421ea7b7/1?style=list
downloads every page and merges them into a single PDF (or a single tall JPG).
"""

from __future__ import annotations

import argparse
import io
import os
import re
import sys
from pathlib import Path
from typing import List
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


def force_list_style(url: str) -> str:
    """Ensure the URL uses ?style=list so every page image is in one DOM."""
    parsed = urlparse(url)
    query = dict(parse_qsl(parsed.query))
    query["style"] = "list"
    return urlunparse(parsed._replace(query=urlencode(query)))


def fetch_page_image_urls(chapter_url: str, session: requests.Session) -> List[str]:
    url = force_list_style(chapter_url)
    resp = session.get(url, headers=HEADERS, timeout=30)
    resp.raise_for_status()

    soup = BeautifulSoup(resp.text, "html.parser")
    imgs = soup.select("img.page-image")

    urls: List[str] = []
    for img in imgs:
        src = img.get("src") or img.get("data-src")
        if src:
            urls.append(urljoin(url, src))

    if not urls:
        raise RuntimeError(
            "No page images found. The site layout may have changed, "
            "or the URL is not a chapter reader page."
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


def derive_default_name(chapter_url: str) -> str:
    parts = [p for p in urlparse(chapter_url).path.split("/") if p]
    meaningful = [p for p in parts if not re.fullmatch(r"[0-9a-f]{24}", p) and p not in {"manga", "read"}]
    return safe_filename("_".join(meaningful) or "manga_chapter")


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


def main() -> int:
    parser = argparse.ArgumentParser(description="Download a MangaWorld chapter into one PDF or JPG.")
    parser.add_argument("url", help="Chapter reader URL, e.g. https://www.mangaworld.mx/manga/.../read/.../1")
    parser.add_argument(
        "-f", "--format", choices=("pdf", "jpg"), default="pdf",
        help="Output format (default: pdf).",
    )
    parser.add_argument(
        "-o", "--output",
        help="Output file path. Defaults to a name derived from the URL.",
    )
    args = parser.parse_args()

    session = requests.Session()

    print(f"Fetching chapter page: {args.url}", file=sys.stderr)
    image_urls = fetch_page_image_urls(args.url, session)
    print(f"Found {len(image_urls)} pages.", file=sys.stderr)

    images: List[Image.Image] = []
    for i, img_url in enumerate(image_urls, 1):
        print(f"  [{i}/{len(image_urls)}] {img_url}", file=sys.stderr)
        images.append(download_image(img_url, session, referer=args.url))

    default_name = derive_default_name(args.url)
    out_path = Path(args.output) if args.output else Path(f"{default_name}.{args.format}")
    out_path.parent.mkdir(parents=True, exist_ok=True) if out_path.parent != Path("") else None

    if args.format == "pdf":
        save_as_pdf(images, out_path)
    else:
        save_as_long_jpg(images, out_path)

    print(f"Saved: {out_path.resolve()}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
