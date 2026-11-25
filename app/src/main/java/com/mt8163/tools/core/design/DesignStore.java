package com.mt8163.tools.core.design;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.concurrent.CopyOnWriteArrayList;

public final class DesignStore {
    private static final String PREFS_NAME = "global_design";
    private static final String KEY_VALUES = "values";
    private final SharedPreferences preferences;
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private JSONObject values;

    public DesignStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        values = readValues();
    }

    public synchronized int getColor(String key, int fallback) {
        return values.optInt(key, fallback);
    }

    public synchronized float getFloat(String key, float fallback) {
        return (float) values.optDouble(key, fallback);
    }

    public void setColor(String key, int color) {
        update(key, color);
    }

    public void setFloat(String key, float value) {
        update(key, value);
    }

    public void reset() {
        synchronized (this) {
            values = new JSONObject();
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

    private void update(String key, Object value) {
        if (key == null || key.isEmpty()) return;
        synchronized (this) {
            try {
                values.put(key, value);
                persist();
            } catch (JSONException ignored) {
                return;
            }
        }
        notifyListeners();
    }

    private JSONObject readValues() {
        String json = preferences.getString(KEY_VALUES, "{}");
        try {
            return new JSONObject(json == null ? "{}" : json);
        } catch (JSONException error) {
            return new JSONObject();
        }
    }

    private void persist() {
        preferences.edit().putString(KEY_VALUES, values.toString()).apply();
    }

    private void notifyListeners() {
        for (Runnable listener : listeners) listener.run();
    }
}