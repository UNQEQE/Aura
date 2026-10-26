package com.example.eliansitowebonsito;

/** Reglas del juego: puntos, multiplicador de combo y rangos de aura. Sin nada de Android. */
public final class AuraGame {

    public static final long COMBO_WINDOW_MS = 7000L;
    public static final int MAX_MULTIPLIER = 10;

    private static final int[] RANK_MIN = {0, 300, 1000, 2500, 5000, 10000};
    private static final String[] RANK_NAME = {
            "NPC", "Novato del aura", "Sigma en prácticas", "Rizzler", "Aura Lord", "AURA INFINITA"
    };

    public static final class Result {
        public final int gained;
        public final int total;
        public final int multiplier;
        public final boolean comboUp;
        public final boolean repeated;
        public final boolean rankUp;

        Result(int gained, int total, int multiplier, boolean comboUp, boolean repeated, boolean rankUp) {
            this.gained = gained;
            this.total = total;
            this.multiplier = multiplier;
            this.comboUp = comboUp;
            this.repeated = repeated;
            this.rankUp = rankUp;
        }
    }

    private int points = 0;
    private int multiplier = 1;
    private AuraMove last = null;
    private long lastTime = 0L;

    public Result register(AuraMove move, long now) {
        boolean inWindow = last != null && now - lastTime <= COMBO_WINDOW_MS;
        boolean comboUp = false;
        boolean repeated = false;
        int rankBefore = rankIndex();

        if (!inWindow) {
            multiplier = 1;
        } else if (move != last) {
            if (multiplier < MAX_MULTIPLIER) {
                multiplier++;
                comboUp = true;
            }
        } else {
            repeated = true;                      // repetir el mismo movimiento rinde la mitad
        }

        int gained = move.basePoints * multiplier;
        if (repeated) gained = Math.max(1, gained / 2);

        points += gained;
        last = move;
        lastTime = now;
        return new Result(gained, points, multiplier, comboUp, repeated, rankIndex() > rankBefore);
    }

    /** @return true si el combo acaba de vencerse (para actualizar la UI una sola vez). */
    public boolean tick(long now) {
        if (multiplier > 1 && now - lastTime > COMBO_WINDOW_MS) {
            multiplier = 1;
            last = null;
            return true;
        }
        return false;
    }

    /** Tiempo restante del combo, 1 = recién hecho, 0 = vencido. */
    public float comboRemaining(long now) {
        if (last == null) return 0f;
        float r = 1f - (now - lastTime) / (float) COMBO_WINDOW_MS;
        return Math.max(0f, Math.min(1f, r));
    }

    public void reset() {
        points = 0;
        multiplier = 1;
        last = null;
        lastTime = 0L;
    }

    public int getPoints() { return points; }

    public int getMultiplier() { return multiplier; }

    public AuraMove getLastMove() { return last; }

    public int rankIndex() {
        int idx = 0;
        for (int i = 0; i < RANK_MIN.length; i++) if (points >= RANK_MIN[i]) idx = i;
        return idx;
    }

    public String rankName() { return RANK_NAME[rankIndex()]; }

    /** Progreso (0..1) hacia el siguiente rango. */
    public float rankProgress() {
        int i = rankIndex();
        if (i >= RANK_MIN.length - 1) return 1f;
        return (points - RANK_MIN[i]) / (float) (RANK_MIN[i + 1] - RANK_MIN[i]);
    }

    public int nextRankPoints() {
        int i = rankIndex();
        return i >= RANK_MIN.length - 1 ? RANK_MIN[RANK_MIN.length - 1] : RANK_MIN[i + 1];
    }
}
