package com.mt8163.tools.core.design;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.CopyOnWriteArrayList;

public final class DesignStore {
    private static final String PREFS_NAME = "runtime_design";
    private static final String KEY_STYLES = "styles";

    private final SharedPreferences preferences;
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private JSONObject styles;

    public DesignStore(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        styles = readStyles();
    }

    public synchronized JSONObject getStyle(String viewName) {
        JSONObject style = styles.optJSONObject(viewName);
        if (style == null) return new JSONObject();
        try {
            return new JSONObject(style.toString());
        } catch (JSONException error) {
            return new JSONObject();
        }
    }

    public void updateStyle(String viewName, JSONObject values) {
        if (viewName == null || viewName.isEmpty() || values == null) return;
        synchronized (this) {
            JSONObject style = styles.optJSONObject(viewName);
            if (style == null) style = new JSONObject();
            java.util.Iterator<String> keys = values.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                try {
                    style.put(key, values.get(key));
                } catch (JSONException ignored) {
                }
            }
            try {
                styles.put(viewName, style);
            } catch (JSONException ignored) {
                return;
            }
            persist();
        }
        notifyListeners();
    }

    public void resetView(String viewName) {
        if (viewName == null || viewName.isEmpty()) return;
        synchronized (this) {
            styles.remove(viewName);
            persist();
        }
        notifyListeners();
    }

    public void resetAll() {
        synchronized (this) {
            styles = new JSONObject();
            persist();
        }
        notifyListeners();
    }

    public void addListener(Runnable listener) {
        if (listener != null) listeners.addIfAbsent(listener);
    }

    public void removeListener(Runnable listener) {
        if (listener != null) listeners.remove(listener);
    }

    private JSONObject readStyles() {
        String json = preferences.getString(KEY_STYLES, "{}");
        try {
            JSONObject result = new JSONObject(json == null ? "{}" : json);
            return result;
        } catch (JSONException error) {
            return new JSONObject();
        }
    }

    private void persist() {
        preferences.edit().putString(KEY_STYLES, styles.toString()).apply();
    }

    private void notifyListeners() {
        for (Runnable listener : listeners) listener.run();
    }
}
