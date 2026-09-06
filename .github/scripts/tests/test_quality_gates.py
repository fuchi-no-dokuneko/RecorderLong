import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[3]
MERGE = ROOT / ".github" / "scripts" / "merge-coverage.py"


class QualityGateTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="recorderlong-gate-")
        self.root = Path(self.temporary.name)
        self.jacoco = self.root / "jacoco.xml"
        self.python = self.root / "python.xml"
        self.media = self.root / "media.json"
        self.security = self.root / "security.json"
        self.output = self.root / "merged.json"
        self.jacoco.write_text(
            '<report><package name="dev/recorderlong"><sourcefile name="A.java">'
            '<line nr="1" mi="0" ci="1"/></sourcefile></package></report>',
            encoding="utf-8",
        )
        self.python.write_text(
            '<coverage><packages><package><classes><class filename="tools/a.py"><lines>'
            '<line number="1" hits="1"/></lines></class></classes></package></packages></coverage>',
            encoding="utf-8",
        )
        self.media.write_text(
            json.dumps({"tests": 1, "failures": 0, "errors": 0, "ffmpeg": {"version": "fixture"}, "ffprobe": {"version": "fixture"}}),
            encoding="utf-8",
        )
        self.security.write_text(json.dumps({"passed": True}), encoding="utf-8")

    def tearDown(self):
        self.temporary.cleanup()

    def run_gate(self, device=None):
        return subprocess.run(
            [
                sys.executable,
                str(MERGE),
                "--jacoco",
                str(self.jacoco),
                "--python",
                str(self.python),
                "--device-report",
                str(device or self.jacoco),
                "--media-evidence",
                str(self.media),
                "--security-evidence",
                str(self.security),
                "--threshold",
                "95",
                "--output",
                str(self.output),
            ],
            text=True,
            capture_output=True,
            check=False,
        )

    def test_complete_evidence_passes(self):
        result = self.run_gate()
        self.assertEqual(0, result.returncode, result.stderr + result.stdout)
        self.assertTrue(json.loads(self.output.read_text(encoding="utf-8"))["passed"])

    def test_coverage_below_threshold_blocks(self):
        self.python.write_text(
            '<coverage><packages><package><classes><class filename="tools/a.py"><lines>'
            '<line number="1" hits="0"/></lines></class></classes></package></packages></coverage>',
            encoding="utf-8",
        )
        result = self.run_gate()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("below 95.00%", result.stdout)

    def test_missing_device_evidence_blocks(self):
        result = self.run_gate(self.root / "missing-device.xml")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Missing required evidence", result.stderr)


if __name__ == "__main__":
    unittest.main()
