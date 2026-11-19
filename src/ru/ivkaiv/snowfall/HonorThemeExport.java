package ru.ivkaiv.snowfall;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Saves the bundled lock screen to a new document chosen by the owner. */
final class HonorThemeExport {
    static final String THEMES_PACKAGE = "com.hihonor.android.thememanager";
    private static final String THEME_ASSETS = "honor-garland/";
    private static final int REQUEST_BASE = 72;

    enum Garland {
        WARM("Тёплая", "warm"),
        COLOR("Разноцветная", "color");

        final String label;
        final String slug;

        Garland(String label, String slug) {
            this.label = label;
            this.slug = slug;
        }
    }

    enum Effect {
        TWINKLE("Мерцание", "twinkle"),
        WAVE("Волна", "wave"),
        SPARKS("Искры", "sparks"),
        BREATH("Дыхание", "breath"),
        STEADY("Ровный свет", "steady");

        final String label;
        final String slug;

        Effect(String label, String slug) {
            this.label = label;
            this.slug = slug;
        }
    }

    static final class Selection {
        final Garland garland;
        final Effect effect;

        Selection(Garland garland, Effect effect) {
            this.garland = garland;
            this.effect = effect;
        }

        String key() {
            return garland.slug + "-" + effect.slug;
        }

        String themeTitle() {
            return "Snowflake " + garland.slug + " " + effect.slug + " 4";
        }

        int requestCode() {
            return REQUEST_BASE + garland.ordinal() * Effect.values().length + effect.ordinal();
        }
    }

    static Selection fromRequestCode(int code) {
        Garland[] palettes = Garland.values();
        Effect[] effects = Effect.values();
        int index = code - REQUEST_BASE;
        if (index < 0 || index >= palettes.length * effects.length) return null;
        return new Selection(palettes[index / effects.length], effects[index % effects.length]);
    }

    static Intent createDocument(Selection selection) {
        Uri folder = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents",
                "primary:HONOR/Themes");
        return new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/octet-stream")
                .putExtra(Intent.EXTRA_TITLE, "Snezhok-HONOR-" + selection.key() + "-4.hnt")
                .putExtra(DocumentsContract.EXTRA_INITIAL_URI, folder);
    }

    static void write(Context context, Uri destination, Selection selection) throws IOException {
        if (!"content".equals(destination.getScheme())
                || !DocumentsContract.isDocumentUri(context, destination)) {
            throw new IOException("File picker did not return a document");
        }
        // Each prebuilt tail contains its ZIP directory with offsets into the common prefix.
        try (InputStream common = context.getAssets().open(THEME_ASSETS + "common.zip-part");
                InputStream tail = context.getAssets().open(THEME_ASSETS + selection.key() + ".zip-tail");
                OutputStream output = context.getContentResolver().openOutputStream(destination, "w")) {
            if (output == null) throw new IOException("File picker did not provide a writable document");
            byte[] buffer = new byte[32 * 1024];
            copy(common, output, buffer);
            copy(tail, output, buffer);
        }
    }

    private static void copy(InputStream input, OutputStream output, byte[] buffer) throws IOException {
        int bytes;
        while ((bytes = input.read(buffer)) != -1) {
            output.write(buffer, 0, bytes);
        }
    }

    private HonorThemeExport() { }
}
