#!/usr/bin/env python3
"""Rescue or rebuild RecorderLong hourly files from an old session folder.

This script is PC-side and offline. It scans completed minute parts, attempts
local repair for damaged files, then concatenates valid parts into hour files
using ffmpeg stream copy where possible. It keeps memory low by delegating
concat to ffmpeg instead of loading audio into Python.
"""

from __future__ import annotations

import argparse
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


PART_RE = re.compile(r"^rec_(?P<stamp>.+)_part(?P<part>\d+)\.m4a$", re.IGNORECASE)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Select a RecorderLong session and rebuild hourly files from valid minute parts."
    )
    parser.add_argument(
        "root",
        nargs="?",
        type=Path,
        default=Path.cwd(),
        help="RecorderLong root folder or a single session-* folder.",
    )
    parser.add_argument(
        "--list",
        action="store_true",
        help="List detected sessions and exit.",
    )
    parser.add_argument(
        "--session",
        help="Session folder name, numeric list index, or full path.",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        help="Where to write rescued hourly files. Defaults inside the session folder.",
    )
    parser.add_argument(
        "--parts-per-hour",
        type=int,
        default=60,
        help="Number of one-minute parts per output hour file.",
    )
    parser.add_argument(
        "--repair-dir",
        type=Path,
        help="Where to store repaired copies. Defaults to session/.rescued_parts.",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Only report what would be used.",
    )
    return parser.parse_args()


def find_tool(name: str) -> str:
    exe_dir = Path(sys.executable).resolve().parent
    local = exe_dir / name
    found = shutil.which(name)
    if found:
        return found
    if local.exists() and local.is_file():
        return str(local)
    raise RuntimeError(f"{name} was not found. Run tools/setup_offline_audio_env.sh first.")


def run(command: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, check=True, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)


def is_session_dir(path: Path) -> bool:
    return path.is_dir() and path.name.startswith("session-") and any(path.glob("rec_*_part*.m4a"))


def find_sessions(root: Path) -> list[Path]:
    root = root.expanduser().resolve()
    if is_session_dir(root):
        return [root]
    sessions = [path for path in root.iterdir() if is_session_dir(path)] if root.is_dir() else []
    return sorted(sessions, key=lambda path: path.stat().st_mtime, reverse=True)


def choose_session(root: Path, selection: str | None) -> Path:
    sessions = find_sessions(root)
    if selection is None:
        if not sessions:
            raise RuntimeError(f"No session-* folders found under {root}")
        if len(sessions) == 1:
            return sessions[0]
        raise RuntimeError("Multiple sessions found. Run with --list, then pass --session INDEX_OR_NAME.")

    selected = Path(selection).expanduser()
    if selected.exists():
        selected = selected.resolve()
        if not is_session_dir(selected):
            raise RuntimeError(f"Not a RecorderLong session folder: {selected}")
        return selected

    if selection.isdigit():
        index = int(selection) - 1
        if index < 0 or index >= len(sessions):
            raise RuntimeError(f"Session index out of range: {selection}")
        return sessions[index]

    for session in sessions:
        if session.name == selection:
            return session
    raise RuntimeError(f"Session not found: {selection}")


def list_sessions(root: Path) -> None:
    sessions = find_sessions(root)
    if not sessions:
        print(f"No session-* folders found under {root.expanduser().resolve()}")
        return
    for index, session in enumerate(sessions, start=1):
        part_count = len(list(session.glob("rec_*_part*.m4a")))
        print(f"{index:2d}  {session.name}  parts={part_count}  path={session}")


def part_sort_key(path: Path) -> tuple[int, str]:
    match = PART_RE.match(path.name)
    if match:
        return int(match.group("part")), path.name
    return 10**9, path.name


def find_parts(session: Path) -> list[Path]:
    parts = [
        path
        for path in session.glob("*.m4a")
        if PART_RE.match(path.name)
    ]
    return sorted(parts, key=part_sort_key)


def session_stamp(parts: list[Path], session: Path) -> str:
    for part in parts:
        match = PART_RE.match(part.name)
        if match:
            return match.group("stamp")
    return session.name.removeprefix("session-")


def probe_duration(path: Path) -> float | None:
    ffprobe = find_tool("ffprobe")
    try:
        result = run([
            ffprobe,
            "-v",
            "error",
            "-select_streams",
            "a:0",
            "-show_entries",
            "format=duration",
            "-of",
            "default=noprint_wrappers=1:nokey=1",
            str(path),
        ])
    except subprocess.CalledProcessError:
        return None
    try:
        duration = float(result.stdout.strip())
    except ValueError:
        return None
    return duration if duration > 0.0 else None


def try_repair(source: Path, repair_dir: Path) -> Path | None:
    ffmpeg = find_tool("ffmpeg")
    repair_dir.mkdir(parents=True, exist_ok=True)
    remuxed = repair_dir / source.name
    reencoded = repair_dir / source.with_suffix("").name
    reencoded = reencoded.with_name(reencoded.name + "_reencoded.m4a")

    attempts = [
        [
            ffmpeg,
            "-nostdin",
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-err_detect",
            "ignore_err",
            "-i",
            str(source),
            "-map",
            "0:a:0",
            "-c",
            "copy",
            str(remuxed),
        ],
        [
            ffmpeg,
            "-nostdin",
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-err_detect",
            "ignore_err",
            "-i",
            str(source),
            "-map",
            "0:a:0",
            "-c:a",
            "aac",
            "-b:a",
            "96k",
            str(reencoded),
        ],
    ]
    for command in attempts:
        output = Path(command[-1])
        try:
            run(command)
        except subprocess.CalledProcessError:
            output.unlink(missing_ok=True)
            continue
        if probe_duration(output):
            return output
        output.unlink(missing_ok=True)
    return None


def escape_concat_path(path: Path) -> str:
    return str(path.resolve()).replace("'", "'\\''")


def write_concat_list(paths: list[Path], list_path: Path) -> None:
    lines = [f"file '{escape_concat_path(path)}'\n" for path in paths]
    list_path.write_text("".join(lines), encoding="utf-8")


def concat_parts(paths: list[Path], output: Path) -> None:
    ffmpeg = find_tool("ffmpeg")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="recorderlong-rescue-") as temp_dir:
        list_path = Path(temp_dir) / "concat.txt"
        write_concat_list(paths, list_path)
        copy_command = [
            ffmpeg,
            "-nostdin",
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-f",
            "concat",
            "-safe",
            "0",
            "-i",
            str(list_path),
            "-map",
            "0:a:0",
            "-c",
            "copy",
            str(output),
        ]
        try:
            run(copy_command)
            if probe_duration(output):
                return
        except subprocess.CalledProcessError:
            output.unlink(missing_ok=True)

        encode_command = copy_command[:-3] + ["-c:a", "aac", "-b:a", "96k", str(output)]
        run(encode_command)
        if not probe_duration(output):
            raise RuntimeError(f"Could not create valid output: {output}")


def rebuild_session(args: argparse.Namespace) -> int:
    session = choose_session(args.root, args.session)
    parts = find_parts(session)
    if not parts:
        raise RuntimeError(f"No RecorderLong minute parts found in {session}")

    repair_dir = args.repair_dir.expanduser().resolve() if args.repair_dir else session / ".rescued_parts"
    output_dir = args.output_dir.expanduser().resolve() if args.output_dir else session
    stamp = session_stamp(parts, session)

    valid: list[Path] = []
    bad: list[Path] = []
    for part in parts:
        if probe_duration(part):
            valid.append(part)
            continue
        repaired = try_repair(part, repair_dir)
        if repaired is not None:
            valid.append(repaired)
        else:
            bad.append(part)

    print(f"Session: {session}")
    print(f"Found parts: {len(parts)}")
    print(f"Usable parts: {len(valid)}")
    if bad:
        print("Skipped unrecoverable parts:")
        for path in bad:
            print(f"  {path.name}")
    if args.dry_run:
        return 0

    if not valid:
        raise RuntimeError("No usable parts remain after repair attempts.")

    parts_per_hour = max(1, int(args.parts_per_hour))
    total_outputs = (len(valid) + parts_per_hour - 1) // parts_per_hour
    for index in range(total_outputs):
        group = valid[index * parts_per_hour:(index + 1) * parts_per_hour]
        output = output_dir / f"rescued_hour_{stamp}_h{index + 1:02d}.m4a"
        concat_parts(group, output)
        print(output)
    return 0


def main() -> int:
    args = parse_args()
    try:
        if args.list:
            list_sessions(args.root)
            return 0
        return rebuild_session(args)
    except (RuntimeError, subprocess.CalledProcessError) as exc:
        print(f"Failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
