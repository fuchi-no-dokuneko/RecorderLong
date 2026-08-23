# Release evidence gates

RecorderLong publishes only after the release workflow receives five independent results: JVM JaCoCo XML, connected-device JaCoCo XML, Python coverage XML from real ffmpeg rescue tests, immutable security-workflow evidence, and verified Android signing identity.

`.github/scripts/merge-coverage.py` unions duplicate Android source lines across unit and device reports, adds maintained Python source lines, and writes `build/reports/merged-coverage.json`. Missing evidence or overall coverage below 95 percent exits nonzero. SonarQube imports the two JaCoCo reports and the Python Cobertura report directly.

The report schema is versioned as `recorderlong-merged-coverage-1.0.0`. Reports contain paths and counts only; there is no persistent application-data migration.
