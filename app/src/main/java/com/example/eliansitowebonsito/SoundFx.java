package com.example.eliansitowebonsito;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

import java.util.EnumMap;

/**
 * Efectos de sonido del juego con SoundPool (latencia mínima; los sonidos se cargan una sola vez al iniciar).
 *
 * Cada movimiento tiene su archivo en res/raw/sfx_<nombre_del_movimiento>.wav.
 * Para cambiar un sonido por uno tuyo, reemplaza el archivo conservando el nombre (.wav, .ogg o .mp3 con el mismo nombre base).
 */
public final class SoundFx {

    private final SoundPool pool;
    private final EnumMap<AuraMove, Integer> moveIds = new EnumMap<>(AuraMove.class);
    private final int comboId;
    private final int rankUpId;
    private volatile boolean muted = false;

    public SoundFx(Context context) {
        Context app = context.getApplicationContext();

        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        pool = new SoundPool.Builder()
                .setMaxStreams(8)                 // movimiento + combo + subida de rango (x2 jugadores) pueden sonar juntos
                .setAudioAttributes(attrs)
                .build();

        moveIds.put(AuraMove.SIX_SEVEN, pool.load(app, R.raw.sfx_six_seven, 1));
        moveIds.put(AuraMove.DAB, pool.load(app, R.raw.sfx_dab, 1));
        moveIds.put(AuraMove.FLOSS, pool.load(app, R.raw.sfx_floss, 1));
        moveIds.put(AuraMove.HYPE, pool.load(app, R.raw.sfx_hype, 1));
        moveIds.put(AuraMove.T_POSE, pool.load(app, R.raw.sfx_t_pose, 1));
        moveIds.put(AuraMove.GIGACHAD, pool.load(app, R.raw.sfx_gigachad, 1));
        moveIds.put(AuraMove.WAKANDA, pool.load(app, R.raw.sfx_wakanda, 1));
        moveIds.put(AuraMove.SIUUU, pool.load(app, R.raw.sfx_siuuu, 1));
        moveIds.put(AuraMove.ALABANZA, pool.load(app, R.raw.sfx_alabanza, 1));
        moveIds.put(AuraMove.PENSADOR, pool.load(app, R.raw.sfx_pensador, 1));
        moveIds.put(AuraMove.CORAZON, pool.load(app, R.raw.sfx_corazon, 1));
        moveIds.put(AuraMove.SALUDO, pool.load(app, R.raw.sfx_saludo, 1));
        moveIds.put(AuraMove.PODER, pool.load(app, R.raw.sfx_poder, 1));
        moveIds.put(AuraMove.ZEN, pool.load(app, R.raw.sfx_zen, 1));
        moveIds.put(AuraMove.PRESENTE, pool.load(app, R.raw.sfx_presente, 1));
        moveIds.put(AuraMove.GRULLA, pool.load(app, R.raw.sfx_grulla, 1));
        moveIds.put(AuraMove.NI_IDEA, pool.load(app, R.raw.sfx_ni_idea, 1));
        moveIds.put(AuraMove.APLAUSO, pool.load(app, R.raw.sfx_aplauso, 1));
        comboId = pool.load(app, R.raw.sfx_combo, 1);
        rankUpId = pool.load(app, R.raw.sfx_rankup, 1);
    }

    /** Sonido propio de cada emote. */
    public void play(AuraMove move) {
        Integer id = moveIds.get(move);
        if (id == null || muted) return;
        pool.play(id, 1f, 1f, 2, 0, 1f);
    }

    /** "Ding" de combo: mientras más alto el multiplicador, más agudo suena. */
    public void playCombo(int multiplier) {
        if (muted) return;
        float rate = Math.min(2f, 0.9f + 0.1f * multiplier);
        pool.play(comboId, 0.8f, 0.8f, 1, 0, rate);
    }

    public void playRankUp() {
        if (muted) return;
        pool.play(rankUpId, 1f, 1f, 3, 0, 1f);
    }

    public void setMuted(boolean muted) {
        this.muted = muted;
        if (muted) pool.autoPause();
        else pool.autoResume();
    }

    public void pause() {
        pool.autoPause();
    }

    public void resume() {
        if (!muted) pool.autoResume();
    }

    public void release() {
        pool.release();
    }
}
