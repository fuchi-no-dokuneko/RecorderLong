import argparse
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest


MODULE_PATH = Path(__file__).resolve().parents[1] / "rescue_session_offline.py"
SPEC = importlib.util.spec_from_file_location("rescue_session_offline", MODULE_PATH)
rescue = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(rescue)


class RescueSessionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="recorderlong-test-")
        self.root = Path(self.temporary.name)
        self.session = self.root / "session-20260823_120000"
        self.session.mkdir()

    def tearDown(self):
        self.temporary.cleanup()

    def make_audio(self, name, frequency=440):
        target = self.session / name
        subprocess.run([
            "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", f"sine=frequency={frequency}:duration=0.15",
            "-c:a", "aac", str(target)
        ], check=True, timeout=10)
        return target

    def test_real_parts_are_sorted_probed_and_concatenated(self):
        second = self.make_audio("rec_20260823_120000_part002.m4a", 550)
        first = self.make_audio("rec_20260823_120000_part001.m4a", 440)

        self.assertEqual([first, second], rescue.find_parts(self.session))
        self.assertGreater(rescue.probe_duration(first), 0)
        output = self.session / "hour.m4a"
        rescue.concat_parts([first, second], output)
        self.assertGreater(rescue.probe_duration(output), 0)

    def test_rebuild_keeps_bad_input_and_creates_valid_output(self):
        good = self.make_audio("rec_20260823_120000_part001.m4a")
        bad = self.session / "rec_20260823_120000_part002.m4a"
        bad.write_bytes(b"not-media")
        arguments = argparse.Namespace(
            root=self.root,
            list=False,
            session=self.session.name,
            output_dir=None,
            parts_per_hour=60,
            repair_dir=None,
            dry_run=False,
        )

        self.assertEqual(0, rescue.rebuild_session(arguments))
        outputs = list(self.session.glob("rescued_hour_*.m4a"))
        self.assertEqual(1, len(outputs))
        self.assertGreater(rescue.probe_duration(outputs[0]), 0)
        self.assertTrue(good.exists())
        self.assertTrue(bad.exists())

    def test_session_selection_and_errors_are_explicit(self):
        part = self.make_audio("rec_stamp_part003.m4a")
        self.assertTrue(rescue.is_session_dir(self.session))
        self.assertEqual(self.session, rescue.choose_session(self.root, "1"))
        self.assertEqual((3, part.name), rescue.part_sort_key(part))
        with self.assertRaisesRegex(RuntimeError, "out of range"):
            rescue.choose_session(self.root, "9")
        with self.assertRaisesRegex(RuntimeError, "Session not found"):
            rescue.choose_session(self.root, "missing")


if __name__ == "__main__":
    unittest.main()
