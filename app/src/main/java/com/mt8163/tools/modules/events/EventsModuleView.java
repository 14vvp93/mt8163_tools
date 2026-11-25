package com.mt8163.tools.modules.events;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.mt8163.tools.R;
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
    private static LayoutInflater inflater;
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
        inflater = LayoutInflater.from(owner);
        manager = EventManager.get(owner);
        availableModules = new ArrayList<>();
        for (ModuleContract module : modules) availableModules.add(owner.getString(module.getNameRes()));
        selectedModules.clear();
        allModules = true;
        autoScroll = true;
        loadingOlder = false;
        hasMore = false;
        oldestLoadedId = Long.MAX_VALUE;

        View root = inflater.inflate(R.layout.module_events, null, false);
        entries = root.findViewById(R.id.events_entries);
        scroll = root.findViewById(R.id.events_scroll);
        count = root.findViewById(R.id.events_count);
        filterButton = root.findViewById(R.id.events_filter);

        root.findViewById(R.id.events_refresh).setOnClickListener(view -> refresh());
        root.findViewById(R.id.events_copy).setOnClickListener(view -> copy());
        root.findViewById(R.id.events_clear).setOnClickListener(view -> clear());
        root.findViewById(R.id.events_export).setOnClickListener(view -> export());
        filterButton.setOnClickListener(view -> chooseFilter());

        scroll.setOnScrollChangeListener((View view, int x, int y, int oldX, int oldY) -> {
            boolean wasAtBottom = autoScroll;
            autoScroll = y + scroll.getHeight() >= entries.getHeight()
                    - activity.getResources().getDimensionPixelSize(R.dimen.spacing_xxl);
            if (y < oldY && y <= activity.getResources()
                    .getDimensionPixelSize(R.dimen.events_load_older_threshold)
                    && hasMore && !loadingOlder) {
                loadOlder();
            } else if (!wasAtBottom && autoScroll) {
                refresh();
            }
        });

        manager.setListener(() -> {
            if (manager != null && manager.isEnabled() && autoScroll) refresh();
        });
        refresh();
        return root;
    }

    private static List<String> filter() {
        return allModules ? null : new ArrayList<>(selectedModules);
    }

    private static void chooseFilter() {
        if (activity == null || manager == null) return;

        Dialog dialog = new Dialog(activity);
        View root = inflater.inflate(R.layout.dialog_events_filter, null, false);
        CheckBox all = root.findViewById(R.id.events_filter_all);
        LinearLayout options = root.findViewById(R.id.events_filter_options);
        Button apply = root.findViewById(R.id.events_filter_apply);
        Button cancel = root.findViewById(R.id.events_filter_cancel);

        all.setChecked(allModules);
        List<CheckBox> moduleChecks = new ArrayList<>();
        for (String module : availableModules) {
            CheckBox option = (CheckBox) inflater.inflate(
                    R.layout.item_events_filter, options, false);
            option.setText(module);
            option.setChecked(!allModules && selectedModules.contains(module));
            options.addView(option);
            moduleChecks.add(option);
        }

        all.setOnCheckedChangeListener((button, checked) -> {
            for (CheckBox option : moduleChecks) option.setEnabled(!checked);
        });
        for (CheckBox option : moduleChecks) option.setEnabled(!allModules);

        apply.setOnClickListener(view -> {
            selectedModules.clear();
            allModules = all.isChecked();
            if (!allModules) {
                for (int i = 0; i < moduleChecks.size(); i++) {
                    if (moduleChecks.get(i).isChecked()) {
                        selectedModules.add(availableModules.get(i));
                    }
                }
                if (selectedModules.isEmpty()) allModules = true;
            }
            if (allModules) {
                filterButton.setText(R.string.events_filter_all);
            } else {
                filterButton.setText(activity.getString(R.string.events_filter_selected,
                        String.join(", ", selectedModules)));
            }
            dialog.dismiss();
            manager.logLifecycle("filterChanged", filterButton.getText().toString());
            refresh();
        });
        cancel.setOnClickListener(view -> dialog.dismiss());

        showDialog(dialog, root);
    }

    private static void refresh() {
        if (manager == null || !manager.isEnabled() || entries == null) return;
        EventManager current = manager;
        current.readAsync(filter(), PAGE_SIZE + 1, Long.MAX_VALUE, records -> {
            if (manager != current || entries == null || activity == null
                    || !current.isEnabled()) return;
            entries.removeAllViews();
            hasMore = records.size() > PAGE_SIZE;
            if (hasMore) records.remove(records.size() - 1);
            oldestLoadedId = records.isEmpty() ? Long.MAX_VALUE
                    : records.get(records.size() - 1).id;
            for (int i = records.size() - 1; i >= 0; i--) addRecord(records.get(i));
            count.setText(activity.getString(R.string.events_count_format,
                    records.size(), current.getDroppedCount(), EventDatabase.MAX_RECORDS));
            autoScroll = true;
            scroll.post(() -> {
                if (scroll != null) scroll.fullScroll(View.FOCUS_DOWN);
            });
        });
    }

    private static void loadOlder() {
        if (manager == null || entries == null || !hasMore || loadingOlder) return;
        loadingOlder = true;
        int oldHeight = entries.getHeight();
        int oldScrollY = scroll.getScrollY();
        EventManager current = manager;
        current.readAsync(filter(), PAGE_SIZE + 1, oldestLoadedId, records -> {
            if (manager != current || entries == null || scroll == null) {
                loadingOlder = false;
                return;
            }
            hasMore = records.size() > PAGE_SIZE;
            if (hasMore) records.remove(records.size() - 1);
            if (!records.isEmpty()) {
                oldestLoadedId = records.get(records.size() - 1).id;
                for (int i = 0; i < records.size(); i++) addRecordAtTop(records.get(i));
            }
            count.setText(activity.getString(R.string.events_count_loaded,
                    entries.getChildCount(), current.getDroppedCount(),
                    EventDatabase.MAX_RECORDS));
            scroll.post(() -> {
                if (entries != null && scroll != null) {
                    scroll.scrollTo(0, oldScrollY + entries.getHeight() - oldHeight);
                }
                loadingOlder = false;
            });
        });
    }

    private static void addRecordAtTop(EventRecord record) {
        addRecord(record, 0);
    }

    private static void addRecord(EventRecord record) {
        addRecord(record, -1);
    }

    private static void addRecord(EventRecord record, int index) {
        View row = inflater.inflate(R.layout.item_event_record, entries, false);
        ((TextView) row.findViewById(R.id.event_record_date))
                .setText(DATE.format(new Date(record.timestamp)));
        ((TextView) row.findViewById(R.id.event_record_module))
                .setText("[" + record.module + "]");
        ((TextView) row.findViewById(R.id.event_record_source))
                .setText("[" + record.source + "]:");
        ((TextView) row.findViewById(R.id.event_record_message))
                .setText(record.message);
        if (index < 0) entries.addView(row);
        else entries.addView(row, index);
    }

    private static String exportText(List<EventRecord> records) {
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
        EventManager current = manager;
        Activity owner = activity;
        if (current == null || owner == null) return;
        current.readAllAsync(filter(), records -> {
            if (manager != current || activity != owner) return;
            String text = exportText(records);
            if (text.isEmpty()) {
                Toast.makeText(owner, R.string.events_nothing_to_copy, Toast.LENGTH_SHORT).show();
                return;
            }
            ClipboardManager clipboard =
                    (ClipboardManager) owner.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) return;
            clipboard.setPrimaryClip(ClipData.newPlainText("MT8163 Events", text));
            Toast.makeText(owner, R.string.events_copied, Toast.LENGTH_SHORT).show();
        });
    }

    private static void clear() {
        if (activity == null || manager == null) return;
        Dialog dialog = new Dialog(activity);
        View root = inflater.inflate(R.layout.dialog_events_clear, null, false);
        Button confirm = root.findViewById(R.id.events_clear_confirm);
        Button cancel = root.findViewById(R.id.events_clear_cancel);
        confirm.setOnClickListener(view -> {
            autoScroll = true;
            manager.clear();
            dialog.dismiss();
            Toast.makeText(activity, R.string.events_cleared, Toast.LENGTH_SHORT).show();
            refresh();
        });
        cancel.setOnClickListener(view -> dialog.dismiss());
        showDialog(dialog, root);
    }

    private static void export() {
        if (activity == null || manager == null) return;
        Dialog dialog = new Dialog(activity);
        View root = inflater.inflate(R.layout.dialog_events_export, null, false);
        Button filtered = root.findViewById(R.id.events_export_filtered);
        Button all = root.findViewById(R.id.events_export_all);
        Button cancel = root.findViewById(R.id.events_export_cancel);

        filtered.setOnClickListener(view -> startExport(dialog, true));
        all.setOnClickListener(view -> startExport(dialog, false));
        cancel.setOnClickListener(view -> dialog.dismiss());
        showDialog(dialog, root);
    }

    private static void startExport(Dialog dialog, boolean filtered) {
        exportMode = filtered ? "filtered" : "all";
        manager.logLifecycle("exportHistory", filtered
                ? "Filtered export requested" : "Full history export requested");
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, "MT8163Tools_Events_"
                + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
                .format(new Date()) + ".txt");
        dialog.dismiss();
        activity.startActivityForResult(intent, EXPORT_REQUEST);
    }

    private static void showDialog(Dialog dialog, View root) {
        dialog.setContentView(root);
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
    }

    public static void destroy() {
        if (manager != null) manager.setListener(null);
        activity = null;
        inflater = null;
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

        Activity owner = activity;
        EventManager current = manager;
        Uri uri = data.getData();
        current.readAllAsync("filtered".equals(exportMode) ? filter() : null, records -> {
            if (activity != owner || manager != current) return;
            try (OutputStream output = owner.getContentResolver().openOutputStream(uri)) {
                if (output == null) throw new IllegalStateException("Cannot open export destination");
                output.write(exportText(records).getBytes(StandardCharsets.UTF_8));
                Toast.makeText(owner, R.string.events_export_completed, Toast.LENGTH_SHORT).show();
            } catch (Exception error) {
                Toast.makeText(owner, owner.getString(R.string.events_export_failed,
                        error.getMessage()), Toast.LENGTH_LONG).show();
            }
        });
        return true;
    }
}
