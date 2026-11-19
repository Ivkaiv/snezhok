package ru.ivkaiv.snowfall;

/** Only the app's own Quick Settings PendingIntent can use this auto-start entry. */
public final class TileStartActivity extends MainActivity {
    @Override protected boolean allowsTileStart() { return true; }
}
