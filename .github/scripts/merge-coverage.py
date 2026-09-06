#!/usr/bin/env python3

import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET


def jacoco_lines(paths):
    merged = {}
    for path in paths:
        root = ET.parse(path).getroot()
        for package in root.findall("package"):
            package_name = package.get("name", "")
            for source in package.findall("sourcefile"):
                filename = f"app/src/main/java/{package_name}/{source.get('name')}"
                for line in source.findall("line"):
                    key = (filename, int(line.get("nr")))
                    merged[key] = merged.get(key, False) or int(line.get("ci", "0")) > 0
    return merged


def python_lines(path):
    merged = {}
    root = ET.parse(path).getroot()
    for class_node in root.findall(".//class"):
        filename = class_node.get("filename", "")
        for line in class_node.findall("./lines/line"):
            merged[(filename, int(line.get("number")))] = int(line.get("hits", "0")) > 0
    return merged


parser = argparse.ArgumentParser()
parser.add_argument("--jacoco", action="append", required=True, type=Path)
parser.add_argument("--python", required=True, type=Path)
parser.add_argument("--device-report", required=True, type=Path)
parser.add_argument("--media-evidence", required=True, type=Path)
parser.add_argument("--security-evidence", required=True, type=Path)
parser.add_argument("--threshold", type=float, default=95.0)
parser.add_argument("--output", type=Path, default=Path("build/reports/merged-coverage.json"))
args = parser.parse_args()

required = [*args.jacoco, args.python, args.device_report, args.media_evidence, args.security_evidence]
missing = [str(path) for path in required if not path.is_file() or path.stat().st_size == 0]
if missing:
    raise SystemExit("Missing required evidence: " + ", ".join(missing))

android = jacoco_lines(args.jacoco)
python = python_lines(args.python)
all_lines = {**android, **python}
covered = sum(all_lines.values())
total = len(all_lines)
percent = covered / total * 100 if total else 0.0
media = json.loads(args.media_evidence.read_text(encoding="utf-8"))
security = json.loads(args.security_evidence.read_text(encoding="utf-8"))
failures = []
if percent < args.threshold:
    failures.append(f"Merged maintained-source coverage {percent:.2f}% is below {args.threshold:.2f}%")
if media.get("tests", 0) < 1 or media.get("failures") or media.get("errors") or not media.get("ffmpeg") or not media.get("ffprobe"):
    failures.append("Real media evidence is incomplete")
if not security.get("passed"):
    failures.append("Security workflow evidence is incomplete")

report = {
    "schemaVersion": "recorderlong-merged-coverage-1.0.0",
    "threshold": args.threshold,
    "coveredLines": covered,
    "totalLines": total,
    "percent": percent,
    "android": {"coveredLines": sum(android.values()), "totalLines": len(android)},
    "python": {"coveredLines": sum(python.values()), "totalLines": len(python)},
    "deviceReport": str(args.device_report),
    "mediaEvidence": str(args.media_evidence),
    "securityEvidence": str(args.security_evidence),
    "passed": not failures,
    "failures": failures,
}
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
print(f"Merged coverage: {covered}/{total} ({percent:.2f}%)")
for failure in failures:
    print(f"ERROR: {failure}")
if failures:
    raise SystemExit(1)
