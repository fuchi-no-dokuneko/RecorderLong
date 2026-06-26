#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_DIR="${RECORDERLONG_AUDIO_ENV:-"$ROOT_DIR/.audio-env"}"

if ! command -v mamba >/dev/null 2>&1; then
  echo "mamba was not found on PATH. Source the normal VPS shell env first." >&2
  exit 1
fi

if [ ! -x "$ENV_DIR/bin/python" ]; then
  mamba create -y -p "$ENV_DIR" -c conda-forge python=3.11 pip ffmpeg numpy
else
  mamba install -y -p "$ENV_DIR" -c conda-forge ffmpeg numpy
fi

"$ENV_DIR/bin/python" -m pip install --upgrade pip
"$ENV_DIR/bin/python" -m pip install --upgrade deepfilternet

cat <<EOF
Offline audio environment ready:
  $ENV_DIR

Use:
  "$ENV_DIR/bin/python" "$ROOT_DIR/tools/denoise_hour_offline.py" INPUT_HOUR.m4a
  "$ENV_DIR/bin/python" "$ROOT_DIR/tools/denoise_hour_gmm_offline.py" INPUT_HOUR.m4a
  "$ENV_DIR/bin/python" "$ROOT_DIR/tools/rescue_session_offline.py" /path/to/RecorderLong --list

Audio files stay local. The setup downloads tools/models only; it does not upload audio.
EOF
