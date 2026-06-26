#!/usr/bin/env python3
"""Offline long-file statistical denoise for RecorderLong hourly M4A files.

This is a separate denoise pipeline from DeepFilterNet. It uses a small
two-component Gaussian mixture over frame energy to find likely background
noise frames, averages their spectra into a noise profile, then applies
chunked Wiener-style suppression. Audio never leaves the machine.
"""

from __future__ import annotations

import argparse
import math
import shutil
import subprocess
import sys
import tempfile
import wave
from pathlib import Path

import numpy as np


EPS = 1e-10


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Denoise one hourly recording using offline GMM/statistical noise profiling."
    )
    parser.add_argument("input", type=Path, help="Input hourly .m4a file.")
    parser.add_argument("-o", "--output", type=Path, help="Output cleaned .m4a path.")
    parser.add_argument("--sample-rate", type=int, default=48000, help="Processing sample rate.")
    parser.add_argument("--frame-ms", type=float, default=32.0, help="STFT frame size in ms.")
    parser.add_argument("--hop-ms", type=float, default=16.0, help="STFT hop size in ms.")
    parser.add_argument(
        "--chunk-seconds",
        type=float,
        default=30.0,
        help="Decode/process chunk size. Lower this to reduce peak memory further.",
    )
    parser.add_argument(
        "--noise-prob",
        type=float,
        default=0.60,
        help="Minimum GMM probability for a frame to contribute strongly to the noise profile.",
    )
    parser.add_argument(
        "--strength",
        type=float,
        default=1.35,
        help="Noise suppression strength. Higher removes more noise but risks artifacts.",
    )
    parser.add_argument(
        "--floor",
        type=float,
        default=0.08,
        help="Minimum spectral gain. Higher keeps more natural background.",
    )
    parser.add_argument(
        "--smooth",
        type=float,
        default=0.70,
        help="Gain smoothing between frames.",
    )
    parser.add_argument(
        "--work-dir",
        type=Path,
        help="Directory for temporary WAV output. Defaults beside output file.",
    )
    parser.add_argument("--keep-temp", action="store_true", help="Keep temporary WAV file.")
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


def pcm_stream(input_path: Path, sample_rate: int, chunk_samples: int):
    ffmpeg = find_tool("ffmpeg")
    process = subprocess.Popen(
        [
            ffmpeg,
            "-nostdin",
            "-hide_banner",
            "-loglevel",
            "error",
            "-i",
            str(input_path),
            "-ac",
            "1",
            "-ar",
            str(sample_rate),
            "-f",
            "f32le",
            "pipe:1",
        ],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    assert process.stdout is not None
    bytes_per_chunk = chunk_samples * 4
    while True:
        data = process.stdout.read(bytes_per_chunk)
        if not data:
            break
        yield np.frombuffer(data, dtype=np.float32).copy()
    stderr = process.stderr.read().decode("utf-8", errors="replace") if process.stderr else ""
    code = process.wait()
    if code != 0:
        raise RuntimeError(stderr.strip() or f"ffmpeg exited with code {code}")


def iter_frames(input_path: Path, sample_rate: int, frame_size: int, hop_size: int, chunk_samples: int):
    carry = np.empty(0, dtype=np.float32)
    for chunk in pcm_stream(input_path, sample_rate, chunk_samples):
        data = np.concatenate((carry, chunk))
        pos = 0
        while pos + frame_size <= data.size:
            yield data[pos:pos + frame_size]
            pos += hop_size
        carry = data[pos:]
    if carry.size:
        padded = np.zeros(frame_size, dtype=np.float32)
        padded[: min(carry.size, frame_size)] = carry[:frame_size]
        yield padded


def frame_log_energy(
    input_path: Path,
    sample_rate: int,
    frame_size: int,
    hop_size: int,
    chunk_samples: int,
) -> np.ndarray:
    energies: list[float] = []
    for frame in iter_frames(input_path, sample_rate, frame_size, hop_size, chunk_samples):
        energies.append(float(np.log(np.mean(frame * frame) + EPS)))
    if not energies:
        raise RuntimeError("No audio frames decoded from input.")
    return np.asarray(energies, dtype=np.float32)


def gmm_noise_probability(log_energy: np.ndarray) -> np.ndarray:
    x = log_energy.astype(np.float64)
    p20, p80 = np.percentile(x, [20, 80])
    means = np.asarray([p20, p80], dtype=np.float64)
    variances = np.asarray([np.var(x) + EPS, np.var(x) + EPS], dtype=np.float64)
    weights = np.asarray([0.5, 0.5], dtype=np.float64)

    for _ in range(40):
        likelihoods = []
        for k in range(2):
            var = max(variances[k], 1e-6)
            coef = 1.0 / math.sqrt(2.0 * math.pi * var)
            likelihoods.append(weights[k] * coef * np.exp(-0.5 * ((x - means[k]) ** 2) / var))
        gamma = np.vstack(likelihoods).T
        gamma /= np.sum(gamma, axis=1, keepdims=True) + EPS
        nk = np.sum(gamma, axis=0) + EPS
        weights = nk / x.size
        means = np.sum(gamma * x[:, None], axis=0) / nk
        variances = np.sum(gamma * ((x[:, None] - means) ** 2), axis=0) / nk

    noise_component = int(np.argmin(means))
    probability = gamma[:, noise_component].astype(np.float32)

    if float(np.mean(probability)) < 0.01:
        threshold = np.percentile(x, 15)
        probability = (x <= threshold).astype(np.float32)
    return probability


def estimate_noise_spectrum(
    input_path: Path,
    probabilities: np.ndarray,
    sample_rate: int,
    frame_size: int,
    hop_size: int,
    chunk_samples: int,
    min_probability: float,
) -> np.ndarray:
    window = np.hanning(frame_size).astype(np.float32)
    weighted_sum = np.zeros(frame_size // 2 + 1, dtype=np.float64)
    weight_total = 0.0
    frame_index = 0
    for frame in iter_frames(input_path, sample_rate, frame_size, hop_size, chunk_samples):
        probability = float(probabilities[min(frame_index, probabilities.size - 1)])
        weight = probability * probability if probability >= min_probability else 0.0
        if weight > 0.0:
            spectrum = np.fft.rfft(frame * window)
            weighted_sum += weight * (np.abs(spectrum) ** 2)
            weight_total += weight
        frame_index += 1

    if weight_total <= EPS:
        quiet_count = max(1, int(probabilities.size * 0.15))
        quiet_indices = set(np.argsort(probabilities)[-quiet_count:].tolist())
        frame_index = 0
        for frame in iter_frames(input_path, sample_rate, frame_size, hop_size, chunk_samples):
            if frame_index in quiet_indices:
                spectrum = np.fft.rfft(frame * window)
                weighted_sum += np.abs(spectrum) ** 2
                weight_total += 1.0
            frame_index += 1

    if weight_total <= EPS:
        raise RuntimeError("Could not estimate a noise profile.")
    return (weighted_sum / weight_total).astype(np.float32)


def write_pcm16(wav_file: wave.Wave_write, samples: np.ndarray) -> None:
    clipped = np.clip(samples, -1.0, 1.0)
    pcm = (clipped * 32767.0).astype("<i2")
    wav_file.writeframes(pcm.tobytes())


def process_to_wav(
    input_path: Path,
    wav_path: Path,
    noise_power: np.ndarray,
    sample_rate: int,
    frame_size: int,
    hop_size: int,
    chunk_samples: int,
    strength: float,
    floor: float,
    smooth: float,
) -> None:
    window = np.hanning(frame_size).astype(np.float32)
    audio_ola = np.zeros(frame_size, dtype=np.float32)
    norm_ola = np.zeros(frame_size, dtype=np.float32)
    last_gain = np.ones_like(noise_power, dtype=np.float32)

    with wave.open(str(wav_path), "wb") as wav_file:
        wav_file.setnchannels(1)
        wav_file.setsampwidth(2)
        wav_file.setframerate(sample_rate)

        for frame in iter_frames(input_path, sample_rate, frame_size, hop_size, chunk_samples):
            spectrum = np.fft.rfft(frame * window)
            power = (np.abs(spectrum) ** 2).astype(np.float32)
            raw_gain = power / (power + strength * noise_power + EPS)
            gain = np.maximum(floor, raw_gain).astype(np.float32)
            gain = smooth * last_gain + (1.0 - smooth) * gain
            last_gain = gain

            cleaned = np.fft.irfft(spectrum * gain, n=frame_size).astype(np.float32)
            audio_ola += cleaned * window
            norm_ola += window * window

            ready = audio_ola[:hop_size] / np.maximum(norm_ola[:hop_size], 1e-6)
            write_pcm16(wav_file, ready)

            audio_ola[:-hop_size] = audio_ola[hop_size:]
            audio_ola[-hop_size:] = 0.0
            norm_ola[:-hop_size] = norm_ola[hop_size:]
            norm_ola[-hop_size:] = 0.0

        tail = audio_ola / np.maximum(norm_ola, 1e-6)
        write_pcm16(wav_file, tail)


def encode_m4a(wav_path: Path, output_path: Path) -> None:
    ffmpeg = find_tool("ffmpeg")
    output_path.parent.mkdir(parents=True, exist_ok=True)
    run([
        ffmpeg,
        "-nostdin",
        "-hide_banner",
        "-loglevel",
        "error",
        "-y",
        "-i",
        str(wav_path),
        "-c:a",
        "aac",
        "-b:a",
        "96k",
        str(output_path),
    ])


def denoise(args: argparse.Namespace) -> Path:
    input_path = args.input.expanduser().resolve()
    if not input_path.exists():
        raise RuntimeError(f"Input file not found: {input_path}")

    output_path = args.output
    if output_path is None:
        output_path = input_path.with_name(input_path.stem + "_gmmdenoise.m4a")
    output_path = output_path.expanduser().resolve()

    sample_rate = int(args.sample_rate)
    frame_size = max(256, int(sample_rate * args.frame_ms / 1000.0))
    if frame_size % 2:
        frame_size += 1
    hop_size = max(1, int(sample_rate * args.hop_ms / 1000.0))
    chunk_samples = max(frame_size * 2, int(sample_rate * args.chunk_seconds))

    work_dir = args.work_dir.expanduser().resolve() if args.work_dir else output_path.parent
    work_dir.mkdir(parents=True, exist_ok=True)
    temp_path = Path(tempfile.mkdtemp(prefix="recorderlong-gmm-", dir=work_dir))
    try:
        print("Pass 1/3: estimating frame energy", file=sys.stderr)
        log_energy = frame_log_energy(input_path, sample_rate, frame_size, hop_size, chunk_samples)
        probabilities = gmm_noise_probability(log_energy)

        print("Pass 2/3: estimating noise spectrum", file=sys.stderr)
        noise_power = estimate_noise_spectrum(
            input_path,
            probabilities,
            sample_rate,
            frame_size,
            hop_size,
            chunk_samples,
            float(args.noise_prob),
        )

        print("Pass 3/3: writing cleaned audio", file=sys.stderr)
        wav_path = temp_path / "gmmdenoise.wav"
        process_to_wav(
            input_path,
            wav_path,
            noise_power,
            sample_rate,
            frame_size,
            hop_size,
            chunk_samples,
            float(args.strength),
            float(args.floor),
            float(args.smooth),
        )
        encode_m4a(wav_path, output_path)
    finally:
        if args.keep_temp:
            print(f"Kept temp files in {temp_path}", file=sys.stderr)
        else:
            shutil.rmtree(temp_path, ignore_errors=True)
    return output_path


def main() -> int:
    args = parse_args()
    try:
        print(denoise(args))
    except (RuntimeError, subprocess.CalledProcessError) as exc:
        print(f"Failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
