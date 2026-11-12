import java.awt.*;
import java.awt.geom.Arc2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Generates all app artwork. Usage: java tools/MakeArt.java <projectDir>
 *   res/drawable-xhdpi/banner.png        320x180  TV launcher banner
 *   res/mipmap-{mdpi..xxxhdpi}/ic_launcher.png
 *   play/icon-512.png                    Play Store icon
 *   play/tv-banner-1280x720.png          Play Store TV banner
 *   play/feature-graphic-1024x500.png    Play Store feature graphic
 */
public class MakeArt {
    static final Color BG1 = new Color(0x0B1220), BG2 = new Color(0x16233B), ACCENT = new Color(0x38BDF8),
            TEXT = new Color(0xF1F5F9), TEXT2 = new Color(0x94A3B8);

    public static void main(String[] a) throws Exception {
        String dir = a.length > 0 ? a[0] : ".";
        write(banner(320, 180), dir + "/res/drawable-xhdpi/banner.png");
        int[] sizes = {48, 72, 96, 144, 192};
        String[] dens = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};
        for (int i = 0; i < sizes.length; i++) write(icon(sizes[i], true), dir + "/res/mipmap-" + dens[i] + "/ic_launcher.png");
        write(icon(512, false), dir + "/play/icon-512.png"); // Play applies its own mask: full square
        write(banner(1280, 720), dir + "/play/tv-banner-1280x720.png");
        write(banner(1024, 500), dir + "/play/feature-graphic-1024x500.png");
        // GitHub social preview + Open Graph image (shown when the repo/site link is shared)
        BufferedImage social = banner(1280, 640);
        Graphics2D sg = g(social);
        sg.setFont(new Font("SansSerif", Font.BOLD, 30));
        sg.setColor(ACCENT);
        String line = "for NVIDIA SHIELD TV  ·  no root";
        sg.drawString(line, (1280 - sg.getFontMetrics().stringWidth(line)) / 2, 590);
        sg.dispose();
        write(social, dir + "/docs/assets/social-preview-1280x640.png");
    }

    static Graphics2D g(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g;
    }

    static void wifi(Graphics2D g, double cx, double cy, double unit) {
        g.setColor(ACCENT);
        g.setStroke(new BasicStroke((float) (unit * 0.42), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 1; i <= 3; i++) {
            double r = unit * i;
            g.draw(new Arc2D.Double(cx - r, cy - r, 2 * r, 2 * r, 45, 90, Arc2D.OPEN));
        }
        double d = unit * 0.7;
        g.fill(new java.awt.geom.Ellipse2D.Double(cx - d / 2, cy - d / 2, d, d));
    }

    static BufferedImage banner(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = g(img);
        g.setPaint(new GradientPaint(0, 0, BG2, w, h, BG1));
        g.fillRect(0, 0, w, h);
        // Lay out on a 320x180 base frame, scaled to fit and centered.
        float s = Math.min(w / 320f, h / 180f);
        g.translate((w - 320 * s) / 2, (h - 180 * s) / 2);
        g.scale(s, s);
        g.setPaint(new RadialGradientPaint(new Point.Float(70, 100), 110,
                new float[]{0f, 1f}, new Color[]{new Color(56, 189, 248, 60), new Color(56, 189, 248, 0)}));
        g.fillRect(-200, -200, 720, 580);
        wifi(g, 70, 122, 20);
        // badge
        g.setFont(new Font("SansSerif", Font.BOLD, 13));
        String badge = "5 GHz";
        int bx = 132, by = 52, bw = g.getFontMetrics().stringWidth(badge) + 16, bh = 22;
        g.setColor(new Color(12, 59, 87));
        g.fill(new RoundRectangle2D.Float(bx, by, bw, bh, bh, bh));
        g.setColor(ACCENT);
        g.drawString(badge, bx + 8, by + 16);
        g.setColor(TEXT);
        g.setFont(new Font("SansSerif", Font.BOLD, 27));
        g.drawString("Wi-Fi Hotspot", bx, 106);
        g.setColor(TEXT2);
        g.setFont(new Font("SansSerif", Font.PLAIN, 13));
        g.drawString("Ethernet → Wi-Fi · no proxy", bx + 1, 128);
        g.dispose();
        return img;
    }

    static BufferedImage icon(int size, boolean rounded) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = g(img);
        float r = rounded ? size * 0.22f : 0;
        g.setPaint(new GradientPaint(0, 0, BG2, size, size, BG1));
        g.fill(new RoundRectangle2D.Float(0, 0, size, size, r * 2, r * 2));
        wifi(g, size / 2.0, size * 0.66, size * 0.135);
        g.dispose();
        return img;
    }

    static void write(BufferedImage img, String path) throws Exception {
        File f = new File(path);
        f.getParentFile().mkdirs();
        ImageIO.write(img, "png", f);
    }
}
