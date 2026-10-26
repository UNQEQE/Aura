package com.example.eliansitowebonsito;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Menú principal: elegir 1 o 2 jugadores, ver el ranking o cerrar sesión. */
public class MenuActivity extends AppCompatActivity {

    private TextView tvUser;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!Session.active(this)) {                 // sin sesión no se juega: de vuelta al login
            goToLogin();
            return;
        }

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_menu);

        // Edge-to-edge: dejar espacio para las barras del sistema
        final View root = findViewById(R.id.menuRoot);
        final int l = root.getPaddingLeft(), t = root.getPaddingTop(), r = root.getPaddingRight(), b = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(l + bars.left, t + bars.top, r + bars.right, b + bars.bottom);
            return insets;
        });

        tvUser = findViewById(R.id.menuUser);
        findViewById(R.id.btnOnePlayer).setOnClickListener(v -> startGame(1, -1L, null));
        findViewById(R.id.btnTwoPlayers).setOnClickListener(v -> askPlayerTwo());
        findViewById(R.id.btnRanking).setOnClickListener(v -> showRanking());
        tvUser.setOnClickListener(v -> showRanking());
        findViewById(R.id.btnLogout).setOnClickListener(v -> {
            Session.end(this);
            goToLogin();
        });

        // Entrada suave, escalonada
        int[] ids = {R.id.menuUserRow, R.id.menuTitle, R.id.menuSubtitle, R.id.btnOnePlayer, R.id.btnTwoPlayers, R.id.menuTip};
        float shift = 32f * getResources().getDisplayMetrics().density;
        for (int i = 0; i < ids.length; i++) {
            View v = findViewById(ids[i]);
            v.setAlpha(0f);
            v.setTranslationY(shift);
            v.animate().alpha(1f).translationY(0f)
                    .setStartDelay(80L * i).setDuration(420L)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (tvUser != null) refreshUser();            // el récord pudo cambiar jugando
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }

    private void goToLogin() {
        Intent i = new Intent(this, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(i);
        finish();
    }

    private void refreshUser() {
        final long id = Session.id(this);
        io.execute(() -> {
            AuraDb.User u = AuraDb.get(this).getUser(id);
            int rank = AuraDb.get(this).rankOf(id);
            if (u == null) {                          // la cuenta ya no existe (datos borrados)
                runOnUiThread(() -> {
                    Session.end(this);
                    goToLogin();
                });
                return;
            }
            final String text = "👤 " + u.name + "  ·  🏆 " + AuraDb.fmt(u.bestScore)
                    + (rank > 0 ? "  (#" + rank + ")" : "");
            runOnUiThread(() -> tvUser.setText(text));
        });
    }

    // ------------------------------------------------------------------ ranking

    private void showRanking() {
        final long me = Session.id(this);
        io.execute(() -> {
            List<AuraDb.Entry> top = AuraDb.get(this).topScores(10);
            final StringBuilder sb = new StringBuilder();
            if (top.isEmpty()) {
                sb.append("Aún no hay puntajes. ¡Sé el primero en farmear aura! ✨");
            } else {
                String[] medals = {"🥇", "🥈", "🥉"};
                for (int i = 0; i < top.size(); i++) {
                    AuraDb.Entry e = top.get(i);
                    sb.append(i < 3 ? medals[i] : (i + 1) + ".").append("  ")
                            .append(e.name).append(e.id == me ? "  ◀ tú" : "").append('\n')
                            .append("      ").append(AuraDb.fmt(e.bestScore)).append(" pts  ·  combo x").append(e.bestCombo)
                            .append(i < top.size() - 1 ? "\n\n" : "");
                }
            }
            runOnUiThread(() -> new AlertDialog.Builder(this)
                    .setTitle("🏆 Mejores puntajes")
                    .setMessage(sb.toString())
                    .setPositiveButton("Cerrar", null)
                    .show());
        });
    }

    // ------------------------------------------------------------------ 2 jugadores

    /** J1 es quien tiene la sesión abierta. J2 entra con su propia cuenta o juega de invitado (sin guardar puntaje). */
    private void askPlayerTwo() {
        final float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * d);
        box.setPadding(pad, (int) (8 * d), pad, 0);

        final EditText etName = new EditText(this);
        etName.setHint("Nombre del Jugador 2");
        etName.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        etName.setSingleLine(true);
        box.addView(etName);

        final EditText etPass = new EditText(this);
        etPass.setHint("Contraseña");
        etPass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etPass.setSingleLine(true);
        box.addView(etPass);

        final TextView err = new TextView(this);
        err.setTextColor(0xFFD32F2F);
        err.setPadding(0, (int) (8 * d), 0, 0);
        err.setVisibility(View.GONE);
        box.addView(err);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("🧍🧍 Jugador 2")
                .setMessage("Jugador 1: " + Session.name(this) + ". El Jugador 2 entra con su cuenta o juega de invitado.")
                .setView(box)
                .setPositiveButton("Entrar", null)               // se asigna abajo para que no cierre si hay error
                .setNeutralButton("Invitado", (dlg, w) -> startGame(2, -1L, "Invitado"))
                .setNegativeButton("Cancelar", null)
                .create();
        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            final String name = etName.getText().toString();
            final String pass = etPass.getText().toString();
            if (AuraDb.cleanName(name).equalsIgnoreCase(Session.name(this))) {
                showError(err, "Ese es el Jugador 1. Usa otra cuenta o entra de invitado");
                return;
            }
            io.execute(() -> {
                final AuraDb.AuthResult res = AuraDb.get(this).login(name, pass);
                runOnUiThread(() -> {
                    if (res.ok()) {
                        dialog.dismiss();
                        startGame(2, res.user.id, res.user.name);
                    } else {
                        etPass.setText("");
                        showError(err, res.error);
                    }
                });
            });
        });
    }

    private static void showError(TextView tv, String msg) {
        tv.setText(msg);
        tv.setVisibility(View.VISIBLE);
    }

    private void startGame(int players, long p2Id, String p2Name) {
        Intent i = new Intent(this, MainActivity.class);
        i.putExtra(MainActivity.EXTRA_PLAYERS, players);
        i.putExtra(MainActivity.EXTRA_P2_ID, p2Id);
        i.putExtra(MainActivity.EXTRA_P2_NAME, p2Name);
        startActivity(i);
    }
}
