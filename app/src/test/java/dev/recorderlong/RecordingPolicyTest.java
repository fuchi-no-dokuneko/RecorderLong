package dev.recorderlong;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class RecordingPolicyTest {
    @Test
    public void autoStopMinutesStayInsideSupportedRange() {
        assertEquals(RecordingService.MIN_AUTO_STOP_MINUTES, RecordingPolicy.clampAutoStopMinutes(-1));
        assertEquals(120, RecordingPolicy.clampAutoStopMinutes(120));
        assertEquals(RecordingService.MAX_AUTO_STOP_MINUTES, RecordingPolicy.clampAutoStopMinutes(2000));
    }

    @Test
    public void minutesConvertWithoutIntegerOverflow() {
        assertEquals(86_400_000L, RecordingPolicy.minutesToMillis(1440));
    }
}
