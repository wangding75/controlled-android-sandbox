package t53;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Generates deterministic, non-user media fixtures for T53 evidence. */
public final class GenerateMediaFixtures {
    public static void main(String[] args) throws Exception {
        File output = new File(args[0]);
        if (!output.isDirectory() && !output.mkdirs()) throw new IllegalStateException(output.toString());
        image(output, "landscape.png", 1280, 720, new Color(25, 91, 150),
                new Color(240, 190, 50), "T53 LANDSCAPE");
        image(output, "portrait.png", 720, 1280, new Color(35, 125, 83),
                new Color(236, 110, 85), "T53 PORTRAIT");
    }

    private static void image(File output, String name, int width, int height,
            Color background, Color foreground, String label) throws Exception {
        BufferedImage value = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = value.createGraphics();
        graphics.setColor(background);
        graphics.fillRect(0, 0, width, height);
        int diameter = Math.min(width, height) * 2 / 3;
        graphics.setColor(foreground);
        graphics.fillOval((width - diameter) / 2, (height - diameter) / 2, diameter, diameter);
        graphics.setColor(Color.WHITE);
        graphics.setFont(new Font("SansSerif", Font.BOLD, Math.max(24, width / 28)));
        graphics.drawString(label, Math.max(20, width / 8), height - Math.max(36, height / 18));
        graphics.dispose();
        if (!ImageIO.write(value, "png", new File(output, name))) {
            throw new IllegalStateException("PNG writer unavailable");
        }
    }
}
