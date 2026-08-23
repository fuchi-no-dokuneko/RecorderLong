package dev.recorderlong;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class RecordingSessionJournal {
    private static final String KEY = "recording_session_journal_v1";

    static final class Entry {
        final String uri;
        final String filePath;
        final String name;
        final String displayPath;

        Entry(String uri, String filePath, String name, String displayPath) {
            this.uri = uri;
            this.filePath = filePath;
            this.name = name;
            this.displayPath = displayPath;
        }
    }

    static final class Snapshot {
        final boolean active;
        final boolean readable;
        final String sessionName;
        final String sessionPath;
        final List<Entry> segments;

        Snapshot(boolean active, boolean readable, String sessionName, String sessionPath, List<Entry> segments) {
            this.active = active;
            this.readable = readable;
            this.sessionName = sessionName;
            this.sessionPath = sessionPath;
            this.segments = Collections.unmodifiableList(new ArrayList<>(segments));
        }
    }

    private RecordingSessionJournal() {
    }

    static boolean start(SharedPreferences preferences, String sessionName, String sessionPath) {
        return save(preferences, new Snapshot(true, true, sessionName, sessionPath, Collections.emptyList()));
    }

    static boolean saveSegments(
            SharedPreferences preferences,
            String sessionName,
            String sessionPath,
            List<Entry> entries
    ) {
        return save(preferences, new Snapshot(true, true, sessionName, sessionPath, entries));
    }

    static Snapshot load(SharedPreferences preferences) {
        String raw = preferences.getString(KEY, "");
        if (raw == null || raw.isEmpty()) {
            return new Snapshot(false, true, "", "", Collections.emptyList());
        }
        try {
            JSONObject root = new JSONObject(raw);
            if (root.optInt("schemaVersion", 0) != 1) {
                return new Snapshot(true, false, "", "", Collections.emptyList());
            }
            List<Entry> entries = new ArrayList<>();
            JSONArray segments = root.optJSONArray("segments");
            if (segments != null) {
                for (int index = 0; index < segments.length(); index++) {
                    JSONObject item = segments.getJSONObject(index);
                    entries.add(new Entry(
                            item.optString("uri", ""),
                            item.optString("filePath", ""),
                            item.optString("name", ""),
                            item.optString("displayPath", "")
                    ));
                }
            }
            return new Snapshot(
                    root.optBoolean("active", false),
                    true,
                    root.optString("sessionName", ""),
                    root.optString("sessionPath", ""),
                    entries
            );
        } catch (JSONException error) {
            return new Snapshot(true, false, "", "", Collections.emptyList());
        }
    }

    static boolean complete(SharedPreferences preferences) {
        return preferences.edit().remove(KEY).commit();
    }

    private static boolean save(SharedPreferences preferences, Snapshot snapshot) {
        try {
            JSONObject root = new JSONObject();
            root.put("schemaVersion", 1);
            root.put("active", snapshot.active);
            root.put("sessionName", snapshot.sessionName);
            root.put("sessionPath", snapshot.sessionPath);
            JSONArray segments = new JSONArray();
            for (Entry entry : snapshot.segments) {
                JSONObject item = new JSONObject();
                item.put("uri", entry.uri);
                item.put("filePath", entry.filePath);
                item.put("name", entry.name);
                item.put("displayPath", entry.displayPath);
                segments.put(item);
            }
            root.put("segments", segments);
            return preferences.edit().putString(KEY, root.toString()).commit();
        } catch (JSONException error) {
            return false;
        }
    }
}
