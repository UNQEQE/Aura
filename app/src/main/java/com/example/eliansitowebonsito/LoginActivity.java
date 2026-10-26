package com.example.eliansitowebonsito;

import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Primera pantalla: entrar con tu nombre y contraseña, o crear una cuenta nueva. */
public class LoginActivity extends AppCompatActivity {

    private EditText etName, etPass;
    private TextView tvError, btnLogin, btnRegister;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private boolean busy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_login);

        // Edge-to-edge: espacio para las barras del sistema y para el teclado
        final View root = findViewById(R.id.loginRoot);
        final int l = root.getPaddingLeft(), t = root.getPaddingTop(), r = root.getPaddingRight(), b = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
            v.setPadding(l + bars.left, t + bars.top, r + bars.right, b + bars.bottom);
            return insets;
        });

        etName = findViewById(R.id.etName);
        etPass = findViewById(R.id.etPass);
        tvError = findViewById(R.id.tvLoginError);
        btnLogin = findViewById(R.id.btnLogin);
        btnRegister = findViewById(R.id.btnRegister);

        etName.setText(Session.lastName(this));          // deja escrito el último usuario
        if (etName.length() > 0) etPass.requestFocus();

        btnLogin.setOnClickListener(v -> submit(false));
        btnRegister.setOnClickListener(v -> submit(true));
        etPass.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE
                    || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN)) {
                submit(false);
                return true;
            }
            return false;
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Si el usuario cierra sesión desde el menú, vuelve aquí con la clave vacía
        if (!Session.active(this)) etPass.setText("");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }

    private void submit(final boolean register) {
        if (busy) return;
        final String name = etName.getText().toString();
        final String pass = etPass.getText().toString();
        setBusy(true);
        tvError.setVisibility(View.GONE);

        io.execute(() -> {
            AuraDb db = AuraDb.get(this);
            final AuraDb.AuthResult res = register ? db.register(name, pass) : db.login(name, pass);
            runOnUiThread(() -> {
                setBusy(false);
                if (res.ok()) {
                    Session.start(this, res.user);
                    etPass.setText("");
                    startActivity(new Intent(this, MenuActivity.class));
                    finish();
                } else {
                    tvError.setText(res.error);
                    tvError.setVisibility(View.VISIBLE);
                    etPass.setText("");
                }
            });
        });
    }

    private void setBusy(boolean b) {
        busy = b;
        btnLogin.setAlpha(b ? 0.5f : 1f);
        btnRegister.setAlpha(b ? 0.5f : 1f);
    }
}
