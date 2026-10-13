package studio.flame;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.StringReader;
import java.nio.file.*;
import java.util.*;

public final class RendererTests {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        double[] out = new double[2];
        Variation.SPHERICAL.apply(2, 0, new SplittableRandom(1), out);
        check(Math.abs(out[0] - 0.5) < 1e-12 && out[1] == 0, "Spherical map");
        for (Variation v : Variation.values()) {
            v.apply(0, 0, new SplittableRandom(1), out);
            check(Double.isFinite(out[0]) && Double.isFinite(out[1]), v + " must handle the origin");
        }
        Set<Integer> hashes = new HashSet<>();
        for (String id : Preset.IDS) {
            Preset p = Preset.load(id);
            RenderSettings s = new RenderSettings(320, 200, 220_000, 2, p.seed, 2);
            List<Integer> progress = new ArrayList<>();
            FlameRenderer.Result result = new FlameRenderer().render(p, s, progress::add);
            check(result.image().getWidth() == 320 && result.image().getHeight() == 200, id + " dimensions");
            int[] pixels = result.image().getRGB(0, 0, 320, 200, null, 0, 320);
            long occupied = Arrays.stream(pixels).filter(rgb -> (rgb & 0xffffff) != 0x03050c).count();
            check(occupied > 500, id + " nonempty artwork, occupied=" + occupied);
            check(result.plottedPoints() > s.samples() / 3, id + " camera fits attractor");
            check(progress.get(0) == 0 && progress.get(progress.size() - 1) == 100, id + " progress endpoints");
            for (int i = 1; i < progress.size(); i++) check(progress.get(i) >= progress.get(i - 1), "progress monotonic");
            hashes.add(Arrays.hashCode(pixels));
        }
        check(hashes.size() == Preset.IDS.size(), "Presets must produce distinct images");
        Preset phoenix = Preset.load("phoenix");
        RenderSettings s = new RenderSettings(256, 160, 160_000, 1, 42, 2);
        BufferedImage a = new FlameRenderer().render(phoenix, s, n -> {}).image();
        BufferedImage b = new FlameRenderer().render(phoenix, s, n -> {}).image();
        check(Arrays.equals(pixels(a), pixels(b)), "Identical seed/settings are reproducible");
        BufferedImage c = new FlameRenderer().render(phoenix, new RenderSettings(256, 160, 160_000, 1, 43, 2), n -> {}).image();
        check(!Arrays.equals(pixels(a), pixels(c)), "Seed changes the sampled artwork");
        Path temporary = Files.createTempDirectory("flame-tests-");
        try {
            Path png = temporary.resolve("image.png"); Main.writeImage(a, png);
            check(Arrays.equals(pixels(a), pixels(ImageIO.read(png.toFile()))), "Lossless PNG round trip");
            expectFailure(() -> Main.writeImage(b, png), "Existing PNG must not be overwritten");
        } finally {
            Files.deleteIfExists(temporary.resolve("image.png")); Files.deleteIfExists(temporary);
        }
        expectFailure(() -> new RenderSettings(0, 100, 10000, 1, 1, 1), "Invalid size");
        expectFailure(() -> new RenderSettings(100, 100, 0, 1, 1, 1), "Invalid sample count");
        expectFailure(() -> Preset.read(new StringReader("transforms=1\ntransform.0.variations=nonexistent:1")), "Unknown variation");
        expectFailure(() -> Preset.read(new StringReader("gamma=NaN")), "Nonfinite configuration");
        expectFailure(() -> Preset.read(new StringReader("symmetry=2.5")), "Fractional symmetry");
        expectFailure(() -> Main.parse(new String[]{"--unknown"}), "Unknown option");
        expectFailure(() -> Main.parse(new String[]{"--width"}), "Missing option value");
        expectFailure(() -> Main.parse(new String[]{"--seed", "1", "--seed", "2"}), "Duplicate option");
        Thread.currentThread().interrupt();
        try {
            new FlameRenderer().render(phoenix, s, n -> {});
            throw new AssertionError("Interrupted rendering must stop");
        } catch (InterruptedException expected) { assertions++; }
        finally { Thread.interrupted(); }
        System.out.println("Renderer integration checks passed: " + assertions);
    }
    private static int[] pixels(BufferedImage image) { return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth()); }
    private static void check(boolean success, String message) { assertions++; if (!success) throw new AssertionError(message); }
    @FunctionalInterface interface Action { void run() throws Exception; }
    private static void expectFailure(Action action, String message) throws Exception {
        try { action.run(); } catch (IllegalArgumentException | java.io.IOException expected) { assertions++; return; }
        throw new AssertionError(message);
    }
}
