#!/usr/bin/env python3
"""Fetch the requested robustness clip and available caption tracks.

The video captions are marked by provenance from yt-dlp metadata. If no human
Dutch captions exist, auto-captions remain pseudo-ground-truth only.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path


VIDEO_ID = "PMZi3f5kfmI"
VIDEO_URL = f"https://www.youtube.com/watch?v={VIDEO_ID}"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(8 * 1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path("build/benchmark-data/youtube"))
    args = parser.parse_args()
    args.root.mkdir(parents=True, exist_ok=True)
    metadata = json.loads(subprocess.check_output([
        "yt-dlp", "--dump-single-json", "--skip-download", "--no-warnings", VIDEO_URL
    ], text=True))

    output = str(args.root / f"{VIDEO_ID}.%(ext)s")
    audio_candidates = [p for p in args.root.glob(f"{VIDEO_ID}.*")
                        if p.suffix not in {".wav", ".vtt", ".json", ".part"}]
    if not audio_candidates:
        subprocess.run(["yt-dlp", "--no-warnings", "-f", "bestaudio/best", "-o", output, VIDEO_URL], check=True)
        audio_candidates = [p for p in args.root.glob(f"{VIDEO_ID}.*")
                            if p.suffix not in {".wav", ".vtt", ".json", ".part"}]
    if not audio_candidates:
        raise RuntimeError("yt-dlp did not save the original audio stream")
    original = max(audio_candidates, key=lambda path: path.stat().st_size)
    converted = args.root / f"{VIDEO_ID}-mono16k.wav"
    subprocess.run(["ffmpeg", "-nostdin", "-y", "-i", str(original), "-ac", "1", "-ar", "16000",
                    "-c:a", "pcm_s16le", str(converted)], check=True)

    # yt-dlp includes live_chat in `subtitles`; it is not a speech caption.
    manual = sorted(language for language in metadata.get("subtitles", {}) if language != "live_chat")
    automatic = sorted(metadata.get("automatic_captions", {}).keys())
    all_caption_languages = sorted(set(manual + automatic))
    subtitle_base = ["yt-dlp", "--no-warnings", "--skip-download", "--write-subs", "--write-auto-subs"]
    caption_attempts = []
    # Fetch speech-language tracks first so an over-broad translated-track
    # request or YouTube rate limit does not prevent the useful source caption.
    for label, languages in (
        ("source-and-English", [lang for lang in ("nl-orig", "nl", "en") if lang in all_caption_languages]),
        ("remaining-available", [lang for lang in all_caption_languages if lang not in {"nl-orig", "nl", "en"}]),
    ):
        if not languages:
            continue
        command = subtitle_base + ["--sub-langs", ",".join(languages), "--sub-format", "vtt", "-o", output, VIDEO_URL]
        result = subprocess.run(command, check=False, capture_output=True, text=True)
        caption_attempts.append({"label": label, "languages": languages, "exit_code": result.returncode,
                                 "error": result.stderr[-2000:] if result.returncode else None})
    captions = []
    for path in sorted(args.root.glob(f"{VIDEO_ID}*.vtt")):
        language = re.sub(rf"^{re.escape(VIDEO_ID)}\.", "", path.stem)
        provenance = "human-created" if language in manual else "automatic"
        captions.append({"path": path.name, "language": language, "provenance": provenance,
                         "pseudo_ground_truth": provenance != "human-created", "bytes": path.stat().st_size,
                         "sha256": sha256(path)})
    manifest = {
        "video_id": VIDEO_ID, "url": VIDEO_URL, "title": metadata.get("title"),
        "duration_seconds": metadata.get("duration"), "audio_language_metadata": metadata.get("language"),
        "manual_caption_languages": manual, "automatic_caption_languages": automatic,
        "caption_download_attempted_languages": all_caption_languages,
        "caption_download_attempts": caption_attempts,
        "captions": captions,
        "original_audio": {"path": original.name, "bytes": original.stat().st_size, "sha256": sha256(original)},
        "benchmark_audio": {"path": converted.name, "bytes": converted.stat().st_size,
                            "sha256": sha256(converted), "sample_rate_hz": 16000,
                            "channels": 1, "pcm": "signed 16-bit little-endian"},
        "language_assessment": "Inspect the original caption track and model output. Automatic Dutch captions indicate Dutch speech but are not human-verified.",
    }
    (args.root / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({"title": manifest["title"], "duration_seconds": manifest["duration_seconds"],
                      "manual_caption_languages": manual, "automatic_caption_languages": automatic,
                      "downloaded_captions": len(captions), "manifest": str(args.root / "manifest.json")}))


if __name__ == "__main__":
    main()
