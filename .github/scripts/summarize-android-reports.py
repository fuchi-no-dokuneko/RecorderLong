#!/usr/bin/env python3

import os
from pathlib import Path
import xml.etree.ElementTree as ET


ROOT = Path.cwd()


def test_suite_totals(root):
    suites = [root] if root.tag == "testsuite" else list(root.findall(".//testsuite"))
    return {
        "tests": sum(int(suite.get("tests", 0)) for suite in suites),
        "failures": sum(int(suite.get("failures", 0)) for suite in suites),
        "errors": sum(int(suite.get("errors", 0)) for suite in suites),
        "skipped": sum(int(suite.get("skipped", 0)) for suite in suites),
    }


def test_reports():
    patterns = [
        "app/build/test-results/**/TEST-*.xml",
        "app/build/outputs/androidTest-results/**/TEST-*.xml",
    ]
    reports = []
    seen = set()
    for pattern in patterns:
        for report in sorted(ROOT.glob(pattern)):
            if report in seen:
                continue
            seen.add(report)
            try:
                reports.append((report.relative_to(ROOT), test_suite_totals(ET.parse(report).getroot())))
            except (ET.ParseError, OSError, ValueError):
                continue
    return reports


def coverage_reports():
    reports = []
    for report in sorted(ROOT.glob("app/build/reports/coverage/**/report.xml")):
        try:
            root = ET.parse(report).getroot()
        except (ET.ParseError, OSError):
            continue
        counter = next((item for item in root.findall("counter") if item.get("type") == "LINE"), None)
        if counter is None:
            continue
        missed = int(counter.get("missed", 0))
        covered = int(counter.get("covered", 0))
        total = missed + covered
        reports.append((report.relative_to(ROOT), covered, total, (covered / total * 100) if total else 0.0))
    return reports


def lint_totals():
    report = ROOT / "app/build/reports/lint-results-debug.xml"
    if not report.exists():
        return None
    try:
        issues = ET.parse(report).getroot().findall("issue")
    except (ET.ParseError, OSError):
        return None
    errors = sum(issue.get("severity") in {"Error", "Fatal"} for issue in issues)
    warnings = sum(issue.get("severity") == "Warning" for issue in issues)
    return len(issues), errors, warnings


tests = test_reports()
coverage = coverage_reports()
lint = lint_totals()
lines = ["## Android test and quality report", ""]

if tests:
    lines.extend(["### Test results", "", "| Report | Passed | Failed | Skipped | Total |", "| --- | ---: | ---: | ---: | ---: |"])
    for report, totals in tests:
        failed = totals["failures"] + totals["errors"]
        passed = totals["tests"] - failed - totals["skipped"]
        lines.append(f"| `{report}` | {passed} | {failed} | {totals['skipped']} | {totals['tests']} |")
    lines.append("")
else:
    lines.extend(["No JUnit XML test report was produced.", ""])

if coverage:
    lines.extend(["### JaCoCo coverage", "", "| Report | Covered lines | Coverage |", "| --- | ---: | ---: |"])
    for report, covered, total, percent in coverage:
        lines.append(f"| `{report}` | {covered}/{total} | {percent:.1f}% |")
    lines.append("")
else:
    lines.extend(["No JaCoCo XML coverage report was produced.", ""])

if lint:
    total, errors, warnings = lint
    lines.extend(["### Android lint", "", f"- Issues: **{total}**", f"- Errors: **{errors}**", f"- Warnings: **{warnings}**", ""])

summary = "\n".join(lines)
output = ROOT / "build/reports/ci-summary.md"
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(summary, encoding="utf-8")
if step_summary := os.environ.get("GITHUB_STEP_SUMMARY"):
    with open(step_summary, "a", encoding="utf-8") as stream:
        stream.write(summary)
print(summary)
