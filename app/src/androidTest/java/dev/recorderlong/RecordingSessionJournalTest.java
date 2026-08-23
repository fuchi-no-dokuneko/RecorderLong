package dev.recorderlong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
public class RecordingSessionJournalTest {
    private SharedPreferences preferences;

    @Before
    public void clearJournal() {
        Context context = ApplicationProvider.getApplicationContext();
        preferences = context.getSharedPreferences(RecordingService.PREFS, Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
    }

    @After
    public void cleanup() {
        preferences.edit().clear().commit();
    }

    @Test
    public void partialAndCompleteSegmentsRoundTripInPrivatePreferences() {
        assertTrue(RecordingSessionJournal.start(preferences, "session-a", "Download/RecorderLong/session-a"));
        assertTrue(RecordingSessionJournal.saveSegments(
                preferences,
                "session-a",
                "Download/RecorderLong/session-a",
                Arrays.asList(
                        new RecordingSessionJournal.Entry("content://media/1", "", "part001.m4a", "first", true),
                        new RecordingSessionJournal.Entry("", "/tmp/part002.m4a", "part002.m4a", "second", false)
                )
        ));

        RecordingSessionJournal.Snapshot snapshot = RecordingSessionJournal.load(preferences);
        assertTrue(snapshot.active);
        assertTrue(snapshot.readable);
        assertEquals("session-a", snapshot.sessionName);
        assertEquals(2, snapshot.segments.size());
        assertTrue(snapshot.segments.get(0).complete);
        assertFalse(snapshot.segments.get(1).complete);
        assertEquals("/tmp/part002.m4a", snapshot.segments.get(1).filePath);
    }

    @Test
    public void malformedJournalIsSurfacedAndPreserved() {
        assertTrue(preferences.edit().putString(RecordingSessionJournal.KEY, "{broken").commit());

        RecordingSessionJournal.Snapshot snapshot = RecordingSessionJournal.load(preferences);

        assertTrue(snapshot.active);
        assertFalse(snapshot.readable);
        assertEquals("{broken", preferences.getString(RecordingSessionJournal.KEY, ""));
    }

    @Test
    public void completionRemovesTheRecoveryPrompt() {
        assertTrue(RecordingSessionJournal.start(preferences, "session-b", "path-b"));
        assertTrue(RecordingSessionJournal.complete(preferences));

        RecordingSessionJournal.Snapshot snapshot = RecordingSessionJournal.load(preferences);
        assertFalse(snapshot.active);
        assertTrue(snapshot.readable);
        assertTrue(snapshot.segments.isEmpty());
    }
}
