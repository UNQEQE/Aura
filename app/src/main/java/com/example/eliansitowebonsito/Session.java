package com.example.eliansitowebonsito;

import android.content.Context;
import android.content.SharedPreferences;

/** Quién tiene la sesión abierta en este momento (vive solo mientras no cierres sesión). */
public final class Session {

    private static final String PREFS = "aura_session";
    private static final String K_ID = "user_id";
    private static final String K_NAME = "user_name";
    private static final String K_LAST = "last_name";

    private Session() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void start(Context c, AuraDb.User u) {
        prefs(c).edit().putLong(K_ID, u.id).putString(K_NAME, u.name).putString(K_LAST, u.name).apply();
    }

    /** Cierra sesión pero recuerda el nombre para dejarlo escrito en el login. */
    public static void end(Context c) {
        prefs(c).edit().remove(K_ID).remove(K_NAME).apply();
    }

    public static boolean active(Context c) {
        return prefs(c).getLong(K_ID, -1L) >= 0;
    }

    public static long id(Context c) {
        return prefs(c).getLong(K_ID, -1L);
    }

    public static String name(Context c) {
        return prefs(c).getString(K_NAME, "");
    }

    public static String lastName(Context c) {
        return prefs(c).getString(K_LAST, "");
    }
}
