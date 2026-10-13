package studio.flame;

public record RenderSettings(int width, int height, long samples, int supersampling, long seed, int threads) {
    public RenderSettings {
        if (width < 64 || height < 64 || width > 7680 || height > 7680) throw new IllegalArgumentException("Размеры: от 64 до 7680 пикселей");
        if ((long) width * height > 40_000_000) throw new IllegalArgumentException("Размер изображения: не более 40 миллионов пикселей");
        if (samples < 10_000 || samples > 2_000_000_000L) throw new IllegalArgumentException("samples: от 10000 до 2000000000");
        if (supersampling < 1 || supersampling > 3) throw new IllegalArgumentException("supersampling: от 1 до 3");
        if (threads < 1 || threads > 32) throw new IllegalArgumentException("threads: от 1 до 32");
    }

    public static RenderSettings quality(String quality, long seed) {
        int threads = Math.min(4, Math.max(1, Runtime.getRuntime().availableProcessors()));
        return switch (quality) {
            case "preview" -> new RenderSettings(960, 600, 2_500_000, 1, seed, threads);
            case "standard" -> new RenderSettings(1600, 1000, 18_000_000, 2, seed, threads);
            case "high" -> new RenderSettings(2560, 1600, 60_000_000, 2, seed, threads);
            case "ultra" -> new RenderSettings(3840, 2400, 160_000_000, 2, seed, threads);
            default -> throw new IllegalArgumentException("Качество: preview, standard, high или ultra");
        };
    }
}
