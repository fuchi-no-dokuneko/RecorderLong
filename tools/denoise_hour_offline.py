#!/usr/bin/env python3
"""Offline denoise converter for RecorderLong hourly M4A files.

The script keeps audio local. It decodes with a local ffmpeg binary, runs a
local DeepFilterNet command, then writes a cleaned M4A with local ffmpeg.
"""

from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Denoise one RecorderLong hourly recording using local offline tools."
    )
    parser.add_argument("input", type=Path, help="Input hourly .m4a file.")
    parser.add_argument("-o", "--output", type=Path, help="Output cleaned .m4a path.")
    parser.add_argument(
        "--work-dir",
        type=Path,
        help="Directory for temporary WAV files. Defaults beside the output file.",
    )
    parser.add_argument(
        "--keep-temp",
        action="store_true",
        help="Keep temporary WAV files for inspection.",
    )
    return parser.parse_args()


def find_tool(*names: str) -> str | None:
    candidates: list[Path] = []
    exe_dir = Path(sys.executable).resolve().parent
    for name in names:
        candidates.append(exe_dir / name)
        found = shutil.which(name)
        if found:
            return found
    for candidate in candidates:
        if candidate.exists() and candidate.is_file():
            return str(candidate)
    return None


def run(command: list[str], cwd: Path | None = None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        command,
        cwd=cwd,
        check=True,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )


def run_deepfilter(command: str, wav_in: Path, temp_dir: Path) -> Path:
    output_dir = temp_dir / "deepfilter-output"
    output_dir.mkdir(parents=True, exist_ok=True)

    attempts = [
        [command, str(wav_in), "--output-dir", str(output_dir)],
        [command, str(wav_in), "-o", str(output_dir)],
        [command, str(wav_in)],
    ]
    last_error = ""
    before = {path.resolve() for path in temp_dir.rglob("*.wav")}
    for attempt in attempts:
        try:
            run(attempt, cwd=temp_dir)
        except subprocess.CalledProcessError as exc:
            last_error = (exc.stderr or exc.stdout or str(exc)).strip()
            continue

        created = [
            path
            for path in temp_dir.rglob("*.wav")
            if path.resolve() not in before and path.resolve() != wav_in.resolve()
        ]
        if created:
            return max(created, key=lambda path: path.stat().st_mtime)

    raise RuntimeError(
        "DeepFilterNet did not produce an enhanced WAV. "
        "Install local tools with tools/setup_offline_audio_env.sh. "
        f"Last error: {last_error}"
    )


def denoise(input_path: Path, output_path: Path, work_dir: Path, keep_temp: bool) -> None:
    ffmpeg = find_tool("ffmpeg")
    deepfilter = find_tool("deepFilter", "deep-filter")
    if not ffmpeg:
        raise RuntimeError("ffmpeg was not found. Run tools/setup_offline_audio_env.sh first.")
    if not deepfilter:
        raise RuntimeError("DeepFilterNet command was not found. Run tools/setup_offline_audio_env.sh first.")

    work_dir.mkdir(parents=True, exist_ok=True)
    temp_path = Path(tempfile.mkdtemp(prefix="recorderlong-denoise-", dir=work_dir))
    try:
        decoded_wav = temp_path / "decoded.wav"
        run([
            ffmpeg,
            "-nostdin",
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-i",
            str(input_path),
            "-ac",
            "1",
            "-ar",
            "48000",
            str(decoded_wav),
        ])

        enhanced_wav = run_deepfilter(deepfilter, decoded_wav, temp_path)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        run([
            ffmpeg,
            "-nostdin",
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-i",
            str(enhanced_wav),
            "-c:a",
            "aac",
            "-b:a",
            "96k",
            str(output_path),
        ])
    finally:
        if keep_temp:
            print(f"Kept temp files in {temp_path}")
        else:
            shutil.rmtree(temp_path, ignore_errors=True)


def main() -> int:
    args = parse_args()
    input_path = args.input.expanduser().resolve()
    if not input_path.exists():
        print(f"Input file not found: {input_path}", file=sys.stderr)
        return 2

    output_path = args.output
    if output_path is None:
        output_path = input_path.with_name(input_path.stem + "_denoised.m4a")
    output_path = output_path.expanduser().resolve()

    work_dir = args.work_dir.expanduser().resolve() if args.work_dir else output_path.parent
    try:
        denoise(input_path, output_path, work_dir, args.keep_temp)
    except (RuntimeError, subprocess.CalledProcessError) as exc:
        print(f"Failed: {exc}", file=sys.stderr)
        return 1

    print(output_path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
