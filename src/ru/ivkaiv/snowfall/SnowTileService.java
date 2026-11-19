package ru.ivkaiv.snowfall;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public final class SnowTileService extends TileService {
    @Override public void onStartListening() {
        super.onStartListening();
        Tile tile = getQsTile();
        if (tile != null) {
            tile.setState(SnowService.isRunning() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            tile.setLabel("Снежок");
            tile.setIcon(android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_notification));
            tile.updateTile();
        }
    }
    @Override public void onClick() {
        super.onClick();
        if (SnowService.isRunning()) {
            stopService(new Intent(this, SnowService.class));
            onStartListening();
            return;
        }
        // Start from a visible activity, avoiding background FGS-start restrictions.
        Intent open = new Intent(this, TileStartActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("start_snow", true);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 7, open,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } else {
            startActivityAndCollapse(open);
        }
    }
}
