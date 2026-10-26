package com.example.eliansitowebonsito;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.Random;

/**
 * Dibuja sobre la cámara: esqueleto neón, halo de aura, anillo de progreso del movimiento
 * que se está "cargando" y partículas cuando puntúas. Soporta hasta 2 jugadores a la vez,
 * cada uno con su propio color, halo y anillo.
 * No depende de MediaPipe: recibe arrays de landmarks (0..1).
 */
public class OverlayView extends View {

    public static final int MAX_PLAYERS = 2;
    /** Color base de cada jugador: J1 cian, J2 rosa. */
    public static final int[] PLAYER_COLORS = {0xFF00E5FF, 0xFFFF4081};

    private static final int[][] CONNECTIONS = {
            {11, 12},                                   // hombros
            {11, 13}, {13, 15}, {15, 19},               // brazo izq. + mano
            {12, 14}, {14, 16}, {16, 20},               // brazo der. + mano
            {11, 23}, {12, 24}, {23, 24},               // torso
            {23, 25}, {25, 27},                         // pierna izq.
            {24, 26}, {26, 28}                          // pierna der.
    };
    private static final int[] JOINTS = {11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28};
    private static final int MAX_PARTICLES = 200;
    private static final float MIN_DRAW_VIS = 0.35f;

    /** Copia inmutable de un frame (se escribe desde el hilo de MediaPipe, se lee en el hilo de UI). */
    private static final class Frame {
        final float[] x, y, v;
        final int w, h;
        final long time;

        Frame(float[] x, float[] y, float[] v, int w, int h) {
            this.x = x.clone();
            this.y = y.clone();
            this.v = v.clone();
            this.w = w;
            this.h = h;
            this.time = SystemClock.uptimeMillis();
        }
    }

    /** Todo lo que es propio de un jugador. */
    private static final class PlayerState {
        volatile Frame frame;
        volatile int accentTarget;
        volatile int candidateColor = Color.WHITE;
        volatile float candidateProgress = 0f;
        volatile int comboLevel = 1;

        int accent;
        int haloColorApplied = -1;
        final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final float[] sx = new float[33];
        final float[] sy = new float[33];
        float centerX, centerY, torsoPx;

        PlayerState(int color) {
            accent = color;
            accentTarget = color;
            haloPaint.setStyle(Paint.Style.FILL);
            haloPaint.setShader(new RadialGradient(0f, 0f, 1f,
                    new int[]{0xCCFFFFFF, 0x66FFFFFF, 0x00FFFFFF}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        }
    }

    private final PlayerState[] players = new PlayerState[MAX_PLAYERS];
    private int playerCount = 1;
    private volatile boolean skeletonVisible = true;
    private final float density;

    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint jointFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint jointRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringArc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint particlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ringRect = new RectF();
    private final RectF labelRect = new RectF();

    // partículas (solo se tocan desde el hilo de UI)
    private final float[] ppx = new float[MAX_PARTICLES], ppy = new float[MAX_PARTICLES];
    private final float[] pvx = new float[MAX_PARTICLES], pvy = new float[MAX_PARTICLES];
    private final float[] plife = new float[MAX_PARTICLES], pmax = new float[MAX_PARTICLES];
    private final float[] psize = new float[MAX_PARTICLES];
    private final int[] pcolor = new int[MAX_PARTICLES];
    private int pcount = 0;
    private final Random rnd = new Random();
    private long lastDraw = 0L;

    public OverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;

        for (int i = 0; i < MAX_PLAYERS; i++) players[i] = new PlayerState(PLAYER_COLORS[i]);

        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeCap(Paint.Cap.ROUND);
        glowPaint.setStrokeWidth(14f * density);

        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeWidth(4f * density);

        jointFill.setStyle(Paint.Style.FILL);
        jointFill.setColor(Color.WHITE);

        jointRing.setStyle(Paint.Style.STROKE);
        jointRing.setStrokeWidth(2.5f * density);

        ringTrack.setStyle(Paint.Style.STROKE);
        ringTrack.setStrokeWidth(7f * density);
        ringTrack.setColor(0x33FFFFFF);

        ringArc.setStyle(Paint.Style.STROKE);
        ringArc.setStrokeCap(Paint.Cap.ROUND);
        ringArc.setStrokeWidth(7f * density);

        particlePaint.setStyle(Paint.Style.FILL);

        labelBg.setStyle(Paint.Style.FILL);
        labelText.setTextAlign(Paint.Align.CENTER);
        labelText.setFakeBoldText(true);
        labelText.setTextSize(15f * density);
        labelText.setColor(0xFF111111);
    }

    // ---- API pública -------------------------------------------------------

    /** 1 o 2. Con 2 jugadores se dibuja la etiqueta J1 / J2 sobre la cabeza de cada uno. */
    public void setPlayerCount(int count) {
        playerCount = Math.max(1, Math.min(MAX_PLAYERS, count));
        postInvalidateOnAnimation();
    }

    /** Muestra u oculta los huesos, articulaciones y la cabeza. El halo, el anillo y las partículas se siguen viendo. */
    public void setSkeletonVisible(boolean visible) {
        skeletonVisible = visible;
        postInvalidateOnAnimation();
    }

    public boolean isSkeletonVisible() {
        return skeletonVisible;
    }

    /** Llamar desde cualquier hilo. */
    public void setPose(int player, float[] xs, float[] ys, float[] vis, int imgW, int imgH) {
        players[player].frame = new Frame(xs, ys, vis, imgW, imgH);
        postInvalidateOnAnimation();
    }

    public void clearPose(int player) {
        players[player].frame = null;
        postInvalidateOnAnimation();
    }

    /** Color principal del esqueleto de un jugador (se mezcla suavemente). */
    public void setAccent(int player, int color) {
        players[player].accentTarget = color;
    }

    /** Anillo de progreso del movimiento en curso. progress <= 0 lo oculta. */
    public void setCandidate(int player, int color, float progress) {
        players[player].candidateColor = color;
        players[player].candidateProgress = progress;
        postInvalidateOnAnimation();
    }

    /** Multiplicador actual: hace el halo más grande e intenso. */
    public void setComboLevel(int player, int level) {
        players[player].comboLevel = Math.max(1, level);
    }

    /** Explosión de partículas desde el torso de ese jugador. Llamar desde el hilo de UI. */
    public void burst(int player, int color, int count) {
        PlayerState ps = players[player];
        for (int i = 0; i < count && pcount < MAX_PARTICLES; i++) {
            double ang = rnd.nextDouble() * Math.PI * 2;
            float speed = (180f + rnd.nextFloat() * 620f) * density;
            int k = pcount++;
            ppx[k] = ps.centerX + (float) Math.cos(ang) * ps.torsoPx * 0.3f;
            ppy[k] = ps.centerY + (float) Math.sin(ang) * ps.torsoPx * 0.3f;
            pvx[k] = (float) Math.cos(ang) * speed;
            pvy[k] = (float) Math.sin(ang) * speed - 120f * density;
            pmax[k] = plife[k] = 0.7f + rnd.nextFloat() * 0.8f;
            psize[k] = (2.5f + rnd.nextFloat() * 5.5f) * density;
            pcolor[k] = (rnd.nextInt(4) == 0) ? Color.WHITE : color;
        }
        postInvalidateOnAnimation();
    }

    // ---- dibujo ------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long now = SystemClock.uptimeMillis();
        float dt = lastDraw == 0L ? 0.016f : Math.min(0.05f, (now - lastDraw) / 1000f);
        lastDraw = now;

        for (int i = 0; i < playerCount; i++) {
            PlayerState ps = players[i];
            ps.accent = lerpColor(ps.accent, ps.accentTarget, 0.18f);
            Frame f = ps.frame;
            if (f != null && now - f.time < 500L && getWidth() > 0) {
                drawPerson(canvas, ps, f, now, i);
            }
        }
        drawParticles(canvas, dt);

        if (pcount > 0) postInvalidateOnAnimation();
    }

    private void drawPerson(Canvas canvas, PlayerState ps, Frame f, long now, int index) {
        float vw = getWidth(), vh = getHeight();
        // PreviewView usa FILL_CENTER (recorta): el overlay tiene que hacer exactamente lo mismo
        float scale = Math.max(vw / f.w, vh / f.h);
        float ox = (vw - f.w * scale) / 2f;
        float oy = (vh - f.h * scale) / 2f;
        final float[] sx = ps.sx, sy = ps.sy;
        for (int i = 0; i < 33; i++) {
            sx[i] = vw - (ox + f.x[i] * f.w * scale);      // cámara frontal: espejo
            sy[i] = oy + f.y[i] * f.h * scale;
        }

        boolean hips = f.v[23] >= MIN_DRAW_VIS && f.v[24] >= MIN_DRAW_VIS;
        float shx = (sx[11] + sx[12]) / 2f, shy = (sy[11] + sy[12]) / 2f;
        float shoulderW = (float) Math.hypot(sx[11] - sx[12], sy[11] - sy[12]);
        ps.torsoPx = hips ? (float) Math.hypot((sx[23] + sx[24]) / 2f - shx, (sy[23] + sy[24]) / 2f - shy) : shoulderW * 1.35f;
        ps.centerX = shx;
        ps.centerY = shy + ps.torsoPx * 0.45f;

        int accent = ps.accent;
        int combo = ps.comboLevel;

        // 1) halo de aura
        float pulse = 0.85f + 0.15f * (float) Math.sin(now / 260.0);
        float haloR = ps.torsoPx * (1.7f + 0.13f * Math.min(combo, 10)) * (0.95f + 0.05f * pulse);
        int haloAlpha = Math.min(235, (int) ((70 + 16 * combo) * pulse));
        int haloQ = accent & 0x00F0F0F0;                 // cuantizado: evita crear un filtro nuevo por frame
        if (ps.haloColorApplied != haloQ) {
            ps.haloPaint.setColorFilter(new PorterDuffColorFilter(accent | 0xFF000000, PorterDuff.Mode.SRC_IN));
            ps.haloColorApplied = haloQ;
        }
        ps.haloPaint.setAlpha(haloAlpha);
        canvas.save();
        canvas.translate(ps.centerX, ps.centerY);
        canvas.scale(haloR, haloR);
        canvas.drawCircle(0f, 0f, 1f, ps.haloPaint);
        canvas.restore();

        // 2) anillo de progreso del movimiento que se está cargando
        float prog = ps.candidateProgress;
        if (prog > 0.02f) {
            float r = ps.torsoPx * 1.05f;
            ringRect.set(ps.centerX - r, ps.centerY - r, ps.centerX + r, ps.centerY + r);
            ringArc.setColor(ps.candidateColor);
            canvas.drawOval(ringRect, ringTrack);
            canvas.drawArc(ringRect, -90f, 360f * Math.min(1f, prog), false, ringArc);
        }

        float headR = Math.max(shoulderW * 0.30f, 12f * density);
        boolean noseSeen = f.v[0] >= MIN_DRAW_VIS;

        if (skeletonVisible) {
            // 3) huesos: primero un trazo grueso translúcido (glow) y encima el trazo fino
            int glowColor = (accent & 0x00FFFFFF) | 0x55000000;
            int coreColor = lerpColor(accent | 0xFF000000, Color.WHITE, 0.35f);
            glowPaint.setColor(glowColor);
            linePaint.setColor(coreColor);
            for (int[] c : CONNECTIONS) {
                if (f.v[c[0]] < MIN_DRAW_VIS || f.v[c[1]] < MIN_DRAW_VIS) continue;
                canvas.drawLine(sx[c[0]], sy[c[0]], sx[c[1]], sy[c[1]], glowPaint);
            }
            for (int[] c : CONNECTIONS) {
                if (f.v[c[0]] < MIN_DRAW_VIS || f.v[c[1]] < MIN_DRAW_VIS) continue;
                canvas.drawLine(sx[c[0]], sy[c[0]], sx[c[1]], sy[c[1]], linePaint);
            }

            // 4) articulaciones (las manos un poco más grandes) y la cabeza
            jointRing.setColor(accent | 0xFF000000);
            for (int j : JOINTS) {
                if (f.v[j] < MIN_DRAW_VIS) continue;
                float r = (j == 15 || j == 16 ? 7f : 5f) * density;
                canvas.drawCircle(sx[j], sy[j], r, jointFill);
                canvas.drawCircle(sx[j], sy[j], r + 1.5f * density, jointRing);
            }
            if (noseSeen) {
                canvas.drawCircle(sx[0], sy[0], headR, jointRing);
                canvas.drawCircle(sx[0], sy[0], 3f * density, jointFill);
            }
        }

        // 5) etiqueta J1 / J2 sobre la cabeza (solo en modo 2 jugadores)
        if (playerCount > 1) {
            String tag = "J" + (index + 1);
            float tx = noseSeen ? sx[0] : shx;
            float ty = noseSeen ? sy[0] - headR - 18f * density : shy - shoulderW * 0.9f;
            ty = Math.max(ty, 30f * density);
            float halfW = labelText.measureText(tag) / 2f + 9f * density;
            float halfH = 13f * density;
            labelRect.set(tx - halfW, ty - halfH, tx + halfW, ty + halfH);
            labelBg.setColor(PLAYER_COLORS[index]);
            canvas.drawRoundRect(labelRect, halfH, halfH, labelBg);
            canvas.drawText(tag, tx, ty + labelText.getTextSize() * 0.35f, labelText);
        }
    }

    private void drawParticles(Canvas canvas, float dt) {
        int i = 0;
        while (i < pcount) {
            plife[i] -= dt;
            if (plife[i] <= 0f) {           // quitar: mover el último a esta posición
                int last = --pcount;
                ppx[i] = ppx[last]; ppy[i] = ppy[last]; pvx[i] = pvx[last]; pvy[i] = pvy[last];
                plife[i] = plife[last]; pmax[i] = pmax[last]; psize[i] = psize[last]; pcolor[i] = pcolor[last];
                continue;
            }
            pvx[i] *= 0.97f;
            pvy[i] = pvy[i] * 0.97f - 90f * density * dt;   // suben suavemente como "aura"
            ppx[i] += pvx[i] * dt;
            ppy[i] += pvy[i] * dt;
            float k = plife[i] / pmax[i];
            particlePaint.setColor(pcolor[i]);
            particlePaint.setAlpha((int) (255 * Math.min(1f, k * 1.6f)));
            canvas.drawCircle(ppx[i], ppy[i], psize[i] * (0.4f + 0.6f * k), particlePaint);
            i++;
        }
    }

    private static int lerpColor(int a, int b, float t) {
        int aa = (int) (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t);
        int rr = (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t);
        int gg = (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t);
        int bb = (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t);
        return Color.argb(aa, rr, gg, bb);
    }
}
