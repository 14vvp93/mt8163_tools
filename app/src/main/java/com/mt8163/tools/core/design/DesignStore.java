package com.mt8163.tools.core.design;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Environment;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DesignStore {
    private static final String PREFS_NAME = "global_design";
    private static final String KEY_VALUES = "values";
    private static final String FILE_NAME = "design_parameters.json";
    private static final int FILE_VERSION = 1;

    private final Context context;
    private final SharedPreferences preferences;
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService fileWriter = Executors.newSingleThreadExecutor();
    private JSONObject values;
    private boolean externalStorageEnabled;

    public DesignStore(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        values = readPreferences();
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
        if (Float.isNaN(value) || Float.isInfinite(value)) return;
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

    public String getExternalFilePath() {
        return new File(Environment.getExternalStorageDirectory(), FILE_NAME).getAbsolutePath();
    }

    public boolean syncExternalFile() {
        if (context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }

        boolean imported = false;
        synchronized (this) {
            File file = getExternalFile();
            if (file.exists()) {
                try {
                    JSONObject document = readJson(file);
                    JSONObject importedValues = document.optJSONObject("values");
                    values = importedValues != null ? importedValues : document;
                    preferences.edit().putString(KEY_VALUES, values.toString()).apply();
                    imported = true;
                } catch (IOException | JSONException | RuntimeException error) {
                    return false;
                }
            } else {
                try {
                    writeExternalFile(createDocument(values));
                } catch (IOException error) {
                    return false;
                }
            }
            externalStorageEnabled = true;
        }

        if (imported) notifyListeners();
        return true;
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

    private JSONObject readPreferences() {
        String json;
        try {
            json = preferences.getString(KEY_VALUES, "{}");
        } catch (ClassCastException error) {
            json = "{}";
        }
        try {
            return new JSONObject(json == null ? "{}" : json);
        } catch (JSONException error) {
            return new JSONObject();
        }
    }

    private void persist() {
        String snapshot = values.toString();
        preferences.edit().putString(KEY_VALUES, snapshot).apply();

        if (externalStorageEnabled) {
            String document = createDocument(snapshot);
            fileWriter.execute(() -> {
                try {
                    writeExternalFile(document);
                } catch (IOException ignored) {
                }
            });
        }
    }

    private String createDocument(String snapshot) {
        try {
            JSONObject document = new JSONObject();
            document.put("version", FILE_VERSION);
            document.put("values", new JSONObject(snapshot));
            return document.toString(2);
        } catch (JSONException error) {
            return "{\"version\":1,\"values\":{}}";
        }
    }

    private String createDocument(JSONObject source) {
        return createDocument(source.toString());
    }

    private JSONObject readJson(File file) throws IOException, JSONException {
        byte[] data = new byte[(int) Math.min(file.length(), 1024 * 1024)];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < data.length) {
                int count = input.read(data, offset, data.length - offset);
                if (count < 0) break;
                offset += count;
            }
            if (offset != data.length) {
                byte[] actual = new byte[offset];
                System.arraycopy(data, 0, actual, 0, offset);
                data = actual;
            }
        }
        return new JSONObject(new String(data, StandardCharsets.UTF_8));
    }

    private File getExternalFile() {
        return new File(Environment.getExternalStorageDirectory(), FILE_NAME);
    }

    private void writeExternalFile(String document) throws IOException {
        File target = getExternalFile();
        File temporary = new File(target.getParentFile(), FILE_NAME + ".tmp");

        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(document.getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
        }

        if (!temporary.renameTo(target)) {
            temporary.delete();
            throw new IOException("Unable to replace " + target.getAbsolutePath());
        }
    }

    private void notifyListeners() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
            }
        }
    }
}
