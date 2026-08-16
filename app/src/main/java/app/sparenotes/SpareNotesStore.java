package app.sparenotes;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Collections;
import java.util.Set;

final class SpareNotesStore {
    private static final String PREFS = "sparenotes";
    private static final String SOURCES = "sources";
    private static final String FINGERPRINTS = "fingerprints_sparenotes";
    private static final String FINGERPRINT_SCHEMA = "v2\n";
    static final String LAST_STATUS = "last_status";
    static final String LAST_RUN = "last_run";
    static final String BACKUP_RUNNING = "backup_running";

    private SpareNotesStore() {}

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static Set<String> sources(Context context) {
        return new HashSet<>(prefs(context).getStringSet(SOURCES, Collections.emptySet()));
    }

    static void addSource(Context context, String uri) {
        Set<String> sources = sources(context);
        sources.add(uri);
        prefs(context).edit().putStringSet(SOURCES, sources).apply();
    }

    static void removeSource(Context context, String uri) {
        Set<String> sources = sources(context);
        sources.remove(uri);
        prefs(context).edit().putStringSet(SOURCES, sources).apply();
    }

    static JSONObject fingerprints(Context context) {
        try {
            JSONObject values = new JSONObject(prefs(context).getString(FINGERPRINTS, "{}"));
            Iterator<String> keys = values.keys();
            while (keys.hasNext()) {
                if (!keys.next().startsWith(FINGERPRINT_SCHEMA)) keys.remove();
            }
            return values;
        } catch (JSONException ignored) {
            return new JSONObject();
        }
    }

    @SuppressLint("ApplySharedPref") // Upload checkpoint must be durable before staging is deleted.
    static void fingerprints(Context context, JSONObject fingerprints) {
        // ponytail: SharedPreferences is enough for a few thousand files; move to Room if scans become slow.
        prefs(context).edit().putString(FINGERPRINTS, fingerprints.toString()).commit();
    }

    static void forgetSourceFingerprints(Context context, String sourceUri) {
        JSONObject values = fingerprints(context);
        Iterator<String> keys = values.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key.startsWith(sourceUri + "\n")
                    || key.startsWith(FINGERPRINT_SCHEMA + sourceUri + "\n")) keys.remove();
        }
        fingerprints(context, values);
    }

    static String fingerprintKey(String sourceUri, String path) {
        return FINGERPRINT_SCHEMA + sourceUri + "\n" + path;
    }
}
