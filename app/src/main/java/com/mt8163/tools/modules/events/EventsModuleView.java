package com.mt8163.tools.modules.events;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.res.ColorStateList;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.mt8163.tools.R;
import com.mt8163.tools.core.ModuleContract;
import com.mt8163.tools.ui.DialogHelper;

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
    private static final int EVENTS_EXPORT_REQUEST = 8163;
    private static final int EVENTS_PAGE_SIZE = 100;
    private static final SimpleDateFormat EVENT_DATE_FORMAT =
            new SimpleDateFormat("dd.MM.yy HH:mm:ss", Locale.getDefault());

    private static EventManager eventsManager;
    private static Activity eventsActivity;
    private static LayoutInflater eventsInflater;
    private static LinearLayout eventEntriesContainer;
    private static ScrollView eventsScrollView;
    private static TextView eventsCountText;
    private static ImageButton eventsFilterButton;
    private static List<String> availableEventModules = new ArrayList<>();
    private static final Set<String> selectedEventModules = new HashSet<>();
    private static boolean allEventModulesSelected = true;
    private static boolean eventsAutoScrollEnabled = true;
    private static boolean loadingOlderEvents;
    private static boolean refreshingEvents;
    private static boolean refreshPending;
    private static int viewGeneration;
    private static boolean hasMoreEvents;
    private static long oldestLoadedEventId = Long.MAX_VALUE;
    private static String eventsExportMode = "all";

    private EventsModuleView() {
    }

    public static View create(Activity owner, List<ModuleContract> modules) {
        destroy();
        eventsActivity = owner;
        eventsInflater = LayoutInflater.from(owner);
        eventsManager = EventManager.get(owner);
        availableEventModules = new ArrayList<>();
        for (ModuleContract module : modules) availableEventModules.add(owner.getString(module.getNameRes()));
        selectedEventModules.clear();
        allEventModulesSelected = true;
        eventsAutoScrollEnabled = true;
        loadingOlderEvents = false;
        refreshingEvents = false;
        refreshPending = false;
        viewGeneration++;
        hasMoreEvents = false;
        oldestLoadedEventId = Long.MAX_VALUE;

        View root = eventsInflater.inflate(R.layout.module_events, null, false);
        eventEntriesContainer = root.findViewById(R.id.events_entries);
        eventsScrollView = root.findViewById(R.id.events_scroll);
        eventsCountText = root.findViewById(R.id.events_count);
        eventsFilterButton = root.findViewById(R.id.events_filter);
        updateFilterButtonState();

        root.findViewById(R.id.events_refresh).setOnClickListener(view -> refreshEvents());
        root.findViewById(R.id.events_copy).setOnClickListener(view -> copyEvents());
        root.findViewById(R.id.events_clear).setOnClickListener(view -> clearEvents());
        root.findViewById(R.id.events_export).setOnClickListener(view -> exportEvents());
        eventsFilterButton.setOnClickListener(view -> showEventsFilterDialog());

        eventsScrollView.setOnScrollChangeListener((View view, int x, int y, int oldX, int oldY) -> {
            boolean wasAtBottom = eventsAutoScrollEnabled;
            eventsAutoScrollEnabled = y + eventsScrollView.getHeight() >= eventEntriesContainer.getHeight()
                    - eventsActivity.getResources().getDimensionPixelSize(R.dimen.spacing_xxl);
            if (y < oldY && y <= eventsActivity.getResources()
                    .getDimensionPixelSize(R.dimen.events_load_older_threshold)
                    && hasMoreEvents && !loadingOlderEvents) {
                loadOlderEvents();
            } else if (!wasAtBottom && eventsAutoScrollEnabled) {
                refreshEvents();
            }
        });

        eventsManager.setListener(() -> {
            if (eventsManager != null && eventsManager.isEnabled() && eventsAutoScrollEnabled) refreshEvents();
        });
        refreshEvents();
        return root;
    }

    private static List<String> getSelectedEventModuleFilter() {
        return allEventModulesSelected ? null : new ArrayList<>(selectedEventModules);
    }

    private static void updateFilterButtonState() {
        if (eventsActivity == null || eventsFilterButton == null) return;
        int color = eventsActivity.getColor(allEventModulesSelected
                ? R.color.text_secondary
                : R.color.neon_red);
        eventsFilterButton.setImageTintList(ColorStateList.valueOf(color));
    }

    private static void showEventsFilterDialog() {
        if (eventsActivity == null || eventsManager == null) return;

        Dialog dialog = new Dialog(eventsActivity);
        View root = eventsInflater.inflate(R.layout.dialog_events_filter, null, false);
        CheckBox all = root.findViewById(R.id.events_filter_all);
        LinearLayout options = root.findViewById(R.id.events_filter_options);
        Button apply = root.findViewById(R.id.events_filter_apply);
        Button cancel = root.findViewById(R.id.events_filter_cancel);

        all.setChecked(allEventModulesSelected);
        List<CheckBox> moduleChecks = new ArrayList<>();
        for (String module : availableEventModules) {
            CheckBox option = (CheckBox) eventsInflater.inflate(
                    R.layout.item_events_filter, options, false);
            option.setText(module);
            option.setChecked(!allEventModulesSelected && selectedEventModules.contains(module));
            options.addView(option);
            moduleChecks.add(option);
        }

        all.setOnCheckedChangeListener((button, checked) -> {
            for (CheckBox option : moduleChecks) option.setEnabled(!checked);
        });
        for (CheckBox option : moduleChecks) option.setEnabled(!allEventModulesSelected);

        apply.setOnClickListener(view -> {
            selectedEventModules.clear();
            allEventModulesSelected = all.isChecked();
            if (!allEventModulesSelected) {
                for (int i = 0; i < moduleChecks.size(); i++) {
                    if (moduleChecks.get(i).isChecked()) {
                        selectedEventModules.add(availableEventModules.get(i));
                    }
                }
                if (selectedEventModules.isEmpty()) allEventModulesSelected = true;
            }
            updateFilterButtonState();
            dialog.dismiss();
            eventsManager.logLifecycle("filterChanged", allEventModulesSelected
                    ? eventsActivity.getString(R.string.events_filter_all)
                    : String.join(", ", selectedEventModules));
            refreshEvents();
        });
        cancel.setOnClickListener(view -> dialog.dismiss());

        DialogHelper.showSelection(eventsActivity, dialog, root);
    }

    private static void refreshEvents() {
        if (eventsManager == null || !eventsManager.isEnabled() || eventEntriesContainer == null) return;
        if (refreshingEvents) {
            refreshPending = true;
            return;
        }
        refreshingEvents = true;
        EventManager current = eventsManager;
        int generation = viewGeneration;
        current.readAsync(getSelectedEventModuleFilter(), EVENTS_PAGE_SIZE + 1, Long.MAX_VALUE, result -> {
            if (generation != viewGeneration) return;
            refreshingEvents = false;
            if (result.cancelled) {
                if (refreshPending) {
                    refreshPending = false;
                    refreshEvents();
                }
                return;
            }
            if (eventsManager != current || eventEntriesContainer == null || eventsActivity == null
                    || !current.isEnabled()) {
                refreshPending = false;
                return;
            }
            if (!result.success) {
                refreshPending = false;
                Toast.makeText(eventsActivity, R.string.events_read_failed, Toast.LENGTH_SHORT).show();
                return;
            }
            List<EventRecord> records = result.records;
            eventEntriesContainer.removeAllViews();
            hasMoreEvents = records.size() > EVENTS_PAGE_SIZE;
            if (hasMoreEvents) records.remove(records.size() - 1);
            oldestLoadedEventId = records.isEmpty() ? Long.MAX_VALUE
                    : records.get(records.size() - 1).id;
            for (int i = records.size() - 1; i >= 0; i--) addEventRecord(records.get(i));
            eventsCountText.setText(eventsActivity.getString(R.string.events_count_format,
                    records.size(), current.getDroppedCount(), EventDatabase.MAX_RECORDS));
            eventsAutoScrollEnabled = true;
            eventsScrollView.post(() -> {
                if (eventsScrollView != null) eventsScrollView.fullScroll(View.FOCUS_DOWN);
            });
            if (refreshPending) {
                refreshPending = false;
                refreshEvents();
            }
        });
    }

    private static void loadOlderEvents() {
        if (eventsManager == null || eventEntriesContainer == null || !hasMoreEvents || loadingOlderEvents) return;
        loadingOlderEvents = true;
        int oldHeight = eventEntriesContainer.getHeight();
        int oldScrollY = eventsScrollView.getScrollY();
        EventManager current = eventsManager;
        current.readAsync(getSelectedEventModuleFilter(), EVENTS_PAGE_SIZE + 1, oldestLoadedEventId, result -> {
            if (eventsManager != current || eventEntriesContainer == null || eventsScrollView == null) {
                loadingOlderEvents = false;
                return;
            }
            if (result.cancelled) {
                loadingOlderEvents = false;
                return;
            }
            if (!result.success) {
                loadingOlderEvents = false;
                if (eventsActivity != null) {
                    Toast.makeText(eventsActivity, R.string.events_read_failed, Toast.LENGTH_SHORT).show();
                }
                return;
            }
            List<EventRecord> records = result.records;
            hasMoreEvents = records.size() > EVENTS_PAGE_SIZE;
            if (hasMoreEvents) records.remove(records.size() - 1);
            if (!records.isEmpty()) {
                oldestLoadedEventId = records.get(records.size() - 1).id;
                for (int i = 0; i < records.size(); i++) addEventRecordAtTop(records.get(i));
            }
            eventsCountText.setText(eventsActivity.getString(R.string.events_count_loaded,
                    eventEntriesContainer.getChildCount(), current.getDroppedCount(),
                    EventDatabase.MAX_RECORDS));
            eventsScrollView.post(() -> {
                if (eventEntriesContainer != null && eventsScrollView != null) {
                    eventsScrollView.scrollTo(0, oldScrollY + eventEntriesContainer.getHeight() - oldHeight);
                }
                loadingOlderEvents = false;
            });
        });
    }

    private static void addEventRecordAtTop(EventRecord record) {
        addEventRecord(record, 0);
    }

    private static void addEventRecord(EventRecord record) {
        addEventRecord(record, -1);
    }

    private static void addEventRecord(EventRecord record, int index) {
        View row = eventsInflater.inflate(R.layout.item_event_record, eventEntriesContainer, false);
        ((TextView) row.findViewById(R.id.event_record_date))
                .setText(EVENT_DATE_FORMAT.format(new Date(record.timestamp)));
        ((TextView) row.findViewById(R.id.event_record_module))
                .setText("[" + record.module + "]");
        ((TextView) row.findViewById(R.id.event_record_source))
                .setText("[" + record.source + "]:");
        ((TextView) row.findViewById(R.id.event_record_message))
                .setText(record.message);
        if (index < 0) eventEntriesContainer.addView(row);
        else eventEntriesContainer.addView(row, index);
    }

    private static String buildEventsExportText(List<EventRecord> records) {
        StringBuilder text = new StringBuilder();
        for (int i = records.size() - 1; i >= 0; i--) {
            EventRecord record = records.get(i);
            text.append(EVENT_DATE_FORMAT.format(new Date(record.timestamp))).append(" [")
                    .append(record.module).append("][")
                    .append(record.source).append("]: ")
                    .append(record.message).append('\n');
        }
        return text.toString();
    }

    private static void copyEvents() {
        EventManager current = eventsManager;
        Activity owner = eventsActivity;
        if (current == null || owner == null) return;
        current.readAllAsync(getSelectedEventModuleFilter(), result -> {
            if (eventsManager != current || eventsActivity != owner || result.cancelled) return;
            if (!result.success) {
                Toast.makeText(owner, R.string.events_read_failed, Toast.LENGTH_SHORT).show();
                return;
            }
            String text = buildEventsExportText(result.records);
            if (text.isEmpty()) {
                Toast.makeText(owner, R.string.events_nothing_to_copy, Toast.LENGTH_SHORT).show();
                return;
            }
            ClipboardManager clipboard =
                    (ClipboardManager) owner.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) return;
            clipboard.setPrimaryClip(ClipData.newPlainText(owner.getString(R.string.events_clip_label), text));
            Toast.makeText(owner, R.string.events_copied, Toast.LENGTH_SHORT).show();
        });
    }

    private static void clearEvents() {
        if (eventsActivity == null || eventsManager == null) return;
        Dialog dialog = new Dialog(eventsActivity);
        View root = eventsInflater.inflate(R.layout.dialog_events_clear, null, false);
        Button confirm = root.findViewById(R.id.events_clear_confirm);
        Button cancel = root.findViewById(R.id.events_clear_cancel);
        confirm.setOnClickListener(view -> {
            Activity owner = eventsActivity;
            EventManager current = eventsManager;
            eventsAutoScrollEnabled = true;
            dialog.dismiss();
            current.clear(cleared -> {
                if (eventsActivity != owner || eventsManager != current) return;
                Toast.makeText(owner, cleared ? R.string.events_cleared
                        : R.string.events_clear_failed, Toast.LENGTH_SHORT).show();
                refreshEvents();
            });
        });
        cancel.setOnClickListener(view -> dialog.dismiss());
        DialogHelper.showCompact(eventsActivity, dialog, root);
    }

    private static void exportEvents() {
        if (eventsActivity == null || eventsManager == null) return;
        Dialog dialog = new Dialog(eventsActivity);
        View root = eventsInflater.inflate(R.layout.dialog_events_export, null, false);
        Button filtered = root.findViewById(R.id.events_export_filtered);
        Button all = root.findViewById(R.id.events_export_all);
        Button cancel = root.findViewById(R.id.events_export_cancel);

        filtered.setOnClickListener(view -> startExport(dialog, true));
        all.setOnClickListener(view -> startExport(dialog, false));
        cancel.setOnClickListener(view -> dialog.dismiss());
        DialogHelper.showCompact(eventsActivity, dialog, root);
    }

    private static void startExport(Dialog dialog, boolean filtered) {
        eventsExportMode = filtered ? "filtered" : "all";
        eventsManager.logLifecycle("exportHistory", filtered
                ? eventsActivity.getString(R.string.events_export_filtered_requested)
                : eventsActivity.getString(R.string.events_export_all_requested));
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, eventsActivity.getString(R.string.events_export_file_prefix)
                + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
                .format(new Date()) + ".txt");
        dialog.dismiss();
        eventsActivity.startActivityForResult(intent, EVENTS_EXPORT_REQUEST);
    }


    public static void destroy() {
        if (eventsManager != null) eventsManager.setListener(null);
        eventsActivity = null;
        eventsInflater = null;
        eventEntriesContainer = null;
        eventsScrollView = null;
        eventsCountText = null;
        eventsFilterButton = null;
        eventsManager = null;
        availableEventModules = new ArrayList<>();
        selectedEventModules.clear();
        loadingOlderEvents = false;
        refreshingEvents = false;
        refreshPending = false;
        viewGeneration++;
    }

    public static boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != EVENTS_EXPORT_REQUEST) return false;
        if (eventsActivity == null || eventsManager == null) return true;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return true;

        Activity owner = eventsActivity;
        EventManager current = eventsManager;
        Uri uri = data.getData();
        current.readAllAsync("filtered".equals(eventsExportMode) ? getSelectedEventModuleFilter() : null, result -> {
            if (eventsActivity != owner || eventsManager != current || result.cancelled) return;
            if (!result.success) {
                Toast.makeText(owner, R.string.events_read_failed, Toast.LENGTH_SHORT).show();
                return;
            }
            try (OutputStream output = owner.getContentResolver().openOutputStream(uri)) {
                if (output == null) throw new IllegalStateException(owner.getString(R.string.events_export_destination_unavailable));
                output.write(buildEventsExportText(result.records).getBytes(StandardCharsets.UTF_8));
                Toast.makeText(owner, R.string.events_export_completed, Toast.LENGTH_SHORT).show();
            } catch (Exception error) {
                Toast.makeText(owner, owner.getString(R.string.events_export_failed,
                        error.getMessage()), Toast.LENGTH_LONG).show();
            }
        });
        return true;
    }
}
