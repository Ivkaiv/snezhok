import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.GlyphVector;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Produces original transparent glyphs and particle sprites for the HONOR theme. */
final class HonorLockSprites {
    private static final int PARTICLE_SIZE = 64;
    private static final int DIGIT_WIDTH = 128;
    private static final int DIGIT_HEIGHT = 190;
    private static final int DIGIT_BASELINE = 161;

    private static Graphics2D graphics(BufferedImage image) {
        Graphics2D context = image.createGraphics();
        context.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        context.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        return context;
    }

    private static void png(BufferedImage image, Path path) throws IOException {
        if (!ImageIO.write(image, "png", path.toFile())) throw new IOException("PNG encoder unavailable");
    }

    private static void radialSprite(Path path, float radius, Color core, Color middle, Color edge)
            throws IOException {
        float center = PARTICLE_SIZE / 2f;
        BufferedImage image = new BufferedImage(PARTICLE_SIZE, PARTICLE_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D context = graphics(image);
        try {
            context.setPaint(new RadialGradientPaint(center, center, radius,
                    new float[] {0f, .35f, 1f}, new Color[] {core, middle, edge}));
            context.fill(new Ellipse2D.Float(center-radius, center-radius, radius*2, radius*2));
        } finally {
            context.dispose();
        }
        png(image, path);
    }

    private static void crystal(Path path) throws IOException {
        int center = PARTICLE_SIZE / 2;
        BufferedImage image = new BufferedImage(PARTICLE_SIZE, PARTICLE_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D context = graphics(image);
        try {
            context.setStroke(new BasicStroke(7, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            context.setColor(new Color(62, 93, 125, 190));
            arms(context, center);
            context.setStroke(new BasicStroke(3.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            context.setColor(new Color(245, 253, 255, 245));
            arms(context, center);
            context.fill(new Ellipse2D.Float(center-4, center-4, 8, 8));
        } finally {
            context.dispose();
        }
        png(image, path);
    }

    private static void arms(Graphics2D context, int center) {
        for (int arm = 0; arm < 6; arm++) {
            double angle = Math.PI * arm / 3 - Math.PI / 2;
            int x = center + (int)Math.round(Math.cos(angle) * 24);
            int y = center + (int)Math.round(Math.sin(angle) * 24);
            context.drawLine(center, center, x, y);
        }
    }

    private static void clockGlyph(Path path, Font font, String text, int width) throws IOException {
        BufferedImage image = new BufferedImage(width, DIGIT_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D context = graphics(image);
        try {
            GlyphVector vector = font.createGlyphVector(context.getFontRenderContext(), text);
            Rectangle2D bounds = vector.getVisualBounds();
            float x = (float)(width * .5 - bounds.getCenterX());
            Shape glyph = vector.getOutline(x, DIGIT_BASELINE);
            context.translate(0, 3);
            context.setStroke(new BasicStroke(5, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            context.setColor(new Color(9, 21, 27, 155));
            context.draw(glyph);
            context.translate(0, -3);
            context.setPaint(new LinearGradientPaint(0, 18, 0, DIGIT_BASELINE,
                    new float[] {0f, .58f, 1f},
                    new Color[] {new Color(0xfff0cf), new Color(0xecc98b), new Color(0xbd8c4f)}));
            context.fill(glyph);
        } finally {
            context.dispose();
        }
        png(image, path);
    }

    private static void dateRule(Path path) throws IOException {
        BufferedImage image = new BufferedImage(120, 3, BufferedImage.TYPE_INT_ARGB);
        Graphics2D context = graphics(image);
        try {
            context.setPaint(new GradientPaint(0, 0, new Color(239, 206, 151, 0),
                    60, 0, new Color(239, 206, 151, 170), true));
            context.fillRect(0, 0, 120, 3);
        } finally {
            context.dispose();
        }
        png(image, path);
    }

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        Font serif = Font.createFont(Font.TRUETYPE_FONT, Path.of(args[1]).toFile()).deriveFont(211f);
        radialSprite(directory.resolve("snow-disk.png"), 30,
                new Color(253, 255, 255, 255), new Color(229, 244, 255, 232),
                new Color(67, 103, 139, 0));
        crystal(directory.resolve("snow-crystal.png"));
        radialSprite(directory.resolve("warm-glow.png"), 32,
                new Color(255, 243, 207, 245), new Color(255, 213, 140, 130),
                new Color(229, 174, 85, 0));
        for (int digit = 0; digit < 10; digit++) {
            clockGlyph(directory.resolve("time_" + digit + ".png"), serif, Integer.toString(digit), DIGIT_WIDTH);
        }
        clockGlyph(directory.resolve("time_dot.png"), serif, ":", 68);
        dateRule(directory.resolve("date-rule.png"));
    }
}
