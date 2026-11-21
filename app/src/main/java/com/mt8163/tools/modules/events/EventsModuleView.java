package com.mt8163.tools.modules.events;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.mt8163.tools.core.ModuleContract;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class EventsModuleView {
    private static final int EXPORT_REQUEST = 8163;
    private static final int PAGE_SIZE = 100;
    private static final SimpleDateFormat DATE =
            new SimpleDateFormat("dd.MM.yy HH:mm:ss", Locale.getDefault());

    private static EventManager manager;
    private static Activity activity;
    private static LinearLayout entries;
    private static ScrollView scroll;
    private static TextView count;
    private static Button filterButton;
    private static List<String> availableModules = new ArrayList<>();
    private static final Set<String> selectedModules = new HashSet<>();
    private static boolean allModules = true;
    private static boolean autoScroll = true;
    private static boolean loadingOlder;
    private static boolean hasMore;
    private static long oldestLoadedId = Long.MAX_VALUE;
    private static String exportMode = "all";

    private EventsModuleView() {
    }

    public static View create(Activity owner, List<ModuleContract> modules) {
        destroy();
        activity = owner;
        manager = EventManager.get(owner);
        availableModules = new ArrayList<>();
        for (ModuleContract module : modules) availableModules.add(module.getName());
        selectedModules.clear();
        allModules = true;
        autoScroll = true;
        loadingOlder = false;
        hasMore = false;
        oldestLoadedId = Long.MAX_VALUE;

        LinearLayout root = new LinearLayout(owner);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 20, 24, 12);

        LinearLayout actions = new LinearLayout(owner);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        addButton(owner, actions, "Оновити", v -> refresh());
        addButton(owner, actions, "Копіювати", v -> copy());
        addButton(owner, actions, "Очистити", v -> clear());
        addButton(owner, actions, "Експорт", v -> export());
        root.addView(actions, new LinearLayout.LayoutParams(-1, 52));

        filterButton = new Button(owner);
        filterButton.setText("Усі модулі  ▾");
        filterButton.setOnClickListener(v -> chooseFilter());
        root.addView(filterButton, new LinearLayout.LayoutParams(-1, 48));

        count = new TextView(owner);
        count.setTextColor(Color.LTGRAY);
        count.setPadding(4, 10, 4, 10);
        root.addView(count);

        scroll = new ScrollView(owner);
        scroll.setFillViewport(true);
        entries = new LinearLayout(owner);
        entries.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(entries);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        scroll.setOnScrollChangeListener((View view, int x, int y, int oldX, int oldY) -> {
            boolean wasAtBottom = autoScroll;
            autoScroll = y + scroll.getHeight() >= entries.getHeight() - 24;
            if (y < oldY && y <= 8 && hasMore && !loadingOlder) loadOlder();
            else if (!wasAtBottom && autoScroll) refresh();
        });

        manager.setListener(() -> {
            if (manager != null && manager.isEnabled() && autoScroll) refresh();
        });
        refresh();
        return root;
    }

    private static void addButton(Context context, LinearLayout row,
                                  String label, View.OnClickListener listener) {
        Button button = new Button(context);
        button.setText(label);
        button.setTextSize(10);
        button.setAllCaps(false);
        row.addView(button, new LinearLayout.LayoutParams(0, -1, 1));
        button.setOnClickListener(listener);
    }

    private static List<String> filter() {
        return allModules ? null : new ArrayList<>(selectedModules);
    }

    private static void chooseFilter() {
        if (activity == null) return;
        String[] labels = new String[availableModules.size() + 1];
        boolean[] checked = new boolean[labels.length];
        labels[0] = "Усі модулі";
        checked[0] = allModules;
        for (int i = 0; i < availableModules.size(); i++) {
            String name = availableModules.get(i);
            labels[i + 1] = name;
            checked[i + 1] = !allModules && selectedModules.contains(name);
        }

        new AlertDialog.Builder(activity)
                .setTitle("Фільтр модулів")
                .setMultiChoiceItems(labels, checked, (dialog, which, value) -> {
                    if (which == 0) {
                        allModules = value;
                        if (value) selectedModules.clear();
                    } else {
                        allModules = false;
                        if (value) selectedModules.add(availableModules.get(which - 1));
                        else selectedModules.remove(availableModules.get(which - 1));
                        if (selectedModules.isEmpty()) allModules = true;
                    }
                })
                .setPositiveButton("Застосувати", (dialog, which) -> {
                    if (selectedModules.isEmpty()) allModules = true;
                    filterButton.setText(allModules ? "Усі модулі  ▾"
                            : String.join(", ", selectedModules) + "  ▾");
                    manager.logLifecycle("filterChanged", filterButton.getText().toString());
                    refresh();
                })
                .setNegativeButton("Скасувати", null)
                .show();
    }

    private static void refresh() {
        if (manager == null || !manager.isEnabled() || entries == null) return;
        List<EventRecord> records = manager.read(filter(), PAGE_SIZE + 1, Long.MAX_VALUE);
        entries.removeAllViews();
        hasMore = records.size() > PAGE_SIZE;
        if (hasMore) records.remove(records.size() - 1);
        oldestLoadedId = records.isEmpty() ? Long.MAX_VALUE
                : records.get(records.size() - 1).id;
        for (int i = records.size() - 1; i >= 0; i--) addRecord(records.get(i));
        count.setText(records.size() + " завантажено · максимум "
                + EventDatabase.MAX_RECORDS + " · прокрутіть угору для старіших");
        autoScroll = true;
        if (autoScroll) scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private static void loadOlder() {
        if (manager == null || entries == null || !hasMore || loadingOlder) return;
        loadingOlder = true;
        int oldHeight = entries.getHeight();
        int oldScrollY = scroll.getScrollY();
        List<EventRecord> records = manager.read(filter(), PAGE_SIZE + 1, oldestLoadedId);
        hasMore = records.size() > PAGE_SIZE;
        if (hasMore) records.remove(records.size() - 1);
        if (!records.isEmpty()) {
            oldestLoadedId = records.get(records.size() - 1).id;
            for (int i = 0; i < records.size(); i++) addRecordAtTop(records.get(i));
        }
        int loaded = entries.getChildCount();
        count.setText(loaded + " завантажено · максимум " + EventDatabase.MAX_RECORDS);
        scroll.post(() -> {
            if (entries != null && scroll != null) {
                scroll.scrollTo(0, oldScrollY + entries.getHeight() - oldHeight);
            }
            loadingOlder = false;
        });
    }

    private static void addRecordAtTop(EventRecord record) {
        TextView line = createRecordView(record);
        entries.addView(line, 0);
    }

    private static void addRecord(EventRecord record) {
        entries.addView(createRecordView(record));
    }

    private static TextView createRecordView(EventRecord record) {
        TextView line = new TextView(activity);
        line.setTextColor(Color.WHITE);
        line.setTextSize(12);
        line.setPadding(10, 10, 10, 10);
        String date = DATE.format(new Date(record.timestamp));
        String module = "[" + record.module + "]";
        String source = "[" + record.source + "]";
        SpannableStringBuilder text =
                new SpannableStringBuilder(date + " " + module + source + ": " + record.message);
        int moduleStart = date.length() + 1;
        int sourceStart = moduleStart + module.length();
        text.setSpan(new ForegroundColorSpan(Color.RED), moduleStart, sourceStart,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new ForegroundColorSpan(Color.rgb(76, 175, 80)), sourceStart,
                sourceStart + source.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        line.setText(text);
        return line;
    }

    private static String exportText(boolean currentFilter) {
        List<EventRecord> records = manager.readAll(currentFilter ? filter() : null);
        StringBuilder text = new StringBuilder();
        for (int i = records.size() - 1; i >= 0; i--) {
            EventRecord record = records.get(i);
            text.append(DATE.format(new Date(record.timestamp))).append(" [")
                    .append(record.module).append("][")
                    .append(record.source).append("]: ")
                    .append(record.message).append('\n');
        }
        return text.toString();
    }

    private static void copy() {
        String text = exportText(true);
        if (text.isEmpty()) {
            Toast.makeText(activity, "Немає записів для копіювання", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager clipboard =
                (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("MT8163 Events", text));
        Toast.makeText(activity, "Журнал скопійовано", Toast.LENGTH_SHORT).show();
    }

    private static void clear() {
        new AlertDialog.Builder(activity)
                .setTitle("Очистити журнал?")
                .setMessage("Буде видалено всю історію подій (не лише видимі записи).")
                .setNegativeButton("Скасувати", null)
                .setPositiveButton("Очистити", (dialog, which) -> {
                    autoScroll = true;
                    manager.clear();
                    Toast.makeText(activity, "Історію очищено", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private static void export() {
        new AlertDialog.Builder(activity)
                .setTitle("Експорт журналу")
                .setItems(new String[]{"Поточний фільтр", "Уся історія"}, (dialog, which) -> {
                    exportMode = which == 0 ? "filtered" : "all";
                    manager.logLifecycle("exportHistory",
                            which == 0 ? "Filtered export requested" : "Full history export requested");
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("text/plain");
                    intent.putExtra(Intent.EXTRA_TITLE, "MT8163Tools_Events_"
                            + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
                            .format(new Date()) + ".txt");
                    activity.startActivityForResult(intent, EXPORT_REQUEST);
                })
                .show();
    }

    public static void destroy() {
        if (manager != null) manager.setListener(null);
        activity = null;
        entries = null;
        scroll = null;
        count = null;
        filterButton = null;
        manager = null;
        availableModules = new ArrayList<>();
        selectedModules.clear();
        loadingOlder = false;
    }

    public static boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != EXPORT_REQUEST) return false;
        if (activity == null || manager == null) return true;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return true;

        Uri uri = data.getData();
        try (OutputStream output = activity.getContentResolver().openOutputStream(uri)) {
            if (output == null) throw new IllegalStateException("Cannot open export destination");
            output.write(exportText("filtered".equals(exportMode))
                    .getBytes(StandardCharsets.UTF_8));
            Toast.makeText(activity, "Експорт завершено", Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            Toast.makeText(activity, "Помилка експорту: " + error.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
        return true;
    }
}
