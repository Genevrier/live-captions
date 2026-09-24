#!/usr/bin/env python3
"""Create one full-clip Dutch robustness case from the video's automatic VTT.

The result is explicitly pseudo-ground-truth and is never part of the FLEURS
tuning, validation or final hold-out splits.
"""
from __future__ import annotations

import argparse
import hashlib
import html
import json
import re
from pathlib import Path


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(8 * 1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def normalize_word(word: str) -> str:
    return re.sub(r"[^\w']", "", word.casefold(), flags=re.UNICODE)


def transcript_from_vtt(path: Path) -> str:
    cues = []
    lines = path.read_text(encoding="utf-8").splitlines()
    timing = re.compile(r"\d{2}:\d{2}:\d{2}[.,]\d{3}\s+-->\s+")
    current = None
    for line in lines:
        if timing.match(line):
            if current is not None:
                cues.append(" ".join(current))
            current = []
        elif current is not None and line.strip():
            text = re.sub(r"<\d{2}:\d{2}:\d{2}[.,]\d{3}>", " ", line)
            text = re.sub(r"</?c(?:\s+[^>]*)?>", " ", text)
            text = html.unescape(text)
            current.append(text)
    if current is not None:
        cues.append(" ".join(current))

    assembled: list[str] = []
    canonical: list[str] = []
    for cue in cues:
        words = re.findall(r"[\w]+(?:['’][\w]+)*|[^\w\s]", re.sub(r"\s+", " ", cue), re.UNICODE)
        words = [word for word in words if word.strip()]
        if not words:
            continue
        keys = [normalize_word(word) for word in words]
        overlap = 0
        for size in range(min(len(keys), len(canonical)), 0, -1):
            if canonical[-size:] == keys[:size]:
                overlap = size
                break
        if overlap == len(words):
            continue
        assembled.extend(words[overlap:])
        canonical.extend(keys[overlap:])
    # Punctuation tokens are attached to their preceding word; whitespace is
    # otherwise normalized so the metric tokenizer sees ordinary Dutch text.
    result = ""
    for token in assembled:
        if token in ".,!?;:%)]}”’":
            result = result.rstrip() + token + " "
        elif token in "([{“‘":
            result += token
        else:
            result += token + " "
    return re.sub(r"\s+", " ", result).strip()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path("build/benchmark-data/youtube"))
    parser.add_argument("--out", type=Path, default=Path("build/benchmark-data/youtube-robustness.json"))
    args = parser.parse_args()
    manifest = json.loads((args.root / "manifest.json").read_text())
    wav = args.root / manifest["benchmark_audio"]["path"]
    if sha256(wav) != manifest["benchmark_audio"]["sha256"]:
        raise RuntimeError("Converted YouTube benchmark WAV checksum mismatch")
    caption = next((args.root / item["path"] for item in manifest["captions"]
                    if item["language"] == "nl-orig"), None)
    if caption is None:
        raise RuntimeError("Original Dutch caption track (nl-orig) was not downloaded")
    reference = transcript_from_vtt(caption)
    if len(reference.split()) < 100:
        raise RuntimeError("Automatic reference transcript unexpectedly short")
    sample = {
        "schema": 1, "dataset": "youtube-robustness", "video_id": manifest["video_id"],
        "id": f"youtube-{manifest['video_id']}", "row_id": manifest["video_id"],
        "title": manifest["title"], "duration_seconds": manifest["duration_seconds"],
        "benchmark_split": "robustness", "audio_path": wav.relative_to(args.root.parent).as_posix(),
        "audio_sha256": sha256(wav), "sample_rate": 16000, "channels": 1,
        "pcm_format": "s16le", "reference_nl": reference,
        "reference_kind": "PSEUDO-GROUND-TRUTH; YouTube automatic Dutch captions, not human-created",
        "caption_sha256": sha256(caption),
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps({"samples": [sample]}, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({"output": str(args.out), "words": len(reference.split()),
                      "reference_kind": sample["reference_kind"], "audio_sha256": sample["audio_sha256"]}))


if __name__ == "__main__":
    main()
