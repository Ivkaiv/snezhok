package ru.ivkaiv.snowfall;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.StatusBarManager;
import android.app.WallpaperManager;
import android.app.WallpaperInfo;
import android.app.usage.UsageEvents;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int BACKGROUND = 0xff101b26;
    private static final int CARD = 0xff192938;
    private static final int BUTTON = 0xff263c4c;
    private static final int TEXT = 0xfff1f6f9;
    private static final int MUTED = 0xffabc1cf;
    private static final int ACCENT = 0xff9de3da;
    private static final String GARLAND_PREF = "honor_multicolor_garland";
    private static final String EFFECT_PREF = "honor_garland_effect";
    private static final Preset[] PRESETS = {
            new Preset("Лёгкий снег", 18, 22, 35),
            new Preset("Снегопад", 42, 48, 45),
            new Preset("Метель", 90, 78, 65),
    };
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private SnowView preview;
    private HolidayPreviewView holidayPreview;
    private Switch overlayTreeControl;
    private Switch homeTreeControl;
    private Switch lockTreeControl;
    private TextView status;
    private TextView wallpaperAssignment;
    private Button toggle;
    private Button snowTab;
    private Button wallpaperTab;
    private LinearLayout snowPage;
    private LinearLayout wallpaperPage;
    private boolean wallpaperSelected;
    private final Button[] presetButtons = new Button[PRESETS.length];
    private SeekBar intensitySlider;
    private SeekBar driftSlider;
    private SeekBar cleanupSlider;
    private Button saveThemeButton;
    private boolean exportingTheme;
    private Button smsUsageAccess;
    private Button smsDiagnostic;
    private TextView exclusionSummary;
    private Set<String> renderedExclusions;
    private TextView smsProbeResult;
    private String smsProbePackage;
    private long smsProbeStartedAt;
    private long smsProbeElapsedAt;
    private boolean smsProbeLeftApp;
    private boolean waitingOverlay;
    private boolean startRequested;
    private boolean resumed;
    private String systemUiPauseToken;
    private static final int NOTIFICATION_PERMISSION = 20;
    private static final int OVERLAY_SETTINGS_REQUEST = 22;
    private static final int SYSTEM_UI_REQUEST = 71;
    private final Runnable statusRefresh = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            refreshStatus();
            handler.postDelayed(this, 1000);
        }
    };

    private static final class Preset {
        final String label;
        final int intensity;
        final int drift;
        final int cleanup;

        Preset(String label, int intensity, int drift, int cleanup) {
            this.label = label;
            this.intensity = intensity;
            this.drift = drift;
            this.cleanup = cleanup;
        }

        boolean matches(int currentIntensity, int currentDrift, int currentCleanup) {
            return intensity == currentIntensity && drift == currentDrift && cleanup == currentCleanup;
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = SnowSettings.prefs(this);
        if (state != null) waitingOverlay = state.getBoolean("waiting_overlay");
        if (state != null) systemUiPauseToken = state.getString("system_ui_pause_token");
        startRequested = state != null ? state.getBoolean("start_requested")
                : allowsTileStart() && getIntent().getBooleanExtra("start_snow", false);
        getIntent().removeExtra("start_snow");
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BACKGROUND);
        scroll.setFillViewport(true);
        LinearLayout content = column();
        content.setPadding(dp(16), dp(14), dp(16), dp(24));
        scroll.addView(content);
        if (Build.VERSION.SDK_INT >= 30) {
            scroll.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                scroll.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        }
        buildHero(content);
        buildTabs(content);
        snowPage = buildSnowPage();
        wallpaperPage = buildWallpaperPage();
        content.addView(snowPage);
        content.addView(wallpaperPage);
        setWallpaperPage(state != null && state.getBoolean("wallpaper_page"));
        setContentView(scroll);
        refreshStatus();
        updatePreviews();
    }

    private void buildHero(LinearLayout content) {
        LinearLayout hero = card();
        hero.setPadding(0, 0, 0, 0);
        FrameLayout art = new FrameLayout(this);
        art.setBackground(round(BACKGROUND, 23));
        art.setClipToOutline(true);
        ImageView backdrop = decorativeImage(R.drawable.premium_backdrop);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        ImageView tree = decorativeImage(R.drawable.premium_tree);
        tree.setScaleType(ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams treeSize = new FrameLayout.LayoutParams(dp(160), dp(176),
                Gravity.END | Gravity.BOTTOM);
        treeSize.setMarginEnd(dp(7));
        art.addView(tree, treeSize);
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xf50b1723, 0x9a0b1723, 0x000b1723}));
        art.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        preview = new SnowView(this);
        art.addView(preview, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout identity = column();
        identity.setPaddingRelative(dp(23), dp(12), dp(18), dp(12));
        TextView title = text("Снежок", 38, 0xffffefd0);
        title.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        identity.addView(title);
        add(identity, text("Снег поверх приложений", 14, TEXT), 3, 0);
        art.addView(identity, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL));
        hero.addView(art, new LinearLayout.LayoutParams(-1, dp(174)));

        LinearLayout weather = column();
        weather.setPadding(dp(16), dp(14), dp(16), dp(16));
        status = text("", 14, MUTED);
        add(weather, status, 0, 9);
        toggle = actionButton("Включить снег", true);
        toggle.setOnClickListener(view -> toggleSnow());
        weather.addView(toggle);
        hero.addView(weather);
        add(content, hero, 0, 14);
    }

    private ImageView decorativeImage(int resource) {
        ImageView view = new ImageView(this);
        view.setImageResource(resource);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    private void toggleSnow() {
        if (SnowService.isPausedForSystemUi()) {
            SnowService.resumeForUser();
            systemUiPauseToken = null;
            refreshStatus();
        } else if (SnowService.isRunning()) {
            stopService(new Intent(this, SnowService.class));
            handler.postDelayed(this::refreshStatus, 150);
        } else {
            requestStart();
        }
    }

    private void buildTabs(LinearLayout content) {
        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(dp(4), dp(4), dp(4), dp(4));
        tabs.setBackground(round(CARD, 17));
        snowTab = secondary("Снег");
        wallpaperTab = secondary("Обои");
        addTab(tabs, snowTab, false);
        addTab(tabs, wallpaperTab, true);
        add(content, tabs, 0, 16);
    }

    private void addTab(LinearLayout tabs, Button tab, boolean wallpaper) {
        tab.setOnClickListener(view -> setWallpaperPage(wallpaper));
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -2, 1);
        size.setMarginStart(dp(2));
        size.setMarginEnd(dp(2));
        tabs.addView(tab, size);
    }

    private void setWallpaperPage(boolean wallpaper) {
        wallpaperSelected = wallpaper;
        snowPage.setVisibility(wallpaper ? View.GONE : View.VISIBLE);
        wallpaperPage.setVisibility(wallpaper ? View.VISIBLE : View.GONE);
        styleTab(snowTab, !wallpaper);
        styleTab(wallpaperTab, wallpaper);
        updatePreviewAnimation();
        if (wallpaper) refreshWallpaperAssignment();
    }

    private void styleTab(Button tab, boolean selected) {
        tab.setSelected(selected);
        tab.setTextColor(selected ? BACKGROUND : TEXT);
        tab.setBackground(buttonSurface(selected ? ACCENT : CARD, selected));
    }

    private LinearLayout buildSnowPage() {
        LinearLayout page = column();
        LinearLayout snow = card();
        snow.addView(sectionTitle("Снегопад"));
        addPresets(snow);
        intensitySlider = slider(snow, "Интенсивность", "intensity", 42, 5);
        driftSlider = slider(snow, "Высота сугробов", "drift", 48, 0);
        cleanupSlider = slider(snow, "Скорость таяния", "cleanup_speed", 45, 0);
        refreshPresets();
        Button clear = secondary("Убрать сугробы");
        clear.setOnClickListener(view -> clearSnow());
        add(snow, clear, 8, 0);
        page.addView(snow);

        LinearLayout appearance = expandable(page, "Эффекты и плавность");
        overlayTreeControl = switchRow(appearance, "Ёлочка поверх приложений", "tree", true);
        switchRow(appearance, "Ёлочка слева", "left", false);
        switchRow(appearance, "Бережный режим", "eco", false);

        if (Build.VERSION.SDK_INT >= 35) {
            buildExclusionSettings(expandable(page, "Исключения"));
        }
        LinearLayout system = expandable(page, "Управление");
        Button tile = secondary("Добавить кнопку в шторку");
        tile.setOnClickListener(view -> addTile());
        add(system, tile, 0, 8);
        Button battery = secondary("Настройки работы в фоне");
        battery.setOnClickListener(view -> openSystemUi(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()))));
        system.addView(battery);
        if (Build.VERSION.SDK_INT >= 33) {
            Button notifications = secondary("Уведомления");
            notifications.setOnClickListener(view -> openSystemUi(
                    new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())));
            add(system, notifications, 8, 0);
        }
        return page;
    }

    private void addPresets(LinearLayout card) {
        LinearLayout row = new LinearLayout(this);
        row.setBaselineAligned(false);
        for (int i = 0; i < PRESETS.length; i++) {
            Preset preset = PRESETS[i];
            Button button = secondary(preset.label);
            button.setPadding(dp(5), dp(7), dp(5), dp(7));
            button.setMinHeight(dp(52));
            button.setOnClickListener(view -> applyPreset(preset));
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -2, 1);
            if (i != 0) size.setMarginStart(dp(5));
            row.addView(button, size);
            presetButtons[i] = button;
        }
        add(card, row, 11, 2);
    }

    private void applyPreset(Preset preset) {
        prefs.edit().putInt("intensity", preset.intensity).putInt("drift", preset.drift)
                .putInt("cleanup_speed", preset.cleanup).apply();
        intensitySlider.setProgress(preset.intensity);
        driftSlider.setProgress(preset.drift);
        cleanupSlider.setProgress(preset.cleanup);
        refreshPresets();
        updatePreviews();
    }

    private void refreshPresets() {
        if (cleanupSlider == null) return;
        int intensity = intensitySlider.getProgress();
        int drift = driftSlider.getProgress();
        int cleanup = cleanupSlider.getProgress();
        for (int i = 0; i < PRESETS.length; i++) {
            Button button = presetButtons[i];
            boolean selected = PRESETS[i].matches(intensity, drift, cleanup);
            if (button.isSelected() == selected) continue;
            button.setSelected(selected);
            button.setTextColor(selected ? BACKGROUND : TEXT);
            button.setBackground(buttonSurface(selected ? ACCENT : BUTTON, selected));
        }
    }

    private void clearSnow() {
        preview.clearSnow();
        prefs.edit().putLong("clear_snow_serial", System.currentTimeMillis()).apply();
        if (holidayPreview != null) holidayPreview.clearSnow();
        if (SnowService.isRunning()) {
            startService(new Intent(this, SnowService.class).setAction(SnowService.CLEAR));
        }
    }

    private void buildExclusionSettings(LinearLayout section) {
        exclusionSummary = text("", 13, MUTED);
        add(section, exclusionSummary, 0, 6);
        Button picker = secondary("Выбрать приложения");
        picker.setOnClickListener(view -> ExcludedAppsDialog.show(this, () -> {
            smsProbePackage = null;
            smsProbeLeftApp = false;
            smsProbeResult.setVisibility(View.GONE);
            refreshExclusionSettings();
        }));
        add(section, picker, 0, 5);
        smsUsageAccess = secondary("Разрешить доступ к статистике");
        smsUsageAccess.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle("Исключения для приложений")
                .setMessage("Доступ нужен, чтобы узнать, открыто ли выбранное приложение, и скрыть над ним снег. "
                        + "Эти сведения остаются на телефоне.")
                .setNegativeButton("Позже", null)
                .setPositiveButton("Открыть настройки", (dialog, which) ->
                        openSystemUi(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)))
                .show());
        add(section, smsUsageAccess, 4, 4);
        smsDiagnostic = secondary("Проверить скрытие снега");
        smsDiagnostic.setOnClickListener(view -> startExclusionProbe());
        add(section, smsDiagnostic, 4, 4);
        smsProbeResult = text("", 13, MUTED);
        smsProbeResult.setTextIsSelectable(true);
        smsProbeResult.setVisibility(View.GONE);
        add(section, smsProbeResult, 0, 4);
        refreshExclusionSettings();
    }

    private LinearLayout buildWallpaperPage() {
        LinearLayout page = column();
        LinearLayout destinations = card();
        destinations.addView(sectionTitle("Живые обои Android"));
        WallpaperManager manager = WallpaperManager.getInstance(this);
        boolean canSetWallpaper = manager.isWallpaperSupported() && manager.isSetWallpaperAllowed();
        Button setWallpaper = secondary("Установить живые обои");
        setWallpaper.setOnClickListener(view ->
                openSystemUi(new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                        .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                                new ComponentName(this, SnowWallpaperService.class))));
        setWallpaper.setEnabled(canSetWallpaper);
        add(destinations, setWallpaper, 12, 0);
        add(destinations, text(canSetWallpaper
                ? "Вариант для экрана блокировки появится, если его предлагает Android."
                : "Установка обоев сейчас недоступна на этом устройстве.", 13, MUTED), 6, 0);
        page.addView(destinations);

        if (getPackageManager().getLaunchIntentForPackage(HonorThemeExport.THEMES_PACKAGE) != null) {
            addHonorLockCard(page);
        }

        LinearLayout scene = expandable(page, "Настроить живые обои");
        switchRow(scene, "Снег на рабочем столе", "wallpaper_snow", true);
        homeTreeControl = switchRow(scene, "Ёлочка на рабочем столе", "wallpaper_tree", true);
        switchRow(scene, "Гирлянды и фон", "holiday_decorations", true);
        switchRow(scene, "Белочка", "holiday_squirrel", true);
        slider(scene, "Размер ёлки", "holiday_tree_size", 100, 70, 110);

        LinearLayout previewSettings = expandable(page, "Сцена блокировки");
        holidayPreview = new HolidayPreviewView(this);
        holidayPreview.setBackground(round(BACKGROUND, 15));
        holidayPreview.setClipToOutline(true);
        previewSettings.addView(holidayPreview, new LinearLayout.LayoutParams(-1, dp(300)));
        add(previewSettings, text("Настройки живых обоев Android", 13, MUTED), 10, 4);
        switchRow(previewSettings, "Снег на блокировке", "wallpaper_lock_snow", true);
        lockTreeControl = switchRow(previewSettings, "Ёлочка на блокировке", "wallpaper_lock_tree", true);
        Button treeOnlyLock = secondary("Ёлочка только на блокировке");
        treeOnlyLock.setOnClickListener(view -> {
            overlayTreeControl.setChecked(false);
            homeTreeControl.setChecked(false);
            lockTreeControl.setChecked(true);
        });
        add(previewSettings, treeOnlyLock, 4, 6);
        switchRow(previewSettings, "Дополнительные часы", "holiday_clock", false);
        slider(previewSettings, "Положение часов", "holiday_clock_y", 30, 12, 65);

        LinearLayout more = expandable(page, "Статус установки обоев");
        wallpaperAssignment = text("", 13, MUTED);
        add(more, wallpaperAssignment, 0, 8);
        Button checkAssignment = secondary("Обновить статус");
        checkAssignment.setOnClickListener(view -> refreshWallpaperAssignment());
        add(more, checkAssignment, 0, 8);
        Button wallpaperList = secondary("Список живых обоев Android");
        wallpaperList.setOnClickListener(view -> openSystemUi(
                new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)));
        wallpaperList.setEnabled(canSetWallpaper);
        more.addView(wallpaperList);
        return page;
    }

    private void addHonorLockCard(LinearLayout page) {
        LinearLayout lock = card();
        lock.addView(sectionTitle("Тема блокировки HONOR"));
        add(lock, text("Цвет гирлянды", 15, TEXT), 10, 3);
        addGarlandChoice(lock);
        Button effect = secondary(effectLabel());
        effect.setOnClickListener(view -> chooseEffect(effect));
        add(lock, effect, 8, 0);
        saveThemeButton = secondary("Сохранить тему HONOR");
        saveThemeButton.setOnClickListener(view -> {
            HonorThemeExport.Selection choice = new HonorThemeExport.Selection(selectedGarland(), selectedEffect());
            openSystemUi(HonorThemeExport.createDocument(choice), choice.requestCode());
        });
        add(lock, saveThemeButton, 12, 0);
        add(lock, text("Папка для сохранения: HONOR/Themes", 13, MUTED), 6, 0);
        Button themeApp = secondary("Открыть «Темы»");
        themeApp.setOnClickListener(view -> openHonorThemes());
        add(lock, themeApp, 8, 0);
        add(page, lock, 12, 0);
    }

    private HonorThemeExport.Garland selectedGarland() {
        return prefs.getBoolean(GARLAND_PREF, false)
                ? HonorThemeExport.Garland.COLOR : HonorThemeExport.Garland.WARM;
    }

    private HonorThemeExport.Effect selectedEffect() {
        String saved = prefs.getString(EFFECT_PREF, "");
        for (HonorThemeExport.Effect effect : HonorThemeExport.Effect.values()) {
            if (effect.slug.equals(saved)) return effect;
        }
        // An existing warm theme used twinkle; the existing color theme used a wave.
        return selectedGarland() == HonorThemeExport.Garland.COLOR
                ? HonorThemeExport.Effect.WAVE : HonorThemeExport.Effect.TWINKLE;
    }

    private String effectLabel() {
        return "Эффект · " + selectedEffect().label;
    }

    private void chooseEffect(Button button) {
        HonorThemeExport.Effect[] effects = HonorThemeExport.Effect.values();
        String[] labels = new String[effects.length];
        for (int i = 0; i < effects.length; i++) labels[i] = effects[i].label;
        new AlertDialog.Builder(this).setTitle("Эффект гирлянды")
                .setSingleChoiceItems(labels, selectedEffect().ordinal(), (dialog, index) -> {
                    prefs.edit().putString(EFFECT_PREF, effects[index].slug).apply();
                    button.setText(effectLabel());
                    dialog.dismiss();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void chooseGarland(HonorThemeExport.Garland garland) {
        prefs.edit().putString(EFFECT_PREF, selectedEffect().slug)
                .putBoolean(GARLAND_PREF, garland == HonorThemeExport.Garland.COLOR).apply();
    }

    private void addGarlandChoice(LinearLayout card) {
        LinearLayout row = new LinearLayout(this);
        row.setBaselineAligned(false);
        Button warm = secondary(HonorThemeExport.Garland.WARM.label);
        Button color = secondary(HonorThemeExport.Garland.COLOR.label);
        row.addView(warm, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, -2, 1);
        right.setMarginStart(dp(6));
        row.addView(color, right);
        Runnable style = () -> {
            boolean warmSelected = selectedGarland() == HonorThemeExport.Garland.WARM;
            styleTab(warm, warmSelected);
            styleTab(color, !warmSelected);
        };
        warm.setOnClickListener(view -> {
            chooseGarland(HonorThemeExport.Garland.WARM);
            style.run();
        });
        color.setOnClickListener(view -> {
            chooseGarland(HonorThemeExport.Garland.COLOR);
            style.run();
        });
        style.run();
        card.addView(row);
    }

    private void updatePreviewAnimation() {
        if (preview != null) preview.setAnimating(resumed);
        if (holidayPreview != null) holidayPreview.setAnimating(resumed && wallpaperSelected);
    }

    private void requestStart() {
        if (!Settings.canDrawOverlays(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("Снег поверх приложений")
                    .setMessage(Build.VERSION.SDK_INT >= 30
                            ? "В настройках Android выбери «Снежок» и разреши показ поверх других приложений."
                            : "Разреши «Снежку» показ поверх других приложений в настройках Android.")
                    .setNegativeButton("Позже", null)
                    .setPositiveButton("Открыть настройки", (dialog, which) -> openOverlaySettings())
                    .show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
                && !prefs.getBoolean("notification_asked", false)) {
            new AlertDialog.Builder(this)
                    .setTitle("Управление из шторки")
                    .setMessage("Разреши уведомления, чтобы ставить снег на паузу и выключать его из шторки.")
                    .setNegativeButton("Без уведомлений", (dialog, which) -> {
                        prefs.edit().putBoolean("notification_asked", true).apply();
                        startSnow();
                    })
                    .setPositiveButton("Разрешить", (dialog, which) -> {
                        prefs.edit().putBoolean("notification_asked", true).apply();
                        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION);
                    })
                    .show();
            return;
        }
        startSnow();
    }

    private void openOverlaySettings() {
        Intent settings = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
        if (Build.VERSION.SDK_INT < 30) settings.setData(Uri.parse("package:" + getPackageName()));
        waitingOverlay = true;
        try {
            startActivityForResult(settings, OVERLAY_SETTINGS_REQUEST);
        } catch (android.content.ActivityNotFoundException | SecurityException error) {
            waitingOverlay = false;
            Toast.makeText(this, "Открой разрешение показа поверх других приложений в настройках Android.", Toast.LENGTH_LONG).show();
        }
    }

    private void startSnow() {
        try {
            startForegroundService(new Intent(this, SnowService.class));
            handler.postDelayed(this::refreshStatus, 250);
            handler.postDelayed(this::refreshStatus, 1000);
        } catch (RuntimeException error) {
            Toast.makeText(this, "Не удалось запустить снег. Проверь разрешение поверх окон.", Toast.LENGTH_LONG).show();
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == NOTIFICATION_PERMISSION) {
            startRequested = true;
            handler.post(this::continuePendingStart);
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        startRequested = allowsTileStart() && intent.getBooleanExtra("start_snow", false);
        intent.removeExtra("start_snow");
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        updatePreviewAnimation();
        if (SnowService.isRunning()) startService(new Intent(this, SnowService.class).setAction(SnowService.REFRESH));
        refreshStatus();
        refreshWallpaperAssignment();
        if (smsProbeLeftApp) handler.postDelayed(this::finishSmsProbe, 900);
        handler.post(statusRefresh);
        handler.post(this::continuePendingStart);
    }

    private void continuePendingStart() {
        if (!resumed || (!startRequested && !(waitingOverlay && Settings.canDrawOverlays(this)))) return;
        startRequested = false;
        waitingOverlay = false;
        requestStart();
    }

    @Override protected void onPause() {
        resumed = false;
        if (smsProbePackage != null) smsProbeLeftApp = true;
        updatePreviewAnimation();
        handler.removeCallbacksAndMessages(null);
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("waiting_overlay", waitingOverlay);
        state.putBoolean("start_requested", startRequested);
        state.putBoolean("wallpaper_page", wallpaperSelected);
        state.putString("system_ui_pause_token", systemUiPauseToken);
        super.onSaveInstanceState(state);
    }

    private void refreshStatus() {
        if (toggle == null) return;
        refreshExclusionSettings();
        boolean running = SnowService.isRunning();
        String label = SnowService.statusText();
        if ("Снег включён · экран разблокирован".equals(label)) label = "Снег включён";
        status.setText(label);
        status.setTextColor(running ? ACCENT : MUTED);
        toggle.setText(SnowService.isPausedForSystemUi() ? "Продолжить снег"
                : (running ? "Выключить снег" : "Включить снег"));
    }

    private void refreshExclusionSettings() {
        if (smsUsageAccess == null) return;
        Set<String> packages = SmsVisibility.selectedPackages(this);
        boolean hasSelection = !packages.isEmpty();
        boolean canRead = hasSelection && SmsVisibility.canRead(this);
        if (!packages.equals(renderedExclusions)) {
            renderedExclusions = packages;
            if (packages.size() == 1) {
                exclusionSummary.setText("Выбрано: " +
                        ExcludedAppsDialog.label(this, packages.iterator().next()));
            } else {
                exclusionSummary.setText(hasSelection ? "Выбрано приложений: " + packages.size()
                        : "Приложения не выбраны");
            }
        }
        smsUsageAccess.setVisibility(hasSelection && !canRead ? View.VISIBLE : View.GONE);
        smsDiagnostic.setVisibility(canRead ? View.VISIBLE : View.GONE);
        if (!hasSelection) smsProbeResult.setVisibility(View.GONE);
    }

    private void startExclusionProbe() {
        if (!SnowService.isRunning()) {
            showSmsProbeResult("Сначала включи снег.");
            return;
        }
        Set<String> targets = SmsVisibility.selectedPackages(this);
        if (targets.isEmpty()) {
            showSmsProbeResult("Сначала выбери приложение.");
            return;
        }
        if (targets.size() == 1) {
            openAppForProbe(targets.iterator().next());
            return;
        }
        String[] packages = targets.toArray(new String[0]);
        String[] labels = new String[packages.length];
        for (int i = 0; i < packages.length; i++) {
            labels[i] = ExcludedAppsDialog.label(this, packages[i]);
        }
        new AlertDialog.Builder(this).setTitle("Проверить приложение")
                .setItems(labels, (dialog, index) -> openAppForProbe(packages[index]))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void openAppForProbe(String packageName) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
        smsProbePackage = packageName;
        smsProbeStartedAt = System.currentTimeMillis();
        smsProbeElapsedAt = SystemClock.elapsedRealtime();
        smsProbeLeftApp = false;
        String instructions = "Подожди 3 секунды и вернись в «Снежок».";
        showSmsProbeResult(instructions);
        if (launch != null) {
            try {
                startActivity(launch);
                return;
            } catch (android.content.ActivityNotFoundException | SecurityException error) {
                // The app may have been disabled after the chooser opened.
            }
        }
        showSmsProbeResult("Открой выбранное приложение вручную. " + instructions);
    }

    private void finishSmsProbe() {
        String packageName = smsProbePackage;
        smsProbePackage = null;
        smsProbeLeftApp = false;
        if (packageName == null) return;
        if (SystemClock.elapsedRealtime() - smsProbeElapsedAt > 60_000L) {
            showSmsProbeResult("Прошла минута. Запусти проверку ещё раз.");
            return;
        }
        if (!SmsVisibility.canRead(this)) {
            showSmsProbeResult("Android сейчас не даёт «Снежку» доступ к статистике использования.");
            return;
        }
        if (!SmsVisibility.selectedPackages(this).contains(packageName)) {
            showSmsProbeResult("Приложение исключено из проверки. Выбери его заново.");
            return;
        }
        boolean opened = false;
        boolean closed = false;
        try {
            UsageEvents events = SmsVisibility.query(this, packageName,
                    smsProbeStartedAt, System.currentTimeMillis() + 1);
            UsageEvents.Event event = new UsageEvents.Event();
            while (events != null && events.hasNextEvent() && events.getNextEvent(event)) {
                if (!packageName.equals(event.getPackageName())) continue;
                opened |= event.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED;
                closed |= event.getEventType() == UsageEvents.Event.ACTIVITY_STOPPED;
            }
        } catch (RuntimeException error) {
            showSmsProbeResult("Запрос событий Android завершился ошибкой: " + error.getClass().getSimpleName());
            return;
        }
        showSmsProbeResult("Приложение: " + packageName
                + "\nСобытие открытия: " + (opened ? "есть" : "нет")
                + "\nСобытие закрытия: " + (closed ? "есть" : "нет")
                + "\nСнятие слоя снега: "
                + (SnowService.hidSnowForExclusionSince(smsProbeElapsedAt) ? "выполнено" : "не отмечено"));
    }

    private void showSmsProbeResult(String message) {
        smsProbeResult.setText(message);
        smsProbeResult.setVisibility(View.VISIBLE);
    }

    protected boolean allowsTileStart() { return false; }

    private void openSystemUi(Intent intent) {
        openSystemUi(intent, SYSTEM_UI_REQUEST);
    }

    private void openSystemUi(Intent intent, int requestCode) {
        String paused = SnowService.pauseForSystemUi();
        if (paused != null) systemUiPauseToken = paused;
        try {
            startActivityForResult(intent, requestCode);
        } catch (RuntimeException error) {
            resumeAfterSystemUi();
            Toast.makeText(this, "Не удалось открыть системное окно настроек", Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == OVERLAY_SETTINGS_REQUEST) {
            if (waitingOverlay && Settings.canDrawOverlays(this)) startRequested = true;
            waitingOverlay = false;
            handler.post(this::continuePendingStart);
            return;
        }
        HonorThemeExport.Selection selection = HonorThemeExport.fromRequestCode(code);
        if (code == SYSTEM_UI_REQUEST || selection != null) {
            resumeAfterSystemUi();
            refreshStatus();
            refreshWallpaperAssignment();
        }
        if (selection != null && result == RESULT_OK && data != null && data.getData() != null) {
            writeTheme(data.getData(), selection);
        }
    }

    private void resumeAfterSystemUi() {
        if (systemUiPauseToken == null) return;
        String token = systemUiPauseToken;
        systemUiPauseToken = null;
        SnowService.resumeAfterSystemUi(token);
    }

    private void writeTheme(Uri destination, HonorThemeExport.Selection selection) {
        if (exportingTheme || saveThemeButton == null) return;
        exportingTheme = true;
        saveThemeButton.setEnabled(false);
        saveThemeButton.setText("Сохраняю тему…");
        Context app = getApplicationContext();
        new Thread(() -> {
            boolean saved;
            try {
                HonorThemeExport.write(app, destination, selection);
                saved = true;
            } catch (IOException | SecurityException error) {
                saved = false;
            }
            boolean success = saved;
            runOnUiThread(() -> themeExportFinished(success, selection));
        }, "snow-honor-theme-export").start();
    }

    private void themeExportFinished(boolean success, HonorThemeExport.Selection selection) {
        Toast.makeText(getApplicationContext(), success
                ? "Тема сохранена. Выбери " + selection.themeTitle() + "."
                : "Не удалось сохранить файл темы", Toast.LENGTH_LONG).show();
        if (isDestroyed() || isFinishing()) return;
        exportingTheme = false;
        saveThemeButton.setEnabled(true);
        saveThemeButton.setText("Сохранить тему HONOR");
        if (success && resumed) openHonorThemes();
    }

    private void openHonorThemes() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(HonorThemeExport.THEMES_PACKAGE);
        if (launch == null) return;
        launch.removeFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        openSystemUi(launch);
    }

    private void refreshWallpaperAssignment() {
        if (wallpaperAssignment == null) return;
        if (Build.VERSION.SDK_INT < 34) {
            wallpaperAssignment.setText("Проверка доступна на Android 14 и новее.");
            return;
        }
        WallpaperManager manager = WallpaperManager.getInstance(this);
        wallpaperAssignment.setText("Живые обои Android\nРабочий стол: "
                + describeAssignment(manager, WallpaperManager.FLAG_SYSTEM)
                + "\nБлокировка: " + describeAssignment(manager, WallpaperManager.FLAG_LOCK));
    }

    private String describeAssignment(WallpaperManager manager, int which) {
        try {
            WallpaperInfo info = manager.getWallpaperInfo(which);
            if (info == null) return "нет данных";
            ComponentName own = new ComponentName(this, SnowWallpaperService.class);
            return own.equals(info.getComponent()) ? "Снежок ✓" : "другие живые обои";
        } catch (RuntimeException error) {
            return "система не разрешила проверку";
        }
    }

    private void addTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            getSystemService(StatusBarManager.class).requestAddTileService(
                    new ComponentName(this, SnowTileService.class), "Снежок",
                    Icon.createWithResource(this, R.drawable.ic_notification), getMainExecutor(),
                    result -> Toast.makeText(this, result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED
                            ? "Кнопка добавлена в шторку" : "Если кнопки нет, добавь «Снежок» через редактирование шторки",
                            Toast.LENGTH_LONG).show());
        } else {
            Toast.makeText(this, "Открой редактирование шторки и добавь кнопку «Снежок»", Toast.LENGTH_LONG).show();
        }
    }

    private SeekBar slider(LinearLayout parent, String label, String key, int fallback, int min) {
        return slider(parent, label, key, fallback, min, 100);
    }

    private SeekBar slider(LinearLayout parent, String label, String key, int fallback, int min, int max) {
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(label, 15, TEXT);
        heading.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        TextView valueLabel = text("", 14, ACCENT);
        valueLabel.setPaddingRelative(dp(8), 0, 0, 0);
        heading.addView(valueLabel);
        add(parent, heading, 14, 0);
        SeekBar bar = new SeekBar(this);
        bar.setMin(min);
        bar.setMax(max);
        bar.setProgress(prefs.getInt(key, fallback));
        valueLabel.setText(sliderValue(key, bar.getProgress()));
        bar.setProgressTintList(ColorStateList.valueOf(ACCENT));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(BUTTON));
        bar.setThumbTintList(ColorStateList.valueOf(ACCENT));
        bar.setContentDescription(label);
        parent.addView(bar, new LinearLayout.LayoutParams(-1, dp(44)));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seek, int value, boolean user) {
                valueLabel.setText(sliderValue(key, value));
                if (user) {
                    prefs.edit().putInt(key, value).apply();
                    refreshPresets();
                    updatePreviews();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seek) { }
            @Override public void onStopTrackingTouch(SeekBar seek) { }
        });
        return bar;
    }

    private String sliderValue(String key, int progress) {
        return "cleanup_speed".equals(key) && progress == 0 ? "Выкл." : progress + "%";
    }

    private Switch switchRow(LinearLayout parent, String label, String key, boolean fallback) {
        Switch control = new Switch(this);
        control.setText(label);
        control.setTextColor(TEXT);
        control.setTextSize(15);
        control.setMinHeight(dp(48));
        control.setSwitchPadding(dp(10));
        control.setPadding(0, dp(8), 0, dp(8));
        control.setChecked(prefs.getBoolean(key, fallback));
        control.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean(key, checked).apply();
            updatePreviews();
        });
        parent.addView(control, new LinearLayout.LayoutParams(-1, -2));
        return control;
    }

    private void updatePreviews() {
        if (preview != null) {
            SnowSettings.apply(this, preview);
            preview.setTreeVisible(false);
        }
        if (holidayPreview != null) holidayPreview.refreshSettings();
    }

    private LinearLayout expandable(LinearLayout parent, String title) {
        LinearLayout group = card();
        group.setPadding(dp(12), dp(4), dp(12), dp(4));
        Button header = secondary(title + "  ▾");
        header.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        header.setBackground(buttonSurface(CARD, false));
        header.setContentDescription(title);
        LinearLayout body = column();
        body.setPadding(dp(4), 0, dp(4), dp(12));
        body.setVisibility(View.GONE);
        setExpansionDescription(header, title, false);
        header.setOnClickListener(view -> {
            boolean show = body.getVisibility() != View.VISIBLE;
            body.setVisibility(show ? View.VISIBLE : View.GONE);
            header.setText(title + (show ? "  ▴" : "  ▾"));
            setExpansionDescription(header, title, show);
        });
        group.addView(header);
        group.addView(body);
        add(parent, group, 12, 0);
        return body;
    }

    private void setExpansionDescription(Button header, String title, boolean show) {
        String state = show ? "Развёрнуто" : "Свёрнуто";
        if (Build.VERSION.SDK_INT >= 30) header.setStateDescription(state);
        else header.setContentDescription(title + ". " + state);
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private LinearLayout card() {
        LinearLayout view = column();
        view.setPadding(dp(16), dp(15), dp(16), dp(15));
        view.setBackground(round(CARD, 23));
        return view;
    }

    private TextView sectionTitle(String value) {
        TextView title = text(value, 20, TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        return title;
    }

    private Button secondary(String value) {
        return actionButton(value, false);
    }

    private Button actionButton(String value, boolean primary) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextColor(primary ? BACKGROUND : TEXT);
        button.setTextSize(primary ? 17 : 14);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setMinHeight(dp(primary ? 52 : 48));
        button.setPadding(dp(12), dp(9), dp(12), dp(9));
        button.setBackground(buttonSurface(primary ? ACCENT : BUTTON, primary));
        return button;
    }

    private RippleDrawable buttonSurface(int color, boolean light) {
        ColorStateList ripple = ColorStateList.valueOf(light ? 0x290b2431 : 0x30c4e8ea);
        return new RippleDrawable(ripple, round(color, 13), null);
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }
    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }
    private void add(LinearLayout parent, View view, int top, int bottom) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(top); p.bottomMargin = dp(bottom); parent.addView(view, p); }
}
