package studio.flame;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class Preset {
    public static final List<String> IDS = List.of("phoenix", "nebula", "lotus", "silk", "coral", "galaxy");
    final String id, name, description;
    final long seed;
    final int symmetry;
    final double gamma, exposure, zoom, rotation, vibrancy;
    final Transform[] transforms;
    final double totalWeight;
    final double[][] palette;
    final Properties properties;

    private Preset(Properties p) {
        properties = new Properties(); properties.putAll(p);
        id = p.getProperty("id", "custom");
        if (!id.matches("[a-zA-Z0-9_-]{1,60}")) throw new IllegalArgumentException("id: только латинские буквы, цифры, _ и -");
        name = p.getProperty("name", id);
        description = p.getProperty("description", "Пользовательский фрактал");
        seed = Long.parseLong(p.getProperty("seed", "42"));
        symmetry = (int) number(p, "symmetry", 1, 1, 16);
        gamma = number(p, "gamma", 2.2, 0.2, 5);
        exposure = number(p, "exposure", 1.1, 0.1, 8);
        zoom = number(p, "zoom", 0.96, 0.2, 4);
        rotation = Math.toRadians(number(p, "rotation", 0, -360, 360));
        vibrancy = number(p, "vibrancy", 0.8, 0, 1);
        String[] colors = p.getProperty("palette", "#101040,#3050aa,#20c8ff,#fff0c0").split(",");
        if (colors.length < 2) throw new IllegalArgumentException("В palette нужно минимум два цвета");
        palette = new double[colors.length][3];
        for (int i = 0; i < colors.length; i++) {
            String hex = colors[i].trim();
            if (!hex.matches("#[0-9a-fA-F]{6}")) throw new IllegalArgumentException("Цвет должен иметь формат #RRGGBB: " + hex);
            int rgb = Integer.parseInt(hex.substring(1), 16);
            for (int j = 0; j < 3; j++) palette[i][j] = Math.pow(((rgb >> (16 - j * 8)) & 255) / 255.0, 2.2);
        }
        int count = (int) number(p, "transforms", 3, 1, 32);
        transforms = new Transform[count];
        double sum = 0;
        for (int i = 0; i < count; i++) { transforms[i] = parseTransform(p, i); sum += transforms[i].weight; }
        totalWeight = sum;
    }

    public static Preset load(String id) throws IOException {
        if (!IDS.contains(id)) throw new IllegalArgumentException("Неизвестный пресет: " + id);
        Path file = Path.of("configs", id + ".properties");
        if (Files.isRegularFile(file)) return read(file);
        try (InputStream stream = Preset.class.getResourceAsStream("/presets/" + id + ".properties")) {
            if (stream == null) throw new IOException("Не найден пресет " + id);
            return read(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
    }

    public static Preset read(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { return read(reader); }
    }

    static Preset read(Reader reader) throws IOException {
        Properties p = new Properties(); p.load(reader);
        try { return new Preset(p); }
        catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Ошибка конфига: " + ex.getMessage(), ex); }
    }

    static double number(Properties p, String key, double fallback, double min, double max) {
        double n = Double.parseDouble(p.getProperty(key, Double.toString(fallback)));
        if (!Double.isFinite(n) || n < min || n > max) throw new IllegalArgumentException(key + ": значение от " + min + " до " + max);
        if ((key.equals("symmetry") || key.equals("transforms")) && n != Math.rint(n)) throw new IllegalArgumentException(key + ": требуется целое число");
        return n;
    }

    private static Transform parseTransform(Properties p, int i) {
        String key = "transform." + i + ".";
        double weight = number(p, key + "weight", 1, 0.001, 100);
        double color = number(p, key + "color", 0.5, 0, 1);
        double speed = number(p, key + "colorSpeed", 0.5, 0, 1);
        double[] affine = coefficients(p.getProperty(key + "affine", "0.6,0,0,0,0.6,0"));
        double[] post = coefficients(p.getProperty(key + "post", "1,0,0,0,1,0"));
        String[] entries = p.getProperty(key + "variations", "linear:1").split(",");
        Variation[] variations = new Variation[entries.length]; double[] amounts = new double[entries.length];
        double sum = 0;
        for (int j = 0; j < entries.length; j++) {
            String[] parts = entries[j].trim().split(":");
            if (parts.length != 2) throw new IllegalArgumentException("variations: ожидается swirl:0.7,linear:0.3");
            variations[j] = Variation.valueOf(parts[0].trim().toUpperCase(Locale.ROOT));
            amounts[j] = Double.parseDouble(parts[1]);
            if (!Double.isFinite(amounts[j]) || amounts[j] < 0 || amounts[j] > 10) throw new IllegalArgumentException("Вес вариации: от 0 до 10");
            sum += amounts[j];
        }
        if (sum <= 0) throw new IllegalArgumentException("Нужна хотя бы одна ненулевая вариация");
        return new Transform(weight, color, speed, affine, post, variations, amounts);
    }

    private static double[] coefficients(String raw) {
        String[] parts = raw.split(",");
        if (parts.length != 6) throw new IllegalArgumentException("affine/post: нужно шесть чисел a,b,c,d,e,f");
        double[] result = new double[6];
        for (int i = 0; i < 6; i++) {
            result[i] = Double.parseDouble(parts[i].trim());
            if (!Double.isFinite(result[i]) || Math.abs(result[i]) > 100) throw new IllegalArgumentException("Коэффициенты: конечные числа от -100 до 100");
        }
        return result;
    }

    Transform choose(SplittableRandom random) {
        double r = random.nextDouble(totalWeight), sum = 0;
        for (Transform transform : transforms) { sum += transform.weight; if (r < sum) return transform; }
        return transforms[transforms.length - 1];
    }

    void color(double c, double[] out) {
        double v = Math.max(0, Math.min(1, c)) * (palette.length - 1);
        int index = Math.min((int) v, palette.length - 2); double f = v - index;
        for (int j = 0; j < 3; j++) out[j] = palette[index][j] * (1 - f) + palette[index + 1][j] * f;
    }

    record Transform(double weight, double color, double colorSpeed, double[] affine, double[] post,
                     Variation[] variations, double[] amounts) {
        void apply(double[] point, SplittableRandom random, double[] temp) {
            double x = affine[0] * point[0] + affine[1] * point[1] + affine[2];
            double y = affine[3] * point[0] + affine[4] * point[1] + affine[5];
            double nx = 0, ny = 0;
            for (int i = 0; i < variations.length; i++) {
                if (amounts[i] == 0) continue;
                variations[i].apply(x, y, random, temp); nx += amounts[i] * temp[0]; ny += amounts[i] * temp[1];
            }
            point[0] = post[0] * nx + post[1] * ny + post[2];
            point[1] = post[3] * nx + post[4] * ny + post[5];
            point[2] += colorSpeed * (color - point[2]);
        }
    }
}
