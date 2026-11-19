import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Renders the brightness frames for a small glass bulb and its soft glow. */
final class HonorGarlandSprites {
    private static final int SIZE = 72;
    private static final float CENTER = SIZE / 2f;
    private static final int[] BRIGHTNESS = {12, 23, 44, 77, 124, 177, 220, 255};

    private static Color paint(int rgb, float strength, int level) {
        int alpha = Math.round(BRIGHTNESS[level] * strength);
        return new Color((rgb >>> 16) & 255, (rgb >>> 8) & 255, rgb & 255, alpha);
    }

    private static void bulb(Path target, int glass, int halo, int level) throws IOException {
        BufferedImage frame = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D context = frame.createGraphics();
        try {
            context.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            context.setPaint(new RadialGradientPaint(CENTER, CENTER, CENTER,
                    new float[] {0f, .11f, .32f, .61f, 1f},
                    new Color[] {
                        paint(0xfffaf0, 1f, level), paint(glass, 1f, level),
                        paint(glass, .60f, level), paint(halo, .25f, level),
                        paint(halo, 0f, level)
                    }));
            context.fill(new Ellipse2D.Float(0, 0, SIZE, SIZE));
        } finally {
            context.dispose();
        }
        if (!ImageIO.write(frame, "png", target.toFile())) {
            throw new IOException("PNG encoder unavailable");
        }
    }

    public static void main(String[] args) throws IOException {
        Path directory = Path.of(args[0]);
        int glass = Integer.parseInt(args[2], 16);
        int halo = Integer.parseInt(args[3], 16);
        for (int level = 0; level < BRIGHTNESS.length; level++) {
            bulb(directory.resolve(args[1] + "-" + level + ".png"), glass, halo, level);
        }
    }
}
