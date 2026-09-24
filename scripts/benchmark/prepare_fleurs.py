#!/usr/bin/env python3
"""Prepare a deterministic, speaker-split Dutch ASR/MT benchmark outside Git.

FLEURS audio and matching English references are fetched at one immutable
Hugging Face revision. The video source is handled separately by yt-dlp.
No sample audio or model weights are written to the repository.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import urllib.request
import wave
from pathlib import Path

import numpy as np
import pyarrow.parquet as pq
import soundfile as sf


REVISION = "168de341b3db6859a9bac1c50a2ef5e3b47647e0"
FLEURS_URL = f"https://huggingface.co/datasets/google/fleurs/resolve/{REVISION}"
FILE_PINS = {
    ("nl_nl", "train"): (1_761_841_308, "9e3587d4c19f27566e29de1df815a2755c4ae6a352637cc2ce122f2980000f05"),
    ("nl_nl", "validation"): (101_073_327, "257cb6c6b02692f8b0a919a59d689e210b091d617adec5f2234706282dea66c5"),
    ("nl_nl", "test"): (216_860_601, "a89456e5219b9cd311d1fedc9088a8fcaa4724c840de6db32df613a7968b15a1"),
    ("en_us", "train"): (1_721_501_976, "0472c0b6eb31427c50350e8d44d5151ec9febfe26ffceaa6806c894806e87c07"),
    ("en_us", "validation"): (236_549_523, "7c3eeb11a9597bd52cdc1b0d637e85389fe094cfd8763913e7bf4fdf7a853959"),
    ("en_us", "test"): (401_722_686, "6428a4d04d3aac29e16b45e039bb1470a8bd7aa334cf92f7984c9c520d1f234d"),
}
COUNTS = {"train": 30, "validation": 10, "test": 10}
SPLIT_LABEL = {"train": "tuning", "validation": "validation", "test": "final_holdout"}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(8 * 1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def verified(path: Path, size: int, digest: str) -> bool:
    return path.is_file() and path.stat().st_size == size and sha256(path) == digest


def fetch(url: str, path: Path, size: int, digest: str) -> None:
    if verified(path, size, digest):
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".part")
    with urllib.request.urlopen(url, timeout=180) as response, temporary.open("wb") as output:
        while block := response.read(8 * 1024 * 1024):
            output.write(block)
    if not verified(temporary, size, digest):
        raise RuntimeError(f"Pinned data integrity check failed: {temporary}")
    temporary.replace(path)


def parquet_path(root: Path, language: str, split: str) -> Path:
    size, digest = FILE_PINS[(language, split)]
    canonical = root / "fleurs" / language / split / "0000.parquet"
    # Reuse already downloaded verified shards without duplicating several GB.
    alternatives = [
        root / ("fleurs" if language == "nl_nl" else "fleurs-en") / f"{split}.parquet",
    ]
    if language == "nl_nl" and split == "test":
        alternatives.append(root.parent / "chunk-smoke" / "dutch.parquet")
    for candidate in [canonical, *alternatives]:
        if verified(candidate, size, digest):
            return candidate
    fetch(f"{FLEURS_URL}/{language}/{split}/0000.parquet", canonical, size, digest)
    return canonical


def hashed_rank(split: str, row_id: int) -> str:
    return hashlib.sha256(f"live-captions-bench-v1:{split}:{row_id}".encode()).hexdigest()


def choose_rows(rows: list[dict], split: str, count: int) -> list[dict]:
    # Samples are duration-stratified, then deterministically randomized inside
    # each decile. 3/1/1 per decile yields an exact 60/20/20 corpus split.
    rows.sort(key=lambda row: (row["num_samples"], row["id"]))
    bins: list[list[dict]] = [[] for _ in range(10)]
    for index, row in enumerate(rows):
        bins[min(9, index * 10 // len(rows))].append(row)
    quota = count // 10
    if quota * 10 != count:
        raise ValueError("sample count must be a multiple of ten")
    chosen = []
    for bucket in bins:
        chosen.extend(sorted(bucket, key=lambda row: hashed_rank(split, row["id"]))[:quota])
    return sorted(chosen, key=lambda row: (row["num_samples"], row["id"]))


def read_metadata(path: Path) -> list[dict]:
    table = pq.read_table(path, columns=["id", "num_samples", "raw_transcription", "transcription", "gender"])
    return table.to_pylist()


def write_pcm_wav(path: Path, encoded: bytes) -> int:
    samples, rate = sf.read(io.BytesIO(encoded), dtype="float32", always_2d=False)
    if rate != 16_000 or samples.ndim != 1:
        raise RuntimeError(f"FLEURS audio must already be mono/16 kHz, received {rate} Hz/{samples.shape}")
    pcm = np.rint(np.clip(samples, -1.0, 32767 / 32768) * 32768).astype("<i2")
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(16_000)
        output.writeframes(pcm.tobytes())
    return int(pcm.size)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path("build/benchmark-data"))
    args = parser.parse_args()

    paths = {(language, split): parquet_path(args.root, language, split)
             for language in ("nl_nl", "en_us") for split in ("train", "validation", "test")}
    manifest = {
        "schema": 1,
        "dataset": "google/fleurs",
        "revision": REVISION,
        "license": "CC-BY-4.0",
        "split_policy": "FLEURS official train/validation/test; 30/10/10 duration-stratified samples; final_holdout excluded from tuning and validation runs",
        "samples": [],
    }

    for split, count in COUNTS.items():
        source_rows = read_metadata(paths[("nl_nl", split)])
        english_rows = read_metadata(paths[("en_us", split)])
        english_by_id = {row["id"]: row["raw_transcription"] or row["transcription"] for row in english_rows}
        candidates = [row for row in source_rows if row["id"] in english_by_id]
        if split == "test":
            # This row was already consumed by the repository's pre-existing
            # smoke test and is excluded from our untouched holdout.
            candidates = [row for row in candidates if row["id"] != 1927]
        selected = choose_rows(candidates, split, count)
        selected_ids = {row["id"] for row in selected}
        audio_by_id: dict[int, bytes] = {}
        parquet = pq.ParquetFile(paths[("nl_nl", split)])
        for batch in parquet.iter_batches(columns=["id", "audio"], batch_size=32):
            for row in batch.to_pylist():
                if row["id"] in selected_ids:
                    audio_by_id[row["id"]] = row["audio"]["bytes"]
        if selected_ids != audio_by_id.keys():
            raise RuntimeError(f"Missing selected FLEURS audio rows for {split}")

        for row in selected:
            sample_id = row["id"]
            audio_path = args.root / "samples" / SPLIT_LABEL[split] / f"nl_nl-{sample_id}.wav"
            sample_count = write_pcm_wav(audio_path, audio_by_id[sample_id])
            raw = row["raw_transcription"] or row["transcription"]
            manifest["samples"].append({
                "id": f"fleurs-{split}-{sample_id}",
                "source_split": split,
                "benchmark_split": SPLIT_LABEL[split],
                "row_id": sample_id,
                "speaker_group": "FLEURS train" if split == "train" else "FLEURS dev/test",
                "gender_id": row["gender"],
                "duration_seconds": sample_count / 16_000,
                "sample_rate": 16_000,
                "channels": 1,
                "pcm_format": "s16le",
                "audio_path": audio_path.relative_to(args.root).as_posix(),
                "audio_sha256": sha256(audio_path),
                "reference_nl": raw,
                "reference_en": english_by_id[sample_id],
                "reference_kind": "parallel FLEURS English transcription; not a translation-model output",
                "contains_digits": any(char.isdigit() for char in raw),
            })

    output = args.root / "manifest.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    counts = {name: sum(sample["benchmark_split"] == name for sample in manifest["samples"])
              for name in ("tuning", "validation", "final_holdout")}
    print(json.dumps({"manifest": str(output), "counts": counts,
                      "official_holdout_used": False,
                      "sample_audio_bytes": sum((args.root / s["audio_path"]).stat().st_size
                                                 for s in manifest["samples"])}))


if __name__ == "__main__":
    main()
