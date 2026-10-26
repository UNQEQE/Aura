package com.example.eliansitowebonsito;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Base de datos local (SQLite) con las cuentas y los puntajes máximos.
 *
 * - La contraseña NUNCA se guarda: solo un hash PBKDF2 con sal propia de cada usuario.
 * - Después de 5 intentos fallidos la cuenta se bloquea 30 segundos (frena el adivinar la clave).
 * - Los datos viven en este celular: protege tu cuenta de otras personas que usen el mismo teléfono.
 *
 * Todos los métodos son bloqueantes y rápidos, pero llámalos fuera del hilo de UI cuando puedas
 * (recordMove ya lo hace MainActivity en un hilo aparte).
 */
public final class AuraDb extends SQLiteOpenHelper {

    private static final String DB_NAME = "aura_farm.db";
    private static final int DB_VERSION = 1;

    public static final int NAME_MIN = 3, NAME_MAX = 16, PASS_MIN = 4;
    private static final int PBKDF2_ITERATIONS = 10000;
    private static final int MAX_FAILS = 5;
    private static final long LOCK_MS = 30_000L;

    /** Cuenta ya autenticada. */
    public static final class User {
        public final long id;
        public final String name;
        public final int bestScore;
        public final int bestCombo;

        User(long id, String name, int bestScore, int bestCombo) {
            this.id = id;
            this.name = name;
            this.bestScore = bestScore;
            this.bestCombo = bestCombo;
        }
    }

    /** Una fila del ranking. */
    public static final class Entry {
        public final long id;
        public final String name;
        public final int bestScore;
        public final int bestCombo;

        Entry(long id, String name, int bestScore, int bestCombo) {
            this.id = id;
            this.name = name;
            this.bestScore = bestScore;
            this.bestCombo = bestCombo;
        }
    }

    /** Resultado de registrar / entrar: o hay usuario, o hay un mensaje de error para mostrar. */
    public static final class AuthResult {
        public final User user;
        public final String error;

        AuthResult(User user, String error) {
            this.user = user;
            this.error = error;
        }

        public boolean ok() {
            return user != null;
        }
    }

    private static AuraDb instance;

    public static synchronized AuraDb get(Context ctx) {
        if (instance == null) instance = new AuraDb(ctx.getApplicationContext());
        return instance;
    }

    private final SecureRandom random = new SecureRandom();

    private AuraDb(Context ctx) {
        super(ctx, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE users ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "name TEXT NOT NULL UNIQUE COLLATE NOCASE,"      // "Eli" y "eli" son el mismo usuario
                + "salt BLOB NOT NULL,"
                + "hash BLOB NOT NULL,"
                + "created_at INTEGER NOT NULL,"
                + "best_score INTEGER NOT NULL DEFAULT 0,"
                + "best_combo INTEGER NOT NULL DEFAULT 1,"
                + "games INTEGER NOT NULL DEFAULT 0,"
                + "total_points INTEGER NOT NULL DEFAULT 0,"
                + "fails INTEGER NOT NULL DEFAULT 0,"
                + "locked_until INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_users_best ON users(best_score DESC)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Versión 1: nada que migrar todavía. Cuando cambies el esquema, súbele DB_VERSION y migra aquí.
    }

    // ------------------------------------------------------------------ validación

    /** @return mensaje de error, o null si el nombre es válido. */
    public static String validateName(String name) {
        if (name == null) return "Escribe un nombre";
        if (name.length() < NAME_MIN) return "El nombre debe tener al menos " + NAME_MIN + " letras";
        if (name.length() > NAME_MAX) return "El nombre puede tener máximo " + NAME_MAX + " caracteres";
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == ' ')) {
                return "Usa solo letras, números, espacio, _ . -";
            }
        }
        return null;
    }

    public static String cleanName(String raw) {
        return raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
    }

    // ------------------------------------------------------------------ cuentas

    public synchronized AuthResult register(String rawName, String password) {
        String name = cleanName(rawName);
        String err = validateName(name);
        if (err != null) return new AuthResult(null, err);
        if (password == null || password.length() < PASS_MIN) {
            return new AuthResult(null, "La contraseña debe tener al menos " + PASS_MIN + " caracteres");
        }

        SQLiteDatabase db = getWritableDatabase();
        if (findIdByName(db, name) >= 0) return new AuthResult(null, "Ese nombre ya está en uso, elige otro");

        byte[] salt = new byte[16];
        random.nextBytes(salt);
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        cv.put("salt", salt);
        cv.put("hash", hash(password, salt));
        cv.put("created_at", System.currentTimeMillis());
        long id = db.insert("users", null, cv);
        if (id < 0) return new AuthResult(null, "No se pudo crear la cuenta, intenta de nuevo");
        return new AuthResult(new User(id, name, 0, 1), null);
    }

    public synchronized AuthResult login(String rawName, String password) {
        String name = cleanName(rawName);
        if (name.isEmpty() || password == null || password.isEmpty()) {
            return new AuthResult(null, "Escribe tu nombre y tu contraseña");
        }
        SQLiteDatabase db = getWritableDatabase();
        long now = System.currentTimeMillis();

        try (Cursor c = db.rawQuery(
                "SELECT id, name, salt, hash, best_score, best_combo, fails, locked_until FROM users WHERE name = ?",
                new String[]{name})) {
            if (!c.moveToFirst()) return new AuthResult(null, "Usuario o contraseña incorrectos");

            long id = c.getLong(0);
            long lockedUntil = c.getLong(7);
            if (lockedUntil > now) {
                long secs = (lockedUntil - now + 999) / 1000;
                return new AuthResult(null, "Demasiados intentos. Espera " + secs + " s");
            }

            byte[] expected = c.getBlob(3);
            if (MessageDigest.isEqual(expected, hash(password, c.getBlob(2)))) {   // comparación en tiempo constante
                db.execSQL("UPDATE users SET fails = 0, locked_until = 0 WHERE id = ?", new Object[]{id});
                return new AuthResult(new User(id, c.getString(1), c.getInt(4), c.getInt(5)), null);
            }

            int fails = c.getInt(6) + 1;
            if (fails >= MAX_FAILS) {
                db.execSQL("UPDATE users SET fails = 0, locked_until = ? WHERE id = ?", new Object[]{now + LOCK_MS, id});
                return new AuthResult(null, "Demasiados intentos. Cuenta bloqueada 30 s");
            }
            db.execSQL("UPDATE users SET fails = ? WHERE id = ?", new Object[]{fails, id});
            return new AuthResult(null, "Usuario o contraseña incorrectos (" + (MAX_FAILS - fails) + " intentos restantes)");
        }
    }

    public synchronized User getUser(long id) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, name, best_score, best_combo FROM users WHERE id = ?", new String[]{String.valueOf(id)})) {
            return c.moveToFirst() ? new User(c.getLong(0), c.getString(1), c.getInt(2), c.getInt(3)) : null;
        }
    }

    // ------------------------------------------------------------------ puntajes

    /** Una partida nueva empezó con esta cuenta. */
    public synchronized void recordGameStart(long userId) {
        getWritableDatabase().execSQL("UPDATE users SET games = games + 1 WHERE id = ?", new Object[]{userId});
    }

    /**
     * Un movimiento puntuó. Actualiza el récord personal (solo si lo supera) y el mejor combo.
     * @param gained  puntos que dio este movimiento
     * @param total   puntaje total de la partida actual
     * @param multiplier multiplicador de combo actual
     */
    public synchronized void recordMove(long userId, int gained, int total, int multiplier) {
        getWritableDatabase().execSQL(
                "UPDATE users SET total_points = total_points + ?,"
                        + " best_score = MAX(best_score, ?), best_combo = MAX(best_combo, ?) WHERE id = ?",
                new Object[]{gained, total, multiplier, userId});
    }

    /** Ranking: mejores puntajes primero (a igualdad, gana quien llegó antes a crear la cuenta). */
    public synchronized List<Entry> topScores(int limit) {
        List<Entry> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, name, best_score, best_combo FROM users WHERE best_score > 0"
                        + " ORDER BY best_score DESC, created_at ASC LIMIT " + Math.max(1, limit), null)) {
            while (c.moveToNext()) out.add(new Entry(c.getLong(0), c.getString(1), c.getInt(2), c.getInt(3)));
        }
        return out;
    }

    /** Puesto de la cuenta en el ranking (1 = primero), o -1 si todavía no tiene puntaje. */
    public synchronized int rankOf(long userId) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT best_score FROM users WHERE id = ?", new String[]{String.valueOf(userId)})) {
            if (!c.moveToFirst() || c.getInt(0) <= 0) return -1;
            int mine = c.getInt(0);
            try (Cursor r = getReadableDatabase().rawQuery(
                    "SELECT COUNT(*) FROM users WHERE best_score > ?", new String[]{String.valueOf(mine)})) {
                r.moveToFirst();
                return r.getInt(0) + 1;
            }
        }
    }

    // ------------------------------------------------------------------ internos

    private static long findIdByName(SQLiteDatabase db, String name) {
        try (Cursor c = db.rawQuery("SELECT id FROM users WHERE name = ?", new String[]{name})) {
            return c.moveToFirst() ? c.getLong(0) : -1L;
        }
    }

    private static byte[] hash(String password, byte[] salt) {
        try {
            // PBKDF2WithHmacSHA1 existe desde API 10 (SHA256 solo desde la 26 y el proyecto usa minSdk 24)
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo calcular el hash", e);
        }
    }

    /** Formato "1.234" para los puntajes. */
    public static String fmt(int n) {
        return String.format(Locale.GERMANY, "%,d", n);
    }
}
