package com.example.eliansitowebonsito;

/**
 * Pose del cuerpo en un "sistema de coordenadas corporal":
 *  - origen  : punto medio de los hombros
 *  - unidad  : largo del torso (hombros -> caderas), así no importa qué tan lejos estés de la cámara
 *  - +x      : hacia la IZQUIERDA anatómica de la persona (funciona igual con cámara espejada o no)
 *  - +y      : hacia abajo
 * Además suaviza los puntos (EMA) para quitar el temblor de MediaPipe.
 */
public final class BodyPose {

    public static final int NOSE = 0;
    public static final int L_SH = 11, R_SH = 12;
    public static final int L_EL = 13, R_EL = 14;
    public static final int L_WR = 15, R_WR = 16;
    public static final int L_HIP = 23, R_HIP = 24;
    public static final int L_KNEE = 25, R_KNEE = 26;
    public static final int COUNT = 33;

    /** Visibilidad mínima para fiarse de un punto. */
    public static final float MIN_VIS = 0.40f;

    private static final float SMOOTH = 0.60f;      // 1 = sin suavizado, menor = más suave
    private static final long RESET_GAP_MS = 500L;

    /** Coordenadas normalizadas al cuerpo (ver arriba). */
    public final float[] x = new float[COUNT];
    public final float[] y = new float[COUNT];
    public final float[] vis = new float[COUNT];

    public boolean valid = false;
    public boolean hipsVisible = false;
    public long timeMs = 0L;

    // Estado interno (píxeles suavizados)
    private final float[] sx = new float[COUNT];
    private final float[] sy = new float[COUNT];
    private float scale = 0f;
    private long lastMs = -1L;

    /**
     * @param lx,ly   landmarks de MediaPipe (0..1)
     * @param lv      visibilidad de cada landmark (0..1)
     * @param imgW,imgH tamaño de la imagen analizada (para no deformar los ejes)
     */
    public void update(float[] lx, float[] ly, float[] lv, int imgW, int imgH, long tMs) {
        valid = false;
        timeMs = tMs;

        boolean fresh = lastMs < 0 || tMs - lastMs > RESET_GAP_MS;
        for (int i = 0; i < COUNT; i++) {
            float px = lx[i] * imgW;
            float py = ly[i] * imgH;
            if (fresh) {
                sx[i] = px;
                sy[i] = py;
            } else {
                sx[i] += (px - sx[i]) * SMOOTH;
                sy[i] += (py - sy[i]) * SMOOTH;
            }
            vis[i] = lv[i];
        }
        lastMs = tMs;

        if (vis[L_SH] < MIN_VIS || vis[R_SH] < MIN_VIS) return;

        float mx = (sx[L_SH] + sx[R_SH]) * 0.5f;
        float my = (sy[L_SH] + sy[R_SH]) * 0.5f;
        float sw = dist(sx[L_SH], sy[L_SH], sx[R_SH], sy[R_SH]);
        if (sw < 0.06f * imgW) return;               // muy lejos o de perfil: no hay pose fiable

        float side = (sx[L_SH] >= sx[R_SH]) ? 1f : -1f;

        hipsVisible = vis[L_HIP] >= MIN_VIS && vis[R_HIP] >= MIN_VIS;
        float raw;
        if (hipsVisible) {
            float hx = (sx[L_HIP] + sx[R_HIP]) * 0.5f;
            float hy = (sy[L_HIP] + sy[R_HIP]) * 0.5f;
            raw = dist(mx, my, hx, hy);
            if (hy < my) hipsVisible = false;         // caderas "arriba" de los hombros = ruido
        } else {
            raw = 1.35f * sw;
        }
        if (!hipsVisible) raw = 1.35f * sw;
        raw = Math.max(1.10f * sw, Math.min(1.90f * sw, raw));
        scale = (scale == 0f || fresh) ? raw : scale + (raw - scale) * 0.12f;

        for (int i = 0; i < COUNT; i++) {
            x[i] = (sx[i] - mx) * side / scale;
            y[i] = (sy[i] - my) / scale;
        }
        valid = true;
    }

    public void reset() {
        valid = false;
        lastMs = -1L;
        scale = 0f;
    }

    // ---- helpers para los detectores ------------------------------------

    public boolean seen(int a) { return vis[a] >= MIN_VIS; }

    public boolean seen(int a, int b) { return seen(a) && seen(b); }

    public boolean seen(int a, int b, int c, int d) { return seen(a) && seen(b) && seen(c) && seen(d); }

    public float dist(int a, int b) {
        return dist(x[a], y[a], x[b], y[b]);
    }

    /** Ángulo en grados en el punto b (a-b-c). 180 = brazo totalmente estirado. */
    public float angle(int a, int b, int c) {
        float v1x = x[a] - x[b], v1y = y[a] - y[b];
        float v2x = x[c] - x[b], v2y = y[c] - y[b];
        float n1 = (float) Math.hypot(v1x, v1y);
        float n2 = (float) Math.hypot(v2x, v2y);
        if (n1 < 1e-4f || n2 < 1e-4f) return 0f;
        float cos = (v1x * v2x + v1y * v2y) / (n1 * n2);
        cos = Math.max(-1f, Math.min(1f, cos));
        return (float) Math.toDegrees(Math.acos(cos));
    }

    /** Cuánto se aleja la muñeca/codo del hombro hacia AFUERA del cuerpo (positivo = afuera). */
    public float outward(int shoulder, int point) {
        return (shoulder == L_SH) ? (x[point] - x[shoulder]) : (x[shoulder] - x[point]);
    }

    private static float dist(float ax, float ay, float bx, float by) {
        return (float) Math.hypot(ax - bx, ay - by);
    }
}
