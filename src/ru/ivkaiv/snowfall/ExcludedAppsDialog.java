package ru.ivkaiv.snowfall;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.CheckedTextView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Local selection of launcher apps; no usage events, icons, logs or network access. */
final class ExcludedAppsDialog {
    private ExcludedAppsDialog() { }

    static String label(Context context, String packageName) {
        if (packageName == null || packageName.isEmpty()) return "Приложение не выбрано";
        try {
            PackageManager packages = context.getPackageManager();
            ApplicationInfo info = packages.getApplicationInfo(packageName, 0);
            CharSequence value = packages.getApplicationLabel(info);
            if (value != null && !value.toString().trim().isEmpty()) return value.toString();
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
            // Previously selected packages remain removable even after uninstall or filtering.
        }
        return packageName;
    }

    static void show(Activity activity, Runnable onSaved) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        if (Build.VERSION.SDK_INT < 35) {
            new AlertDialog.Builder(activity)
                    .setTitle("Исключения приложений")
                    .setMessage("Для ограниченной проверки выбранных приложений нужен Android 15 или новее.")
                    .setPositiveButton("Понятно", null).show();
            return;
        }

        Set<String> working = new HashSet<>(SmsVisibility.selectedPackages(activity));
        Map<String, AppRow> unique = new HashMap<>();
        PackageManager packages = activity.getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        boolean listAvailable = true;
        try {
            List<ResolveInfo> resolved = packages.queryIntentActivities(launcher, 0);
            if (resolved != null) {
                for (ResolveInfo result : resolved) {
                    if (result.activityInfo == null || !result.activityInfo.enabled
                            || !result.activityInfo.exported) continue;
                    String packageName = result.activityInfo.packageName;
                    if (packageName == null || packageName.isEmpty()
                            || packageName.equals(activity.getPackageName())
                            || unique.containsKey(packageName)) continue;
                    unique.put(packageName, new AppRow(packageName, label(activity, packageName)));
                }
            }
        } catch (RuntimeException unavailable) {
            listAvailable = false;
        }
        // Include every existing selection, including the old SMS target with no launcher entry.
        for (String selected : working) {
            if (!unique.containsKey(selected)) unique.put(selected, new AppRow(selected, label(activity, selected)));
        }
        List<AppRow> all = new ArrayList<>(unique.values());
        Collator collator = Collator.getInstance(Locale.getDefault());
        all.sort((left, right) -> {
            int byLabel = collator.compare(left.label, right.label);
            return byLabel == 0 ? left.packageName.compareTo(right.packageName) : byLabel;
        });

        int horizontal = dp(activity, 18);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(horizontal, dp(activity, 8), horizontal, 0);
        if (!listAvailable) {
            TextView explanation = new TextView(activity);
            explanation.setText("Список приложений недоступен.");
            explanation.setTextSize(13);
            content.addView(explanation);
        }

        EditText search = new EditText(activity);
        search.setSingleLine(true);
        search.setHint("Найти приложение");
        search.setContentDescription("Поиск приложений для исключения");
        content.addView(search, new LinearLayout.LayoutParams(-1, -2));

        TextView count = new TextView(activity);
        count.setTextSize(12);
        content.addView(count);
        ListView list = new ListView(activity);
        list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        list.setContentDescription("Приложения, в которых снег будет скрыт");
        int height = Math.min(dp(activity, 360), activity.getResources().getDisplayMetrics().heightPixels / 2);
        content.addView(list, new LinearLayout.LayoutParams(-1, height));
        TextView empty = new TextView(activity);
        empty.setText("Приложения не найдены");
        empty.setPadding(0, dp(activity, 16), 0, dp(activity, 16));
        content.addView(empty);
        list.setEmptyView(empty);

        AppsAdapter adapter = new AppsAdapter(activity, all, working);
        list.setAdapter(adapter);
        Runnable syncSelection = () -> {
            list.clearChoices();
            for (int i = 0; i < adapter.getCount(); i++) {
                list.setItemChecked(i, working.contains(adapter.getItem(i).packageName));
            }
            count.setText("Выбрано: " + working.size());
        };
        list.setOnItemClickListener((parent, view, position, id) -> {
            String selected = adapter.getItem(position).packageName;
            if (list.isItemChecked(position)) working.add(selected); else working.remove(selected);
            count.setText("Выбрано: " + working.size());
            adapter.notifyDataSetChanged();
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int length) {
                adapter.search(text.toString());
                syncSelection.run();
            }
            @Override public void afterTextChanged(Editable text) { }
        });
        syncSelection.run();
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Исключения")
                .setView(content)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", (window, which) -> {
                    SmsVisibility.saveSelectedPackages(activity, working);
                    if (onSaved != null) onSaved.run();
                }).create();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        dialog.show();
    }

    private static int dp(Context context, int value) {
        return Math.round(context.getResources().getDisplayMetrics().density * value);
    }

    private static final class AppRow {
        final String packageName;
        final String label;
        AppRow(String packageName, String label) {
            this.packageName = packageName;
            this.label = label;
        }
    }

    private static final class AppsAdapter extends BaseAdapter {
        private final Activity activity;
        private final List<AppRow> all;
        private final List<AppRow> shown = new ArrayList<>();
        private final Set<String> working;
        private final Map<String, Integer> labelCounts = new HashMap<>();

        AppsAdapter(Activity activity, List<AppRow> all, Set<String> working) {
            this.activity = activity;
            this.all = all;
            this.working = working;
            shown.addAll(all);
            for (AppRow row : all) labelCounts.merge(row.label, 1, Integer::sum);
        }
        void search(String query) {
            String needle = query.trim().toLowerCase(Locale.ROOT);
            shown.clear();
            for (AppRow row : all) {
                if (row.label.toLowerCase(Locale.ROOT).contains(needle)
                        || row.packageName.toLowerCase(Locale.ROOT).contains(needle)) shown.add(row);
            }
            notifyDataSetChanged();
        }
        @Override public int getCount() { return shown.size(); }
        @Override public AppRow getItem(int position) { return shown.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            CheckedTextView row = recycled instanceof CheckedTextView ? (CheckedTextView) recycled
                    : (CheckedTextView) activity.getLayoutInflater().inflate(
                            android.R.layout.simple_list_item_multiple_choice, parent, false);
            AppRow app = getItem(position);
            boolean ambiguous = labelCounts.getOrDefault(app.label, 0) > 1;
            row.setSingleLine(false);
            row.setMaxLines(3);
            row.setMinHeight(dp(activity, ambiguous ? 64 : 48));
            row.setText(ambiguous ? app.label + "\n" + app.packageName : app.label);
            row.setChecked(working.contains(app.packageName));
            row.setContentDescription(ambiguous ? app.label + ", " + app.packageName : app.label);
            return row;
        }
    }
}
