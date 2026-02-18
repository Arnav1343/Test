#!/usr/bin/env python3
"""
Song-to-MP3 Downloader
============================
Usage:
    python main.py "Song Name"
    python main.py "Song Name" --artist "Artist Name"
    python main.py "Song Name" --output ./music --quality 192
"""

import argparse
import os
import sys
import time
from pathlib import Path

# Force UTF-8 on Windows consoles to avoid cp1252 encoding errors
if sys.platform == "win32":
    os.system("")  # enable ANSI escape codes on Windows 10+
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if hasattr(sys.stderr, "reconfigure"):
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")

from scraper import SongScraper, _human_size


# ── ANSI colours (works on Windows 10+ with VT support) ────────────────
class _C:
    BOLD   = "\033[1m"
    GREEN  = "\033[92m"
    CYAN   = "\033[96m"
    YELLOW = "\033[93m"
    RED    = "\033[91m"
    DIM    = "\033[2m"
    RESET  = "\033[0m"


def _banner():
    print(f"""
{_C.CYAN}{_C.BOLD}╔══════════════════════════════════════╗
║   🎵  Song-to-MP3 Downloader  🎵    ║
╚══════════════════════════════════════╝{_C.RESET}
""")


def _progress_hook(d: dict):
    """Pretty progress bar for yt-dlp."""
    if d["status"] == "downloading":
        total = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
        downloaded = d.get("downloaded_bytes", 0)
        speed = d.get("speed") or 0
        if total > 0:
            pct = downloaded / total
            bar_len = 30
            filled = int(bar_len * pct)
            bar = "█" * filled + "░" * (bar_len - filled)
            speed_str = _human_size(speed) + "/s" if speed else "..."
            print(
                f"\r  {_C.CYAN}⬇  [{bar}] {pct:.0%}  {speed_str}{_C.RESET}",
                end="",
                flush=True,
            )
    elif d["status"] == "finished":
        print(f"\r  {_C.GREEN}✓  Download complete, converting to MP3...{_C.RESET}        ")


def main():
    parser = argparse.ArgumentParser(
        description="Download any song as MP3 by name.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        "song",
        help='Song name to search for (e.g. "Bohemian Rhapsody")',
    )
    parser.add_argument(
        "--artist", "-a",
        default="",
        help="Artist name for a more precise search",
    )
    parser.add_argument(
        "--output", "-o",
        default="downloads",
        help="Output directory (default: ./downloads)",
    )
    parser.add_argument(
        "--quality", "-q",
        type=int,
        default=320,
        choices=[128, 192, 256, 320],
        help="MP3 bitrate in kbps (default: 320)",
    )

    args = parser.parse_args()

    _banner()

    # Build search query
    query = args.song
    if args.artist:
        query = f"{args.artist} - {query}"

    print(f"  {_C.BOLD}🔍  Searching:{_C.RESET}  {query}")
    print()

    try:
        scraper = SongScraper(quality=args.quality)

        # ── Search ──────────────────────────────────────────────────
        t0 = time.time()
        metadata = scraper.search(query)
        search_time = time.time() - t0

        print(f"  {_C.GREEN}✓  Found:{_C.RESET}     {metadata['title']}")
        print(f"  {_C.DIM}   Uploader:   {metadata['uploader']}{_C.RESET}")
        duration_m, duration_s = divmod(metadata.get("duration", 0), 60)
        print(f"  {_C.DIM}   Duration:   {int(duration_m)}:{int(duration_s):02d}{_C.RESET}")
        print(f"  {_C.DIM}   URL:        {metadata['url']}{_C.RESET}")
        print(f"  {_C.DIM}   Search:     {search_time:.1f}s{_C.RESET}")
        print()

        # ── Download ────────────────────────────────────────────────
        t0 = time.time()
        mp3_path = scraper.download(
            url=metadata["url"],
            output_dir=args.output,
            progress_hook=_progress_hook,
        )
        dl_time = time.time() - t0

        file_size = mp3_path.stat().st_size
        print()
        print(f"  {_C.GREEN}{_C.BOLD}🎶  Done!{_C.RESET}")
        print(f"  {_C.BOLD}   File:{_C.RESET}       {mp3_path}")
        print(f"  {_C.BOLD}   Size:{_C.RESET}       {_human_size(file_size)}")
        print(f"  {_C.BOLD}   Quality:{_C.RESET}    {args.quality} kbps MP3")
        print(f"  {_C.BOLD}   Time:{_C.RESET}       {dl_time:.1f}s")
        print()

    except LookupError as e:
        print(f"\n  {_C.RED}✗  {e}{_C.RESET}\n")
        sys.exit(1)
    except FileNotFoundError as e:
        print(f"\n  {_C.RED}✗  {e}{_C.RESET}")
        print(f"  {_C.YELLOW}   Make sure ffmpeg is installed and on your PATH.{_C.RESET}\n")
        sys.exit(1)
    except Exception as e:
        print(f"\n  {_C.RED}✗  Unexpected error: {e}{_C.RESET}\n")
        sys.exit(1)


if __name__ == "__main__":
    main()
