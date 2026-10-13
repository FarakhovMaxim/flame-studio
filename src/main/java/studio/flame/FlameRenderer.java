package studio.flame;

import java.awt.image.BufferedImage;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;

public final class FlameRenderer {
    private static final int CHUNK = 65_536;
    private static final int BURN_IN = 40;

    public record Result(BufferedImage image, int threads, long plottedPoints, double seconds, Camera camera) {}
    public record Camera(double centerX, double centerY, double spanX, double spanY) {}

    public Result render(Preset preset, RenderSettings settings, IntConsumer progress) throws InterruptedException {
        long start = System.nanoTime();
        int w = settings.width() * settings.supersampling(), h = settings.height() * settings.supersampling();
        long pixels = (long) w * h;
        if (pixels > Integer.MAX_VALUE - 8) throw new IllegalArgumentException("Слишком большой буфер изображения");
        long bytesPerWorker = pixels * 16;
        long budget = (long) (Runtime.getRuntime().maxMemory() * 0.65) - (long) settings.width() * settings.height() * 16;
        int workers = (int) Math.min(settings.threads(), Math.min((settings.samples() + CHUNK - 1) / CHUNK, budget / bytesPerWorker));
        if (workers < 1) throw new IllegalArgumentException("Недостаточно памяти. Уменьшите размер/supersampling или запустите java -Xmx4g.");
        Camera camera = fitCamera(preset, settings);
        AtomicLong completed = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(workers, task -> {
            Thread thread = new Thread(task, "flame-renderer"); thread.setDaemon(true); return thread;
        });
        List<Future<Histogram>> futures = new ArrayList<>();
        progress.accept(0);
        try {
            for (int i = 0; i < workers; i++) {
                int index = i, count = workers;
                futures.add(pool.submit(() -> sample(preset, settings, camera, index, count, w, h, completed)));
            }
            int last = 0;
            while (completed.get() < settings.samples()) {
                for (Future<Histogram> future : futures) {
                    if (future.isDone()) get(future);
                }
                int percent = (int) Math.min(95, completed.get() * 95 / settings.samples());
                if (percent > last) { progress.accept(percent); last = percent; }
                Thread.sleep(150);
            }
            Histogram sum = get(futures.get(0));
            for (int i = 1; i < futures.size(); i++) sum.add(get(futures.get(i)));

            futures.clear();
            progress.accept(96);
            BufferedImage image = toneMap(sum, preset, settings, w, h);
            progress.accept(100);
            return new Result(image, workers, sum.plotted, (System.nanoTime() - start) / 1e9, camera);
        } finally {
            for (Future<Histogram> future : futures) future.cancel(true);
            pool.shutdownNow();
        }
    }

    private static Histogram get(Future<Histogram> future) throws InterruptedException {
        try { return future.get(); }
        catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException r) throw r;
            if (e.getCause() instanceof Error error) throw error;
            throw new IllegalStateException("Сбой рендера", e.getCause());
        }
    }

    private Camera fitCamera(Preset p, RenderSettings settings) throws InterruptedException {
        int n = 30_000 * p.symmetry;
        double[] xs = new double[n], ys = new double[n], temp = new double[2];
        double[] point = {0.1, -0.1, 0.5};
        SplittableRandom random = new SplittableRandom(settings.seed() ^ 0x6a09e667f3bcc909L);
        double[] cos = rotationCos(p), sin = rotationSin(p);
        int size = 0, warm = BURN_IN;
        for (int i = 0; i < 40_000 && size < n; i++) {
            if ((i & 4095) == 0 && Thread.currentThread().isInterrupted()) throw new InterruptedException();
            p.choose(random).apply(point, random, temp);
            if (!valid(point)) { reset(point, random); warm = BURN_IN; continue; }
            if (warm > 0) { warm--; continue; }
            for (int k = 0; k < p.symmetry && size < n; k++) {
                xs[size] = point[0] * cos[k] - point[1] * sin[k];
                ys[size] = point[0] * sin[k] + point[1] * cos[k]; size++;
            }
        }
        if (size < 100) throw new IllegalArgumentException("Преобразования расходятся. Проверьте коэффициенты в конфиге.");
        Arrays.sort(xs, 0, size); Arrays.sort(ys, 0, size);
        int low = (int) (size * 0.001), high = Math.min(size - 1, (int) (size * 0.999));
        double cx = (xs[low] + xs[high]) / 2, cy = (ys[low] + ys[high]) / 2;
        double sx = Math.max(0.01, xs[high] - xs[low]) * 1.12 / p.zoom;
        double sy = Math.max(0.01, ys[high] - ys[low]) * 1.12 / p.zoom;
        double aspect = (double) settings.width() / settings.height();
        if (sx / sy < aspect) sx = sy * aspect; else sy = sx / aspect;
        return new Camera(cx, cy, sx, sy);
    }

    private Histogram sample(Preset p, RenderSettings s, Camera camera, int worker, int workers,
                             int w, int h, AtomicLong completed) throws InterruptedException {
        Histogram hist = new Histogram(w * h);
        double[] cos = rotationCos(p), sin = rotationSin(p), rgb = new double[3], temp = new double[2];
        double scaleX = w / camera.spanX, scaleY = h / camera.spanY;
        long chunks = (s.samples() + CHUNK - 1) / CHUNK;
        for (long chunk = worker; chunk < chunks; chunk += workers) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();

            SplittableRandom random = new SplittableRandom(mix(s.seed() + chunk * 0x9e3779b97f4a7c15L));
            double[] point = {random.nextDouble(-1, 1), random.nextDouble(-1, 1), random.nextDouble()};
            int count = (int) Math.min(CHUNK, s.samples() - chunk * CHUNK), warm = BURN_IN;
            for (int i = -BURN_IN; i < count; i++) {
                p.choose(random).apply(point, random, temp);
                if (!valid(point)) { reset(point, random); warm = BURN_IN; continue; }
                if (warm > 0) { warm--; continue; }
                p.color(point[2], rgb);
                for (int k = 0; k < p.symmetry; k++) {
                    double x = point[0] * cos[k] - point[1] * sin[k];
                    double y = point[0] * sin[k] + point[1] * cos[k];
                    double px = (x - camera.centerX) * scaleX + w * 0.5;
                    double py = h * 0.5 - (y - camera.centerY) * scaleY;
                    if (px < 0 || py < 0 || px >= w || py >= h) continue;
                    int index = (int) py * w + (int) px;
                    if (hist.hits[index] < Integer.MAX_VALUE) hist.hits[index]++;
                    hist.r[index] += (float) rgb[0]; hist.g[index] += (float) rgb[1]; hist.b[index] += (float) rgb[2];
                    hist.plotted++;
                }
            }
            completed.addAndGet(count);
        }
        return hist;
    }

    private BufferedImage toneMap(Histogram hist, Preset preset, RenderSettings settings, int w, int h) throws InterruptedException {
        int ss = settings.supersampling(), width = settings.width(), height = settings.height();

        int[] buckets = new int[256]; int occupied = 0;
        for (int count : hist.hits) if (count > 0) {
            int bin = Math.min(255, (int) (Math.log1p(count) * 12)); buckets[bin]++; occupied++;
        }
        if (occupied < 20) throw new IllegalArgumentException("Получилась вырожденная фигура. Измените конфиг.");
        int target = (int) (occupied * 0.995), sum = 0, reference = 1;
        for (int i = 0; i < buckets.length; i++) { sum += buckets[i]; if (sum >= target) { reference = i; break; } }
        double logReference = Math.max(1, reference / 12.0 + Math.log(ss * ss));
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            if ((y & 31) == 0 && Thread.currentThread().isInterrupted()) throw new InterruptedException();
            for (int x = 0; x < width; x++) {
                double count = 0, red = 0, green = 0, blue = 0;
                for (int dy = 0; dy < ss; dy++) for (int dx = 0; dx < ss; dx++) {
                    int index = (y * ss + dy) * w + x * ss + dx;
                    count += hist.hits[index]; red += hist.r[index]; green += hist.g[index]; blue += hist.b[index];
                }

                if (count < 5) {
                    for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        int px = x * ss + dx * ss, py = y * ss + dy * ss;
                        if (px < 0 || py < 0 || px >= w || py >= h) continue;
                        int index = py * w + px; double weight = 0.12;
                        count += weight * hist.hits[index]; red += weight * hist.r[index];
                        green += weight * hist.g[index]; blue += weight * hist.b[index];
                    }
                }
                if (count == 0) { row[x] = 0x03050c; continue; }
                double density = Math.min(1.5, Math.log1p(count) / logReference);
                double light = Math.pow(Math.min(1, density * preset.exposure), 1 / preset.gamma);
                double peak = Math.max(1e-8, Math.max(red, Math.max(green, blue)) / count);
                double saturation = preset.vibrancy;
                int r = channel(red / count / peak, light, saturation, 3);
                int g = channel(green / count / peak, light, saturation, 5);
                int b = channel(blue / count / peak, light, saturation, 12);
                row[x] = (r << 16) | (g << 8) | b;
            }
            image.setRGB(0, y, width, 1, row, 0, width);
        }
        return image;
    }

    private static int channel(double color, double light, double vibrancy, int background) {
        double c = Math.pow(Math.max(0, color), 1 / 2.2);
        c = c * vibrancy + (1 - vibrancy) * light;
        return Math.min(255, (int) (background + (255 - background) * c * light));
    }

    private static boolean valid(double[] p) { return Double.isFinite(p[0]) && Double.isFinite(p[1]) && Math.abs(p[0]) < 1e6 && Math.abs(p[1]) < 1e6; }
    private static void reset(double[] p, SplittableRandom r) { p[0] = r.nextDouble(-1, 1); p[1] = r.nextDouble(-1, 1); p[2] = r.nextDouble(); }
    private static long mix(long x) { x = (x ^ (x >>> 30)) * 0xbf58476d1ce4e5b9L; x = (x ^ (x >>> 27)) * 0x94d049bb133111ebL; return x ^ (x >>> 31); }
    private static double[] rotationCos(Preset p) { double[] a = new double[p.symmetry]; for (int k = 0; k < a.length; k++) a[k] = Math.cos(p.rotation + 2 * Math.PI * k / a.length); return a; }
    private static double[] rotationSin(Preset p) { double[] a = new double[p.symmetry]; for (int k = 0; k < a.length; k++) a[k] = Math.sin(p.rotation + 2 * Math.PI * k / a.length); return a; }

    private static final class Histogram {
        final int[] hits; final float[] r, g, b; long plotted;
        Histogram(int pixels) { hits = new int[pixels]; r = new float[pixels]; g = new float[pixels]; b = new float[pixels]; }
        void add(Histogram other) {
            for (int i = 0; i < hits.length; i++) {
                hits[i] = (int) Math.min(Integer.MAX_VALUE, (long) hits[i] + other.hits[i]);
                r[i] += other.r[i]; g[i] += other.g[i]; b[i] += other.b[i];
            }
            plotted += other.plotted;
        }
    }
}
