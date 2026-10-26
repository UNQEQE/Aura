package com.example.eliansitowebonsito;

import static com.example.eliansitowebonsito.BodyPose.L_EL;
import static com.example.eliansitowebonsito.BodyPose.L_HIP;
import static com.example.eliansitowebonsito.BodyPose.L_KNEE;
import static com.example.eliansitowebonsito.BodyPose.L_SH;
import static com.example.eliansitowebonsito.BodyPose.L_WR;
import static com.example.eliansitowebonsito.BodyPose.NOSE;
import static com.example.eliansitowebonsito.BodyPose.R_EL;
import static com.example.eliansitowebonsito.BodyPose.R_HIP;
import static com.example.eliansitowebonsito.BodyPose.R_KNEE;
import static com.example.eliansitowebonsito.BodyPose.R_SH;
import static com.example.eliansitowebonsito.BodyPose.R_WR;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Motor de detección de movimientos.
 *
 * Por qué el anterior detectaba el 6-7 "todo el rato":
 *  - Solo miraba UN frame: cualquier pose con las manos a la altura del pecho ya contaba.
 *  - El 6-7 es un movimiento (manos alternando arriba/abajo), no una pose estática.
 *  - Las distancias eran absolutas (no dependían de qué tan lejos estás) y no se miraba la visibilidad.
 *  - Con cámara frontal el eje X estaba invertido, así que varias condiciones (dab, brazos abiertos) no calzaban.
 *
 * Ahora:
 *  - Poses estáticas (DAB, T-POSE, ...): se deben MANTENER unos cientos de ms y hay que salir de la pose para repetir.
 *  - Movimientos dinámicos (6-7, SWISH SWISH, HYPE): se cuentan oscilaciones reales con histéresis.
 *  - Todo se mide en "largos de torso" (BodyPose), sin importar la distancia ni el espejo de la cámara.
 *
 * Todos los umbrales están en los métodos de abajo: si algo detecta de más o de menos, ajusta ahí.
 */
public final class GestureEngine {

    public interface Listener {
        /** Se completó un movimiento. */
        void onMove(AuraMove move);

        /** Movimiento "cargándose" (progress 0..1). move == null cuando no hay ninguno. */
        void onCandidate(AuraMove move, float progress);
    }

    private static final long GLOBAL_COOLDOWN_MS = 800L;
    private static final long MOVE_COOLDOWN_MS = 1600L;
    private static final long LOST_POSE_MS = 450L;

    private final List<Detector> detectors = new ArrayList<>();
    private final Detector[] all;
    private Listener listener;
    private long globalCooldownUntil = 0L;
    private long lastValidMs = 0L;
    private AuraMove lastCandidate = null;
    private float lastProgress = 0f;

    public GestureEngine() {
        // El orden es la PRIORIDAD cuando dos movimientos se completan en el mismo frame.
        detectors.add(new StaticDetector(AuraMove.DAB, 250, this::dab));
        detectors.add(new FlossDetector());
        detectors.add(new HypeDetector());
        detectors.add(new SixSevenDetector());
        detectors.add(new StaticDetector(AuraMove.WAKANDA, 450, this::wakanda));
        detectors.add(new StaticDetector(AuraMove.GIGACHAD, 450, this::gigachad));
        detectors.add(new StaticDetector(AuraMove.SIUUU, 450, this::siuuu));
        detectors.add(new StaticDetector(AuraMove.T_POSE, 600, this::tPose));
        detectors.add(new StaticDetector(AuraMove.ALABANZA, 500, this::alabanza));
        detectors.add(new StaticDetector(AuraMove.PENSADOR, 800, this::pensador));
        // --- emotes nuevos (van al final: no cambian la prioridad de los anteriores) ---
        detectors.add(new StaticDetector(AuraMove.CORAZON, 500, this::corazon));
        detectors.add(new StaticDetector(AuraMove.SALUDO, 450, this::saludo));
        detectors.add(new StaticDetector(AuraMove.PODER, 600, this::poder));
        detectors.add(new StaticDetector(AuraMove.ZEN, 700, this::zen));
        detectors.add(new StaticDetector(AuraMove.PRESENTE, 500, this::presente));
        detectors.add(new StaticDetector(AuraMove.GRULLA, 600, this::grulla));
        detectors.add(new StaticDetector(AuraMove.NI_IDEA, 500, this::niIdea));
        detectors.add(new ClapDetector());
        all = detectors.toArray(new Detector[0]);
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    /** Llamar en cada frame con la pose ya actualizada. */
    public void process(BodyPose p) {
        long t = p.timeMs;
        if (!p.valid) {
            noPose(t);
            return;
        }
        lastValidMs = t;

        Detector fired = null;
        for (Detector d : all) {
            boolean done = d.update(p, t);
            if (done && fired == null && t >= globalCooldownUntil && t >= d.cooldownUntil) {
                fired = d;
            }
        }

        if (fired != null) {
            fired.cooldownUntil = t + MOVE_COOLDOWN_MS;
            globalCooldownUntil = t + GLOBAL_COOLDOWN_MS;
            for (Detector d : all) d.clear();
            reportCandidate(null, 0f);
            if (listener != null) listener.onMove(fired.move);
            return;
        }

        // Candidato: el movimiento con más avance
        Detector best = null;
        if (t >= globalCooldownUntil) {
            for (Detector d : all) {
                if (t < d.cooldownUntil) continue;
                if (d.progress > 0.20f && (best == null || d.progress > best.progress)) best = d;
            }
        }
        reportCandidate(best == null ? null : best.move, best == null ? 0f : best.progress);
    }

    /** Llamar cuando MediaPipe no ve a nadie. */
    public void noPose(long t) {
        if (t - lastValidMs > LOST_POSE_MS) {
            for (Detector d : all) d.clear();
            reportCandidate(null, 0f);
        }
    }

    private void reportCandidate(AuraMove m, float progress) {
        boolean changed = m != lastCandidate || Math.abs(progress - lastProgress) > 0.06f;
        if (!changed) return;
        lastCandidate = m;
        lastProgress = progress;
        if (listener != null) listener.onCandidate(m, progress);
    }

    // =====================================================================
    //  POSES ESTÁTICAS  (coordenadas en largos de torso, ver BodyPose)
    //  Referencia: hombros y=0 / x=±0.37 · caderas y≈1 · brazo entero ≈ 1.25
    // =====================================================================

    private static boolean armsStraight(BodyPose p, float minDeg) {
        return p.angle(L_SH, L_EL, L_WR) > minDeg && p.angle(R_SH, R_EL, R_WR) > minDeg;
    }

    private static boolean upperBodySeen(BodyPose p) {
        return p.seen(L_SH, R_SH, L_EL, R_EL) && p.seen(L_WR, R_WR);
    }

    /** DAB: un brazo estirado en diagonal hacia arriba + cara metida en el codo del otro. */
    private boolean dab(BodyPose p) {
        if (!upperBodySeen(p)) return false;   // (la nariz no se exige: en el dab queda tapada)
        return dabSide(p, L_SH, L_EL, L_WR, R_SH, R_EL, R_WR)
                || dabSide(p, R_SH, R_EL, R_WR, L_SH, L_EL, L_WR);
    }

    private static boolean dabSide(BodyPose p, int eSh, int eEl, int eWr, int bSh, int bEl, int bWr) {
        boolean extended = p.angle(eSh, eEl, eWr) > 145f
                && p.y[eWr] < p.y[eSh] - 0.35f
                && p.outward(eSh, eWr) > 0.30f;
        boolean tucked = p.dist(NOSE, bEl) < 0.50f
                && p.angle(bSh, bEl, bWr) < 125f
                && p.y[bEl] < p.y[bSh] + 0.15f;
        return extended && tucked;
    }

    /** T-POSE: brazos rectos a la altura de los hombros. */
    private boolean tPose(BodyPose p) {
        if (!upperBodySeen(p) || !armsStraight(p, 150f)) return false;
        if (Math.abs(p.y[L_WR] - p.y[L_SH]) > 0.30f || Math.abs(p.y[R_WR] - p.y[R_SH]) > 0.30f) return false;
        return p.outward(L_SH, L_WR) > 0.80f && p.outward(R_SH, R_WR) > 0.80f;
    }

    /** ALABANZA: ambos brazos estirados hacia arriba, en V. */
    private boolean alabanza(BodyPose p) {
        if (!upperBodySeen(p) || !armsStraight(p, 140f)) return false;
        if (p.y[L_WR] > -0.55f || p.y[R_WR] > -0.55f) return false;
        return p.x[L_WR] - p.x[R_WR] > 0.85f;
    }

    /** GIGACHAD: doble bíceps frontal. */
    private boolean gigachad(BodyPose p) {
        if (!upperBodySeen(p)) return false;
        return flexArm(p, L_SH, L_EL, L_WR) && flexArm(p, R_SH, R_EL, R_WR);
    }

    private static boolean flexArm(BodyPose p, int sh, int el, int wr) {
        return Math.abs(p.y[el] - p.y[sh]) < 0.28f      // codo a la altura del hombro
                && p.outward(sh, el) > 0.25f            // codo hacia afuera
                && p.y[wr] < p.y[el] - 0.22f            // puño más arriba que el codo
                && Math.abs(p.x[wr] - p.x[el]) < 0.45f  // antebrazo casi vertical
                && p.angle(sh, el, wr) < 115f;          // brazo doblado
    }

    /** WAKANDA: brazos cruzados en X, cada muñeca cerca del hombro contrario. */
    private boolean wakanda(BodyPose p) {
        if (!upperBodySeen(p)) return false;
        if (p.x[L_WR] > -0.10f || p.x[R_WR] < 0.10f) return false;   // cruzadas: la izq. pasó al lado derecho y viceversa
        return p.dist(L_WR, R_SH) < 0.50f && p.dist(R_WR, L_SH) < 0.50f
                && p.y[L_WR] > -0.45f && p.y[L_WR] < 0.55f
                && p.y[R_WR] > -0.45f && p.y[R_WR] < 0.55f;
    }

    /** SIUUU: brazos estirados hacia abajo y hacia afuera (pose de Ronaldo al caer). */
    private boolean siuuu(BodyPose p) {
        if (!upperBodySeen(p) || !armsStraight(p, 150f)) return false;
        return p.y[L_WR] > 0.45f && p.y[R_WR] > 0.45f
                && p.y[L_WR] < 1.80f && p.y[R_WR] < 1.80f
                && p.outward(L_SH, L_WR) > 0.55f && p.outward(R_SH, R_WR) > 0.55f;
    }

    /** SIGMA PENSADOR: puño bajo el mentón, la otra mano abajo. */
    private boolean pensador(BodyPose p) {
        if (!upperBodySeen(p)) return false;
        return thinkArm(p, L_WR, L_EL, R_WR) || thinkArm(p, R_WR, R_EL, L_WR);
    }

    private static boolean thinkArm(BodyPose p, int wr, int el, int otherWr) {
        return p.dist(wr, NOSE) < 0.30f
                && p.y[wr] > p.y[NOSE] - 0.02f      // a la altura de la boca o más abajo
                && p.y[el] > p.y[wr] + 0.15f        // codo más abajo que la muñeca
                && p.y[otherWr] > 0.10f;            // la otra mano abajo
    }

    // ---- EMOTES NUEVOS -------------------------------------------------

    /** CORAZÓN: brazos curvos sobre la cabeza, muñecas juntas arriba (forma de corazón/óvalo). */
    private boolean corazon(BodyPose p) {
        if (!upperBodySeen(p)) return false;
        if (p.dist(L_WR, R_WR) > 0.28f) return false;                           // muñecas juntas
        if ((p.y[L_WR] + p.y[R_WR]) * 0.5f > -0.80f) return false;               // por encima de la cabeza
        if (p.y[L_EL] > 0.20f || p.y[R_EL] > 0.20f) return false;                // codos arriba (a altura de hombro o más)
        if (p.outward(L_SH, L_EL) < 0.22f || p.outward(R_SH, R_EL) < 0.22f) return false;   // codos hacia afuera
        return p.angle(L_SH, L_EL, L_WR) < 150f && p.angle(R_SH, R_EL, R_WR) < 150f;        // brazos curvos, no rectos
    }

    /** SALUDO: mano en la frente (militar) con el codo afuera, el otro brazo colgando. */
    private boolean saludo(BodyPose p) {
        if (!upperBodySeen(p) || !p.seen(NOSE)) return false;
        return saluteArm(p, L_SH, L_EL, L_WR, R_WR) || saluteArm(p, R_SH, R_EL, R_WR, L_WR);
    }

    private static boolean saluteArm(BodyPose p, int sh, int el, int wr, int otherWr) {
        float dy = p.y[wr] - p.y[NOSE];
        return dy < 0.02f && dy > -0.50f                       // mano a la altura de la frente
                && p.dist(wr, NOSE) < 0.55f
                && p.outward(sh, el) > 0.30f                   // codo hacia afuera
                && Math.abs(p.y[el] - p.y[sh]) < 0.35f         // codo a la altura del hombro
                && p.angle(sh, el, wr) < 105f                  // brazo doblado
                && p.y[otherWr] > 0.50f;                       // el otro brazo abajo
    }

    /** POSE DE PODER: manos en la cintura, codos hacia afuera (necesita ver las caderas). */
    private boolean poder(BodyPose p) {
        if (!upperBodySeen(p) || !p.hipsVisible) return false;
        return hipArm(p, L_SH, L_EL, L_WR, L_HIP) && hipArm(p, R_SH, R_EL, R_WR, R_HIP);
    }

    private static boolean hipArm(BodyPose p, int sh, int el, int wr, int hip) {
        return p.dist(wr, hip) < 0.35f
                && p.outward(sh, el) > 0.30f
                && p.angle(sh, el, wr) < 120f
                && p.y[el] > 0.25f && p.y[el] < 0.95f;
    }

    /** ZEN (namaste): palmas juntas frente al pecho, codos doblados. */
    private boolean zen(BodyPose p) {
        if (!upperBodySeen(p)) return false;
        float wy = (p.y[L_WR] + p.y[R_WR]) * 0.5f;
        return p.dist(L_WR, R_WR) < 0.22f
                && Math.abs((p.x[L_WR] + p.x[R_WR]) * 0.5f) < 0.22f     // en el centro del cuerpo
                && wy > -0.05f && wy < 0.80f                            // a la altura del pecho
                && p.angle(L_SH, L_EL, L_WR) < 105f && p.angle(R_SH, R_EL, R_WR) < 105f;
    }

    /** PRESENTE: un brazo estirado hacia arriba (mano levantada) y el otro colgando. */
    private boolean presente(BodyPose p) {
        if (!upperBodySeen(p)) return false;
        return raisedHand(p, L_SH, L_EL, L_WR, R_SH, R_WR) || raisedHand(p, R_SH, R_EL, R_WR, L_SH, L_WR);
    }

    private static boolean raisedHand(BodyPose p, int sh, int el, int wr, int otherSh, int otherWr) {
        return p.angle(sh, el, wr) > 145f
                && p.y[wr] < -0.85f
                && Math.abs(p.outward(sh, wr)) < 0.60f                  // casi vertical
                && p.y[otherWr] > 0.50f                                 // el otro brazo abajo
                && p.outward(otherSh, otherWr) < 0.60f;                 // ... y pegado al cuerpo
    }

    /** GRULLA (Karate Kid): una rodilla arriba y la otra pierna apoyada. Necesita cuerpo entero. */
    private boolean grulla(BodyPose p) {
        if (!p.seen(L_HIP, R_HIP, L_KNEE, R_KNEE) || !p.hipsVisible) return false;
        float l = p.y[L_KNEE] - p.y[L_HIP];       // ~0.9 con la pierna apoyada, mucho menos con la rodilla arriba
        float r = p.y[R_KNEE] - p.y[R_HIP];
        return (l < 0.55f && r > 0.75f && r - l > 0.35f) || (r < 0.55f && l > 0.75f && l - r > 0.35f);
    }

    /** NI IDEA (shrug): codos pegados, antebrazos hacia los lados con las palmas abiertas. */
    private boolean niIdea(BodyPose p) {
        if (!upperBodySeen(p)) return false;
        return shrugArm(p, L_SH, L_EL, L_WR) && shrugArm(p, R_SH, R_EL, R_WR);
    }

    private static boolean shrugArm(BodyPose p, int sh, int el, int wr) {
        return p.outward(sh, wr) > 0.50f
                && p.y[wr] > 0.10f && p.y[wr] < 0.85f
                && p.y[el] > 0.20f
                && p.outward(sh, el) > 0.05f
                && p.angle(sh, el, wr) < 135f;
    }

    // =====================================================================
    //  DETECTORES
    // =====================================================================

    private interface PoseTest {
        boolean test(BodyPose p);
    }

    private abstract static class Detector {
        final AuraMove move;
        long cooldownUntil = 0L;
        float progress = 0f;

        Detector(AuraMove move) {
            this.move = move;
        }

        /** @return true si el movimiento se completó en este frame. */
        abstract boolean update(BodyPose p, long t);

        /** Borra el avance (no toca el "rearme" de las poses estáticas). */
        abstract void clear();
    }

    /** Pose que hay que mantener {@code holdMs} y luego salir de ella para poder repetirla. */
    private static final class StaticDetector extends Detector {
        private static final long GRACE_MS = 100L;   // tolera 2-3 frames de parpadeo del tracker, no más
        private static final long REARM_MS = 450L;   // tiempo fuera de la pose para volver a armarse

        private final PoseTest test;
        private final long holdMs;
        private long holdStart = -1L;
        private long lastTrue = -1L;
        private long falseSince = -1L;
        private boolean armed = true;

        StaticDetector(AuraMove move, long holdMs, PoseTest test) {
            super(move);
            this.holdMs = holdMs;
            this.test = test;
        }

        @Override
        boolean update(BodyPose p, long t) {
            if (test.test(p)) {
                falseSince = -1L;
                lastTrue = t;
                if (holdStart < 0) holdStart = t;
                if (!armed) {          // sigue en la pose después de puntuar: no cuenta
                    progress = 0f;
                    return false;
                }
                progress = Math.min(1f, (t - holdStart) / (float) holdMs);
                if (progress >= 1f) {
                    armed = false;
                    progress = 0f;
                    holdStart = -1L;
                    return true;
                }
            } else {
                if (falseSince < 0) falseSince = t;
                if (t - lastTrue > GRACE_MS) {
                    holdStart = -1L;
                    progress = 0f;
                }
                if (t - falseSince > REARM_MS) armed = true;
            }
            return false;
        }

        @Override
        void clear() {
            holdStart = -1L;
            progress = 0f;
        }
    }

    /**
     * Movimiento repetitivo: se cuenta cuántas veces una señal (p.ej. "mano izq. más alta que la der.")
     * cambia de signo con suficiente amplitud (histéresis) dentro de una ventana de tiempo.
     */
    private abstract static class DynamicDetector extends Detector {
        private static final long PAUSE_RESET_MS = 450L;

        private final Oscillator osc;
        private final int needFlips;
        private long lastValid = -1L;

        DynamicDetector(AuraMove move, float threshold, int needFlips, long windowMs, long maxGapMs) {
            super(move);
            this.osc = new Oscillator(threshold, windowMs, maxGapMs);
            this.needFlips = needFlips;
        }

        abstract boolean precondition(BodyPose p);

        abstract float signal(BodyPose p);

        /** Chequeo extra al completar (por defecto, siempre ok). */
        boolean confirm() {
            return true;
        }

        void onReset() {
        }

        @Override
        boolean update(BodyPose p, long t) {
            if (!precondition(p)) {
                if (lastValid >= 0 && t - lastValid > PAUSE_RESET_MS) clear();
                return false;
            }
            lastValid = t;
            int flips = osc.update(signal(p), t);
            progress = Math.min(1f, flips / (float) needFlips);
            if (flips >= needFlips) {
                boolean ok = confirm();
                clear();
                return ok;
            }
            return false;
        }

        @Override
        void clear() {
            osc.reset();
            progress = 0f;
            lastValid = -1L;
            onReset();
        }
    }

    /** 6-7: manos frente al pecho (palmas arriba) subiendo y bajando de forma alternada. */
    private final class SixSevenDetector extends DynamicDetector {
        private final RangeTracker leftY = new RangeTracker(1300L);
        private final RangeTracker rightY = new RangeTracker(1300L);
        private long now;

        SixSevenDetector() {
            super(AuraMove.SIX_SEVEN, 0.14f, 4, 2600L, 700L);
        }

        @Override
        boolean precondition(BodyPose p) {
            now = p.timeMs;
            if (!p.seen(L_SH, R_SH, L_EL, R_EL) || !p.seen(L_WR, R_WR)) return false;
            float ly = p.y[L_WR], ry = p.y[R_WR];
            if (ly < -0.25f || ry < -0.25f || ly > 0.95f || ry > 0.95f) return false;  // zona pecho-abdomen
            if (Math.abs(p.x[L_WR]) > 0.85f || Math.abs(p.x[R_WR]) > 0.85f) return false; // delante del torso
            return p.angle(L_SH, L_EL, L_WR) < 145f && p.angle(R_SH, R_EL, R_WR) < 145f;   // codos doblados
        }

        @Override
        float signal(BodyPose p) {
            leftY.add(now, p.y[L_WR]);
            rightY.add(now, p.y[R_WR]);
            return p.y[L_WR] - p.y[R_WR];
        }

        @Override
        boolean confirm() {
            // Las DOS manos tienen que haberse movido de verdad (no basta con mover una).
            return leftY.range() >= 0.16f && rightY.range() >= 0.16f;
        }

        @Override
        void onReset() {
            leftY.clear();
            rightY.clear();
        }
    }

    /** HYPE (Fortnite): puños arriba y afuera, bombeando alternado. */
    private final class HypeDetector extends DynamicDetector {
        private final RangeTracker leftY = new RangeTracker(1300L);
        private final RangeTracker rightY = new RangeTracker(1300L);
        private long now;

        HypeDetector() {
            super(AuraMove.HYPE, 0.18f, 4, 2600L, 700L);
        }

        @Override
        boolean precondition(BodyPose p) {
            now = p.timeMs;
            if (!p.seen(L_SH, R_SH, L_EL, R_EL) || !p.seen(L_WR, R_WR)) return false;
            float ly = p.y[L_WR], ry = p.y[R_WR];
            if (ly < -1.5f || ry < -1.5f || ly > 0.45f || ry > 0.45f) return false;
            return p.x[L_WR] > 0.45f && p.x[R_WR] < -0.45f;      // manos AFUERA del torso (el 6-7 va al centro)
        }

        @Override
        float signal(BodyPose p) {
            leftY.add(now, p.y[L_WR]);
            rightY.add(now, p.y[R_WR]);
            return p.y[L_WR] - p.y[R_WR];
        }

        @Override
        boolean confirm() {
            return leftY.range() >= 0.20f && rightY.range() >= 0.20f
                    && Math.min(leftY.min(), rightY.min()) < -0.15f;   // en algún momento una mano pasa sobre el hombro
        }

        @Override
        void onReset() {
            leftY.clear();
            rightY.clear();
        }
    }

    /** SWISH SWISH (floss): brazos abajo y juntos, balanceados de lado a lado. */
    private final class FlossDetector extends DynamicDetector {
        FlossDetector() {
            super(AuraMove.FLOSS, 0.30f, 4, 3200L, 900L);
        }

        @Override
        boolean precondition(BodyPose p) {
            if (!p.seen(L_SH, R_SH, L_EL, R_EL) || !p.seen(L_WR, R_WR)) return false;
            if (p.y[L_WR] < 0.40f || p.y[R_WR] < 0.40f || p.y[L_WR] > 2.0f || p.y[R_WR] > 2.0f) return false;
            if (Math.abs(p.x[L_WR] - p.x[R_WR]) > 0.85f) return false;      // manos juntas
            float elbows = (p.angle(L_SH, L_EL, L_WR) + p.angle(R_SH, R_EL, R_WR)) * 0.5f;
            return elbows > 115f;                                             // brazos casi rectos
        }

        @Override
        float signal(BodyPose p) {
            return (p.x[L_WR] + p.x[R_WR]) * 0.5f;                            // centro de las manos, izq <-> der
        }
    }

    /** APLAUSO: las muñecas se juntan y se separan frente al pecho, 3 veces. */
    private static final class ClapDetector extends DynamicDetector {
        ClapDetector() {
            super(AuraMove.APLAUSO, 0.18f, 6, 3000L, 600L);
        }

        @Override
        boolean precondition(BodyPose p) {
            if (!p.seen(L_SH, R_SH, L_EL, R_EL) || !p.seen(L_WR, R_WR)) return false;
            if (p.y[L_WR] < -0.35f || p.y[R_WR] < -0.35f || p.y[L_WR] > 1.0f || p.y[R_WR] > 1.0f) return false;
            if (Math.abs((p.x[L_WR] + p.x[R_WR]) * 0.5f) > 0.45f) return false;            // manos al centro
            return p.angle(L_SH, L_EL, L_WR) < 150f && p.angle(R_SH, R_EL, R_WR) < 150f;   // codos doblados
        }

        @Override
        float signal(BodyPose p) {
            return p.dist(L_WR, R_WR) - 0.45f;      // > +0.18 = manos abiertas · < -0.18 = manos juntas
        }
    }

    // =====================================================================
    //  UTILIDADES
    // =====================================================================

    /** Cuenta cambios de signo con histéresis: +thr -> -thr -> +thr ... */
    private static final class Oscillator {
        private final float threshold;
        private final long windowMs;
        private final long maxGapMs;
        private final ArrayDeque<Long> flips = new ArrayDeque<>();
        private int state = 0;

        Oscillator(float threshold, long windowMs, long maxGapMs) {
            this.threshold = threshold;
            this.windowMs = windowMs;
            this.maxGapMs = maxGapMs;
        }

        int update(float v, long t) {
            int next = state;
            if (v > threshold) next = 1;
            else if (v < -threshold) next = -1;
            if (next != state) {
                if (state != 0) flips.addLast(t);
                state = next;
            }
            while (!flips.isEmpty() && t - flips.peekFirst() > windowMs) flips.pollFirst();
            if (!flips.isEmpty() && t - flips.peekLast() > maxGapMs) flips.clear();
            return flips.size();
        }

        void reset() {
            flips.clear();
            state = 0;
        }
    }

    /** Mínimo / máximo de una señal en los últimos N ms (buffer circular, no genera basura por frame). */
    private static final class RangeTracker {
        private static final int CAP = 96;
        private final long windowMs;
        private final long[] ts = new long[CAP];
        private final float[] vs = new float[CAP];
        private int head = 0;
        private int size = 0;

        RangeTracker(long windowMs) {
            this.windowMs = windowMs;
        }

        void add(long t, float v) {
            if (size == CAP) {                       // lleno: descarta el más viejo
                head = (head + 1) % CAP;
                size--;
            }
            int idx = (head + size) % CAP;
            ts[idx] = t;
            vs[idx] = v;
            size++;
            while (size > 0 && t - ts[head] > windowMs) {
                head = (head + 1) % CAP;
                size--;
            }
        }

        float min() {
            float m = Float.MAX_VALUE;
            for (int i = 0; i < size; i++) m = Math.min(m, vs[(head + i) % CAP]);
            return size == 0 ? 0f : m;
        }

        float max() {
            float m = -Float.MAX_VALUE;
            for (int i = 0; i < size; i++) m = Math.max(m, vs[(head + i) % CAP]);
            return size == 0 ? 0f : m;
        }

        float range() {
            return size == 0 ? 0f : max() - min();
        }

        void clear() {
            head = 0;
            size = 0;
        }
    }
}
