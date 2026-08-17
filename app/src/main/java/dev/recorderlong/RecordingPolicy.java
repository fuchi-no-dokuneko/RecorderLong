package dev.recorderlong;

final class RecordingPolicy {
    private RecordingPolicy() {
    }

    static int clampAutoStopMinutes(int minutes) {
        return Math.max(
                RecordingService.MIN_AUTO_STOP_MINUTES,
                Math.min(RecordingService.MAX_AUTO_STOP_MINUTES, minutes)
        );
    }

    static long minutesToMillis(int minutes) {
        return minutes * 60L * 1000L;
    }
}
