package ru.ivkaiv.snowfall;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageEventsQuery;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Telephony;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Tracks only the user's selected packages and their visible activities, in memory. */
final class SmsVisibility {
    static final String PREF = "exclude_sms_app";
    static final String TARGET_PREF = "excluded_sms_package";
    static final String EXCLUDED_PACKAGES_PREF = "excluded_application_packages";
    private static final long INITIAL_WINDOW_MS = 30_000L;
    private static final long EVENT_OVERLAP_MS = 1_000L;
    private static final long PACKAGE_REFRESH_MS = 4_000L;

    private final Context context;
    private final Map<String, Set<String>> visibleActivities = new HashMap<>();
    private Set<String> monitoredPackages = Collections.emptySet();
    private long nextPackageCheck;
    private long lastQueryEnd;

    SmsVisibility(Context context) {
        this.context = context;
    }

    static boolean canRead(Context context) {
        if (Build.VERSION.SDK_INT < 35) return false;
        AppOpsManager ops = context.getSystemService(AppOpsManager.class);
        return ops != null && ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    /**
     * Returns a detached selection. Reading old preferences never migrates by writing.
     * A saved empty set is intentional and must not restore the legacy SMS target.
     */
    static Set<String> selectedPackages(Context context) {
        android.content.SharedPreferences preferences = SnowSettings.prefs(context);
        if (preferences.contains(EXCLUDED_PACKAGES_PREF)) {
            return copySelection(context, preferences.getStringSet(EXCLUDED_PACKAGES_PREF,
                    Collections.emptySet()));
        }
        if (!preferences.getBoolean(PREF, false)) return Collections.emptySet();
        String legacy = preferences.getString(TARGET_PREF, null);
        if (legacy == null || legacy.isEmpty()) {
            try {
                legacy = Telephony.Sms.getDefaultSmsPackage(context);
            } catch (RuntimeException unavailable) {
                return Collections.emptySet();
            }
        }
        return copySelection(context, Collections.singleton(legacy));
    }

    /** Called only after the owner presses the selection dialog's Save button. */
    static void saveSelectedPackages(Context context, Set<String> selected) {
        SnowSettings.prefs(context).edit()
                .putStringSet(EXCLUDED_PACKAGES_PREF, copySelection(context, selected)).apply();
    }

    private static Set<String> copySelection(Context context, Set<String> source) {
        Set<String> copy = new HashSet<>();
        if (source != null) {
            for (String value : source) {
                if (value == null) continue;
                String packageName = value.trim();
                if (!packageName.isEmpty() && !context.getPackageName().equals(packageName)) {
                    copy.add(packageName);
                }
            }
        }
        return copy;
    }

    static UsageEvents query(Context context, String packageName, long begin, long end) {
        if (Build.VERSION.SDK_INT < 35 || packageName == null || packageName.isEmpty()
                || begin >= end) return null;
        UsageStatsManager usage = context.getSystemService(UsageStatsManager.class);
        if (usage == null) return null;
        return usage.queryEvents(new UsageEventsQuery.Builder(begin, end)
                .setPackageNames(packageName)
                .setEventTypes(UsageEvents.Event.ACTIVITY_RESUMED, UsageEvents.Event.ACTIVITY_STOPPED)
                .build());
    }

    void reset() {
        monitoredPackages = Collections.emptySet();
        nextPackageCheck = 0;
        lastQueryEnd = 0;
        visibleActivities.clear();
    }

    boolean isVisible() {
        try {
            if (!canRead(context)) {
                reset();
                return false;
            }
            long elapsed = SystemClock.elapsedRealtime();
            // Explicit selections are cheap in-memory preferences: observe Save before
            // every query, even if a caller has not yet reset the monitor's cache.
            if (SnowSettings.prefs(context).contains(EXCLUDED_PACKAGES_PREF)
                    || elapsed >= nextPackageCheck) {
                Set<String> current = selectedPackages(context);
                if (!monitoredPackages.equals(current)) {
                    visibleActivities.clear();
                    lastQueryEnd = 0;
                    monitoredPackages = current;
                }
                nextPackageCheck = elapsed + PACKAGE_REFRESH_MS;
            }
            if (monitoredPackages.isEmpty()) return false;
            return readEvents();
        } catch (SecurityException error) {
            // Settings may revoke access just after canRead().
            reset();
            return false;
        }
    }

    private boolean readEvents() {
        if (Build.VERSION.SDK_INT < 35) return false;
        long end = System.currentTimeMillis() + 1;
        if (lastQueryEnd > end || end - lastQueryEnd > INITIAL_WINDOW_MS) {
            visibleActivities.clear();
            lastQueryEnd = 0;
        }
        long begin = Math.max(end - INITIAL_WINDOW_MS, lastQueryEnd - EVENT_OVERLAP_MS);
        UsageStatsManager usage = context.getSystemService(UsageStatsManager.class);
        if (usage == null || monitoredPackages.isEmpty()) return false;
        // A single OS-filtered query; never retrieve unrelated package names or event types.
        UsageEvents events = usage.queryEvents(new UsageEventsQuery.Builder(begin, end)
                .setPackageNames(monitoredPackages.toArray(new String[0]))
                .setEventTypes(UsageEvents.Event.ACTIVITY_RESUMED, UsageEvents.Event.ACTIVITY_STOPPED)
                .build());
        if (events == null) return !visibleActivities.isEmpty();

        UsageEvents.Event event = new UsageEvents.Event();
        while (events.hasNextEvent() && events.getNextEvent(event)) {
            String packageName = event.getPackageName();
            if (!monitoredPackages.contains(packageName)) continue;
            String activityName = event.getClassName();
            if (activityName == null) activityName = "";
            // A paused activity can still be visible, for example under a dialog or in split-screen.
            if (event.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) {
                Set<String> activities = visibleActivities.get(packageName);
                if (activities == null) {
                    activities = new HashSet<>();
                    visibleActivities.put(packageName, activities);
                }
                activities.add(activityName);
            } else if (event.getEventType() == UsageEvents.Event.ACTIVITY_STOPPED) {
                Set<String> activities = visibleActivities.get(packageName);
                if (activities != null) {
                    activities.remove(activityName);
                    if (activities.isEmpty()) visibleActivities.remove(packageName);
                }
            }
        }
        lastQueryEnd = end;
        return !visibleActivities.isEmpty();
    }
}
