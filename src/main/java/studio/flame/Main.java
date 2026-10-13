package studio.flame;

import javax.imageio.ImageIO;
import java.awt.Desktop;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class Main {
    private static final Set<String> VALUES = Set.of("preset", "config", "quality", "width", "height", "samples", "ss", "seed", "threads", "out", "output-dir");
    private static final Set<String> FLAGS = Set.of("help", "list", "all", "no-open");

    public static void main(String[] args) {
        try { execute(args); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); System.err.println("\nРендер остановлен."); System.exit(130); }
        catch (OutOfMemoryError e) { System.err.println("Недостаточно памяти: уменьшите качество или запустите с -Xmx4g."); System.exit(1); }
        catch (Exception e) { System.err.println("\nОшибка: " + e.getMessage()); System.err.println("Справка: --help"); System.exit(1); }
    }

    static void execute(String[] args) throws IOException, InterruptedException {
        Map<String, String> options = parse(args);
        if (options.containsKey("help")) { help(); return; }
        if (options.containsKey("list")) { menu(); return; }
        if (options.containsKey("preset") && options.containsKey("config")) throw new IllegalArgumentException("Выберите --preset или --config");
        if (options.containsKey("all") && (options.containsKey("preset") || options.containsKey("config") || options.containsKey("out"))) throw new IllegalArgumentException("--all используется с --output-dir, без --preset/--config/--out");
        if (options.containsKey("all")) {
            for (String id : Preset.IDS) render(Preset.load(id), options);
            return;
        }
        Preset preset;
        if (options.containsKey("config")) preset = Preset.read(Path.of(options.get("config")));
        else if (options.containsKey("preset")) preset = Preset.load(options.get("preset"));
        else {
            menu();
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            while (true) {
                System.out.print("  Номер [1–6], Enter = Феникс, 0 = выход: "); System.out.flush();
                String input = in.readLine();
                if (input == null || input.trim().equals("0")) return;
                input = input.trim();
                try {
                    int choice = input.isEmpty() ? 1 : Integer.parseInt(input);
                    if (choice < 1 || choice > Preset.IDS.size()) { System.out.println("  Введите число от 1 до 6."); continue; }
                    preset = Preset.load(Preset.IDS.get(choice - 1)); break;
                } catch (NumberFormatException e) { System.out.println("  Введите число от 1 до 6."); }
            }
        }
        render(preset, options);
    }

    static Map<String, String> parse(String[] args) {
        Map<String, String> result = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) throw new IllegalArgumentException("Ожидался параметр --имя: " + args[i]);
            String name = args[i].substring(2);
            if (result.containsKey(name)) throw new IllegalArgumentException("Повторный параметр --" + name);
            if (FLAGS.contains(name)) result.put(name, "true");
            else if (VALUES.contains(name)) {
                if (++i >= args.length || args[i].startsWith("--")) throw new IllegalArgumentException("Нужно значение после --" + name);
                result.put(name, args[i]);
            } else throw new IllegalArgumentException("Неизвестный параметр --" + name);
        }
        return result;
    }

    private static void menu() throws IOException {
        System.out.println("\n  ╔══════════════════════════════════════════════════╗");
        System.out.println("  ║              F L A M E  S T U D I O             ║");
        System.out.println("  ║          Свет, рождающийся из математики         ║");
        System.out.println("  ╚══════════════════════════════════════════════════╝\n");
        for (int i = 0; i < Preset.IDS.size(); i++) {
            Preset p = Preset.load(Preset.IDS.get(i));
            System.out.printf("  %d  %-18s %s%n", i + 1, p.name, p.description);
        }
        System.out.println("\n  Выбор запускает рендер сразу. PNG появится в папке renders.\n");
    }

    private static RenderSettings settings(Preset p, Map<String, String> o) {
        RenderSettings base = RenderSettings.quality(o.getOrDefault("quality", "standard"), p.seed);

        boolean snapshot = !o.containsKey("quality");
        return new RenderSettings(
                integer(o, p, "width", base.width(), snapshot), integer(o, p, "height", base.height(), snapshot),
                longValue(o, p, "samples", base.samples(), snapshot), integer(o, p, "ss", base.supersampling(), snapshot),
                longValue(o, p, "seed", base.seed(), snapshot), integer(o, p, "threads", base.threads(), snapshot));
    }

    private static int integer(Map<String, String> o, Preset p, String key, int fallback, boolean snapshot) {
        return Math.toIntExact(longValue(o, p, key, fallback, snapshot));
    }
    private static long longValue(Map<String, String> o, Preset p, String key, long fallback, boolean snapshot) {
        return Long.parseLong(o.getOrDefault(key, snapshot ? p.properties.getProperty("render." + key, Long.toString(fallback)) : Long.toString(fallback)));
    }

    private static void render(Preset p, Map<String, String> options) throws IOException, InterruptedException {
        RenderSettings settings = settings(p, options);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
        Path out = options.containsKey("out") ? Path.of(options.get("out")) : Path.of(options.getOrDefault("output-dir", "renders"), p.id + "-" + stamp + ".png");
        out = out.toAbsolutePath().normalize();
        if (!out.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")) throw new IllegalArgumentException("Выходной файл должен иметь расширение .png");
        Path sidecar = out.resolveSibling(out.getFileName().toString().replaceFirst("(?i)\\.png$", ".properties"));
        if (Files.exists(out) || Files.exists(sidecar)) throw new IOException("Файл уже существует: " + out + ". Укажите другое имя.");
        Files.createDirectories(out.getParent());
        System.out.printf("\n  %s · %d × %d · %,d итераций · seed %d%n", p.name, settings.width(), settings.height(), settings.samples(), settings.seed());
        FlameRenderer.Result result = new FlameRenderer().render(p, settings, percent -> {
            int filled = percent / 4;
            System.out.print("\r  [" + "█".repeat(filled) + "·".repeat(25 - filled) + "] " + percent + "%");
            System.out.flush();
        });
        System.out.printf("%n  Рендер готов за %.1f с · потоков: %d · точек: %,d%n", result.seconds(), result.threads(), result.plottedPoints());
        writeImage(result.image(), out);
        Properties saved = new Properties(); saved.putAll(p.properties);
        saved.setProperty("seed", Long.toString(settings.seed()));
        saved.setProperty("render.width", Integer.toString(settings.width()));
        saved.setProperty("render.height", Integer.toString(settings.height()));
        saved.setProperty("render.samples", Long.toString(settings.samples()));
        saved.setProperty("render.ss", Integer.toString(settings.supersampling()));
        saved.setProperty("render.seed", Long.toString(settings.seed()));
        saved.setProperty("render.threads", Integer.toString(result.threads()));
        saved.setProperty("renderer.version", "1.0.0");
        try (Writer writer = Files.newBufferedWriter(sidecar, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            saved.store(writer, "Flame Studio — reproducible render configuration");
        }
        System.out.println("  PNG:    " + out);
        System.out.println("  Конфиг: " + sidecar);
        if (!options.containsKey("no-open") && !options.containsKey("all")) open(out);
    }

    static void writeImage(java.awt.image.BufferedImage image, Path destination) throws IOException {
        Path temporary = Files.createTempFile(destination.getParent(), ".flame-", ".png");
        try {
            if (!ImageIO.write(image, "png", temporary.toFile())) throw new IOException("PNG-кодировщик недоступен");
            Files.move(temporary, destination);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void open(Path path) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) Desktop.getDesktop().open(path.toFile());
            else System.out.println("  Для просмотра откройте PNG из папки renders.");
        } catch (IOException | RuntimeException e) { System.out.println("  PNG сохранён. Откройте его вручную: " + path); }
    }

    private static void help() {
        System.out.println("""
                Flame Studio — Java fractal flame renderer

                Без аргументов: выберите номер, и PNG будет создан автоматически.
                ./gradlew --quiet run
                java -Xmx2g -jar build/libs/FlameStudio-1.0.0.jar

                --preset phoenix|nebula|lotus|silk|coral|galaxy
                --config путь.properties     свой конфиг или снимок прошлого рендера
                --quality preview|standard|high|ultra  (по умолчанию standard)
                --width N --height N         свой размер, от 64 до 7680
                --samples N                  количество итераций chaos game
                --ss 1|2|3                   сглаживание через supersampling
                --seed N                     воспроизводимый случайный seed
                --threads N                  максимум потоков (по умолчанию до 4)
                --out путь.png               имя PNG; существующий файл не заменяется
                --output-dir папка           папка результата (по умолчанию renders)
                --all                        создать все шесть пресетов
                --no-open                    не открывать PNG автоматически
                --list                       показать список пресетов
                --help                       эта справка

                Пример: ./gradlew --quiet run --args="--preset lotus --quality high"
                Повтор: java -Xmx2g -jar build/libs/FlameStudio-1.0.0.jar --config renders/имя.properties
                Ctrl+C останавливает расчёт. Все вычисления выполняются на CPU в Java.
                """);
    }
}
