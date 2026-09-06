#!/usr/bin/env python3

import ast
import json
from pathlib import Path
import shutil
import subprocess
import sys
import trace
import unittest
import xml.etree.ElementTree as ET


ROOT = Path.cwd()
SOURCE_FILES = sorted((ROOT / "tools").glob("*.py"))
REPORT_DIR = ROOT / "build" / "reports" / "python"


def executable_lines(path):
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    return {node.lineno for node in ast.walk(tree) if isinstance(node, ast.stmt) and hasattr(node, "lineno")}


REPORT_DIR.mkdir(parents=True, exist_ok=True)
suite = unittest.defaultTestLoader.discover(str(ROOT / "tools" / "tests"), pattern="test_*.py")
runner = unittest.TextTestRunner(verbosity=2)
namespace = {"runner": runner, "suite": suite, "result": None}
tracer = trace.Trace(count=True, trace=False, ignoredirs=[sys.prefix, sys.base_prefix])
tracer.runctx("result = runner.run(suite)", namespace, namespace)
result = namespace["result"]
counts = tracer.results().counts

coverage_root = ET.Element(
    "coverage",
    {"version": "stdlib-trace", "timestamp": "0", "lines-valid": "0", "lines-covered": "0"},
)
sources = ET.SubElement(coverage_root, "sources")
ET.SubElement(sources, "source").text = str(ROOT)
packages = ET.SubElement(coverage_root, "packages")
package = ET.SubElement(packages, "package", {"name": "tools", "line-rate": "0", "branch-rate": "0"})
classes = ET.SubElement(package, "classes")
total = 0
covered = 0

for source in SOURCE_FILES:
    relative = source.relative_to(ROOT).as_posix()
    lines = executable_lines(source)
    hits = {line: counts.get((str(source.resolve()), line), 0) for line in lines}
    total += len(lines)
    covered += sum(value > 0 for value in hits.values())
    class_node = ET.SubElement(
        classes,
        "class",
        {"name": source.stem, "filename": relative, "line-rate": str(sum(value > 0 for value in hits.values()) / len(lines) if lines else 1)},
    )
    class_lines = ET.SubElement(class_node, "lines")
    for line in sorted(lines):
        ET.SubElement(class_lines, "line", {"number": str(line), "hits": str(hits[line])})

rate = covered / total if total else 1.0
coverage_root.set("lines-valid", str(total))
coverage_root.set("lines-covered", str(covered))
coverage_root.set("line-rate", str(rate))
package.set("line-rate", str(rate))
ET.ElementTree(coverage_root).write(REPORT_DIR / "coverage.xml", encoding="utf-8", xml_declaration=True)

tools = {}
for name in ("ffmpeg", "ffprobe"):
    executable = shutil.which(name)
    if executable:
        version = subprocess.run([executable, "-version"], check=True, text=True, capture_output=True).stdout.splitlines()[0]
        tools[name] = {"path": executable, "version": version}

evidence = {
    "schemaVersion": "recorderlong-media-evidence-1.0.0",
    "tests": result.testsRun,
    "failures": len(result.failures),
    "errors": len(result.errors),
    "ffmpeg": tools.get("ffmpeg"),
    "ffprobe": tools.get("ffprobe"),
    "coverage": {"covered": covered, "total": total, "percent": rate * 100},
}
(REPORT_DIR / "media-evidence.json").write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
print(f"Python coverage: {covered}/{total} ({rate * 100:.2f}%)")
if not result.wasSuccessful() or not tools.get("ffmpeg") or not tools.get("ffprobe"):
    raise SystemExit(1)
