package studio.flame;

import java.util.SplittableRandom;

enum Variation {
    LINEAR, SINUSOIDAL, SPHERICAL, SWIRL, HORSESHOE, POLAR, HANDKERCHIEF,
    DISC, JULIA, JULIAN, BUBBLE, EYEFISH, CURL;

    void apply(double x, double y, SplittableRandom random, double[] out) {
        double r2 = x * x + y * y;
        double r = Math.sqrt(r2);
        double angle = Math.atan2(y, x);
        switch (this) {
            case LINEAR -> { out[0] = x; out[1] = y; }
            case SINUSOIDAL -> { out[0] = Math.sin(x); out[1] = Math.sin(y); }
            case SPHERICAL -> { double d = 1 / Math.max(r2, 1e-12); out[0] = x * d; out[1] = y * d; }
            case SWIRL -> { double s = Math.sin(r2), c = Math.cos(r2); out[0] = x * s - y * c; out[1] = x * c + y * s; }
            case HORSESHOE -> { double d = 1 / Math.max(r, 1e-12); out[0] = (x - y) * (x + y) * d; out[1] = 2 * x * y * d; }
            case POLAR -> { out[0] = angle / Math.PI; out[1] = r - 1; }
            case HANDKERCHIEF -> { out[0] = r * Math.sin(angle + r); out[1] = r * Math.cos(angle - r); }
            case DISC -> { double a = angle / Math.PI; out[0] = a * Math.sin(Math.PI * r); out[1] = a * Math.cos(Math.PI * r); }
            case JULIA, JULIAN -> {

                int power = this == JULIA ? 2 : 3;
                double a = (angle + 2 * Math.PI * random.nextInt(power)) / power;
                double radius = Math.pow(Math.max(r2, 1e-12), 0.5 / power);
                out[0] = radius * Math.cos(a); out[1] = radius * Math.sin(a);
            }
            case BUBBLE -> { double d = 4 / (r2 + 4); out[0] = x * d; out[1] = y * d; }
            case EYEFISH -> { double d = 2 / (r + 1); out[0] = x * d; out[1] = y * d; }
            case CURL -> {
                double re = 1 + 0.35 * x + 0.12 * (x * x - y * y);
                double im = 0.35 * y + 0.24 * x * y;
                double d = 1 / Math.max(re * re + im * im, 1e-12);
                out[0] = (x * re + y * im) * d; out[1] = (y * re - x * im) * d;
            }
        }
    }
}
