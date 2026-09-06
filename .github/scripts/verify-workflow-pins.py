#!/usr/bin/env python3

import json
from pathlib import Path
import re


ROOT = Path.cwd()
workflows = sorted((ROOT / ".github" / "workflows").glob("*.yml"))
mutable = []
for workflow in workflows:
    for number, line in enumerate(workflow.read_text(encoding="utf-8").splitlines(), start=1):
        match = re.search(r"\buses:\s*([^\s#]+)", line)
        if not match or match.group(1).startswith("./"):
            continue
        reference = match.group(1).rsplit("@", 1)[-1]
        if not re.fullmatch(r"[0-9a-f]{40}", reference):
            mutable.append(f"{workflow.relative_to(ROOT)}:{number}:{match.group(1)}")

required = [ROOT / ".github/workflows/codeql.yml", ROOT / ".github/workflows/dependency-review.yml"]
missing = [str(path.relative_to(ROOT)) for path in required if not path.is_file()]
report = {
    "schemaVersion": "recorderlong-security-evidence-1.0.0",
    "workflows": [str(path.relative_to(ROOT)) for path in workflows],
    "mutableActionReferences": mutable,
    "missingSecurityWorkflows": missing,
    "passed": not mutable and not missing,
}
output = ROOT / "build/reports/security-evidence.json"
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
if mutable or missing:
    for item in mutable + missing:
        print(f"ERROR: {item}")
    raise SystemExit(1)
print(f"Verified {len(workflows)} workflows use immutable action references.")
