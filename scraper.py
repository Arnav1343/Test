"""
Song-to-MP3 Scraper  (Spotify + YouTube Music Edition)
======================================================
Uses spotdl to search Spotify for song metadata and download
the matching audio from YouTube Music as a max-quality MP3.

Quality features:
  - Spotify metadata: title, artist, album, album art
  - YouTube Music audio source (highest quality match)
  - 320 kbps MP3 output
  - Embedded album art and metadata
"""

import os
import re
import json
import time
import logging
import subprocess
from pathlib import Path
from typing import Optional

import yt_dlp

logger = logging.getLogger(__name__)


class SongScraper:
    """Search Spotify for songs and download audio from YouTube Music."""

    MAX_DURATION = 900  # 15 minutes

    def __init__(self, quality: int = 320):
        if quality not in (128, 192, 256, 320):
            raise ValueError(f"Invalid quality {quality}. Choose 128, 192, 256, or 320.")
        self.quality = quality

    # ------------------------------------------------------------------
    # Search  (Spotify via spotdl)
    # ------------------------------------------------------------------

    def search(self, query: str) -> dict:
        """
        Search Spotify for the best match.

        Returns:
            dict with keys: id, title, url, duration, uploader, thumbnail,
                            artist, album, spotify_url
        """
        logger.info("Searching Spotify for: %s", query)

        try:
            result = subprocess.run(
                ["spotdl", "url", query],
                capture_output=True, text=True, timeout=30,
                encoding="utf-8", errors="replace",
            )
            urls = [line.strip() for line in result.stdout.strip().splitlines() if line.strip().startswith("http")]

            if not urls:
                raise LookupError(f"No Spotify results for '{query}'")

            spotify_url = urls[0]

            # Get metadata from spotdl
            meta_result = subprocess.run(
                ["spotdl", "meta", spotify_url, "--json"],
                capture_output=True, text=True, timeout=30,
                encoding="utf-8", errors="replace",
            )

            meta = None
            for line in meta_result.stdout.strip().splitlines():
                line = line.strip()
                if line.startswith("{"):
                    try:
                        meta = json.loads(line)
                        break
                    except json.JSONDecodeError:
                        continue

            if meta:
                return {
                    "id": meta.get("song_id", ""),
                    "title": meta.get("name", query),
                    "url": spotify_url,
                    "duration": meta.get("duration", 0),
                    "uploader": ", ".join(meta.get("artists", ["Unknown"])),
                    "thumbnail": meta.get("cover_url", ""),
                    "artist": ", ".join(meta.get("artists", ["Unknown"])),
                    "album": meta.get("album_name", ""),
                    "spotify_url": spotify_url,
                }

        except (subprocess.TimeoutExpired, FileNotFoundError, LookupError):
            pass
        except Exception as e:
            logger.warning("spotdl search failed, falling back to YouTube Music: %s", e)

        # Fallback: YouTube Music search via yt-dlp
        return self._search_ytmusic(query)

    def search_multi(self, query: str, limit: int = 5) -> list[dict]:
        """
        Return multiple search results quickly for autocomplete suggestions.
        Uses extract_flat for speed — returns basic metadata only.
        """
        logger.info("Multi-search for: %s", query)
        results = []

        try:
            ydl_opts = {
                "quiet": True,
                "no_warnings": True,
                "extract_flat": "in_playlist",
                "default_search": f"ytsearch{limit}",
                "noplaylist": True,
                "skip_download": True,
            }

            with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                info = ydl.extract_info(query, download=False)

            entries = info.get("entries", [])
            for entry in entries:
                if entry is None:
                    continue
                vid_id = entry.get("id", "")
                results.append({
                    "title": entry.get("title", "Unknown"),
                    "artist": entry.get("uploader", entry.get("channel", "")),
                    "album": "",
                    "duration": entry.get("duration") or 0,
                    "url": entry.get("url") or entry.get("webpage_url") or f"https://www.youtube.com/watch?v={vid_id}",
                    "thumbnail": f"https://i.ytimg.com/vi/{vid_id}/mqdefault.jpg" if vid_id else "",
                })

        except Exception as e:
            logger.warning("Multi-search failed: %s", e)

        return results[:limit]

    def _search_ytmusic(self, query: str) -> dict:
        """Fallback search via YouTube Music when Spotify search fails."""
        logger.info("Falling back to YouTube Music search for: %s", query)

        ydl_opts = {
            "quiet": True,
            "no_warnings": True,
            "extract_flat": False,
            "default_search": f"ytsearch5",
            "noplaylist": True,
            "skip_download": True,
        }

        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(query, download=False)

        entries = info.get("entries", [])
        if not entries:
            raise LookupError(f"No results found for '{query}'")

        best = entries[0]
        for entry in entries:
            if entry and 90 <= (entry.get("duration") or 0) <= 420:
                best = entry
                break

        return {
            "id": best.get("id", ""),
            "title": best.get("title", "Unknown"),
            "url": best.get("webpage_url") or f"https://www.youtube.com/watch?v={best.get('id', '')}",
            "duration": best.get("duration", 0),
            "uploader": best.get("uploader", "Unknown"),
            "thumbnail": best.get("thumbnail", ""),
            "artist": best.get("uploader", "Unknown"),
            "album": "",
            "spotify_url": "",
        }

    # ------------------------------------------------------------------
    # Download  (spotdl with fallback to yt-dlp)
    # ------------------------------------------------------------------

    def download(
        self,
        url: str,
        output_dir: str | Path = "downloads",
        progress_hook=None,
    ) -> Path:
        """
        Download audio and convert to MP3.

        Tries spotdl first (best for Spotify URLs), falls back to yt-dlp
        for direct YouTube URLs.
        """
        output_dir = Path(output_dir)
        output_dir.mkdir(parents=True, exist_ok=True)

        is_spotify = "spotify.com" in url or "open.spotify" in url

        if is_spotify:
            try:
                return self._download_spotdl(url, output_dir, progress_hook)
            except Exception as e:
                logger.warning("spotdl download failed, falling back to yt-dlp: %s", e)

        return self._download_ytdlp(url, output_dir, progress_hook)

    def _download_spotdl(self, url: str, output_dir: Path, progress_hook=None) -> Path:
        """Download via spotdl (for Spotify URLs)."""
        logger.info("Downloading via spotdl: %s -> %s", url, output_dir)

        # Track existing mp3 files to find the new one
        before = set(output_dir.glob("*.mp3"))

        if progress_hook:
            progress_hook({"status": "downloading", "downloaded_bytes": 0, "total_bytes": 100})

        cmd = [
            "spotdl", "download", url,
            "--output", str(output_dir),
            "--format", "mp3",
            "--bitrate", f"{self.quality}k",
            "--threads", "4",
        ]

        result = subprocess.run(
            cmd, capture_output=True, text=True, timeout=120,
            encoding="utf-8", errors="replace",
        )

        if progress_hook:
            progress_hook({"status": "finished"})

        # Find the new mp3 file
        after = set(output_dir.glob("*.mp3"))
        new_files = after - before

        if new_files:
            mp3_path = max(new_files, key=lambda p: p.stat().st_mtime)
        else:
            # Might have overwritten an existing file
            mp3_path = self._find_mp3(output_dir, "")

        if mp3_path is None:
            raise FileNotFoundError(
                f"spotdl appeared to run but no .mp3 found in {output_dir}. "
                f"stderr: {result.stderr[:200]}"
            )

        logger.info("Saved: %s (%s)", mp3_path, _human_size(mp3_path.stat().st_size))
        return mp3_path

    def _download_ytdlp(self, url: str, output_dir: Path, progress_hook=None) -> Path:
        """Download via yt-dlp (for YouTube URLs or as fallback)."""
        logger.info("Downloading via yt-dlp: %s -> %s", url, output_dir)

        outtmpl = str(output_dir / "%(title)s.%(ext)s")

        ydl_opts = {
            "format": "bestaudio[asr>=44100]/bestaudio/best",
            "format_sort": ["abr", "asr"],
            "outtmpl": outtmpl,
            "noplaylist": True,
            "quiet": True,
            "no_warnings": True,
            "concurrent_fragment_downloads": 4,
            "buffersize": 1024 * 64,
            "writethumbnail": True,
            "postprocessors": [
                {
                    "key": "FFmpegExtractAudio",
                    "preferredcodec": "mp3",
                    "preferredquality": str(self.quality),
                },
                {"key": "FFmpegMetadata", "add_metadata": True},
                {"key": "EmbedThumbnail", "already_have_thumbnail": False},
            ],
            "postprocessor_args": {
                "extractaudio": ["-b:a", "320k", "-joint_stereo", "0"],
            },
            "restrictfilenames": False,
            "windowsfilenames": True,
        }

        if progress_hook:
            ydl_opts["progress_hooks"] = [progress_hook]

        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(url, download=True)

        title = info.get("title", "audio")
        mp3_path = self._find_mp3(output_dir, title)

        if mp3_path is None:
            raise FileNotFoundError(
                f"Download appeared to succeed but no .mp3 found in {output_dir}"
            )

        logger.info("Saved: %s (%s)", mp3_path, _human_size(mp3_path.stat().st_size))
        return mp3_path

    # ------------------------------------------------------------------
    # Combined convenience method
    # ------------------------------------------------------------------

    def search_and_download(
        self,
        query: str,
        output_dir: str | Path = "downloads",
        progress_hook=None,
    ) -> tuple[dict, Path]:
        """Search for a song and download it in one call."""
        metadata = self.search(query)
        mp3_path = self.download(
            url=metadata["url"],
            output_dir=output_dir,
            progress_hook=progress_hook,
        )
        return metadata, mp3_path

    # ------------------------------------------------------------------
    # Helpers
    # ------------------------------------------------------------------

    @staticmethod
    def _find_mp3(directory: Path, title_hint: str) -> Optional[Path]:
        """Find the most recently created .mp3 file in the directory."""
        mp3_files = list(directory.glob("*.mp3"))
        if not mp3_files:
            return None
        return max(mp3_files, key=lambda p: p.stat().st_mtime)


def _human_size(nbytes: int) -> str:
    """Format byte count as human-readable string."""
    for unit in ("B", "KB", "MB", "GB"):
        if nbytes < 1024:
            return f"{nbytes:.1f} {unit}"
        nbytes /= 1024
    return f"{nbytes:.1f} TB"
