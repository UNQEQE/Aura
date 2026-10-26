package com.example.eliansitowebonsito;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.text.TextUtils;
import android.util.Log;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.TextViewCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult;

import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity implements TextToSpeech.OnInitListener {

    /** Extra del Intent: 1 o 2 jugadores (lo manda MenuActivity). */
    public static final String EXTRA_PLAYERS = "players";
    /** Cuenta del Jugador 2 (id < 0 = invitado, no guarda puntaje). El Jugador 1 es siempre quien tiene la sesión abierta. */
    public static final String EXTRA_P2_ID = "p2_id";
    public static final String EXTRA_P2_NAME = "p2_name";

    private static final String TAG = "AuraFarm";
    private static final String PREFS = "aura_prefs";
    private static final String PREF_SKELETON = "skeleton_visible";
    private static final String STATUS_IDLE_1P = "Ponte de cuerpo entero a ~2 metros y haz una pose para farmear aura ✨";
    private static final String STATUS_IDLE_2P = "J1 a la izquierda, J2 a la derecha · de cuerpo entero a ~3 metros ✨";

    /** Si la IA va lenta en tu celular, prueba en true (usa la GPU). Si falla, vuelve sola a CPU. */
    private static final boolean USE_GPU_DELEGATE = false;
    /** true = además del sonido, la voz dice el nombre de cada emote. Los combos y subidas de rango siempre se anuncian. */
    private static final boolean VOICE_ON_MOVES = false;
    /** Si MediaPipe no responde en este tiempo, se manda otro frame (evita quedarse esperando). */
    private static final long DETECT_TIMEOUT_MS = 250L;
    /** Tiempo sin ver a un jugador para avisar que no lo vemos. */
    private static final long LOST_WARN_MS = 2000L;

    /**
     * Todo lo que es propio de UN jugador: su pose, su motor de gestos, su marcador y sus vistas.
     * Regla de hilos: bodyPose / engine / lx,ly,lv / lastSeenMs solo se tocan desde el hilo de MediaPipe;
     * game y las vistas solo desde el hilo de UI.
     */
    private final class Player {
        final int index;
        final int baseColor;

        // Lógica
        final BodyPose bodyPose = new BodyPose();
        final GestureEngine engine = new GestureEngine();
        final AuraGame game = new AuraGame();
        final float[] lx = new float[BodyPose.COUNT];
        final float[] ly = new float[BodyPose.COUNT];
        final float[] lv = new float[BodyPose.COUNT];
        long lastSeenMs = 0L;

        // Vistas
        View hud, popup, candidatePanel;
        TextView tvName, tvRank, tvNextRank, tvPoints, tvMult;
        TextView tvPopupEmoji, tvPopupTitle, tvPopupPoints, tvCandidate;
        ProgressBar progressRank, progressCombo, progressCandidate;

        // Cuenta (id < 0 = invitado: juega pero no guarda nada). Solo hilo de UI.
        long userId = -1L;
        String userName = "";
        int best = 0;                 // mejor puntaje guardado (sube en vivo al superarlo)
        int bestAtStart = 0;          // récord con el que empezó la ronda: para avisar "nuevo récord"
        boolean recordAnnounced = false;

        // Estado de UI
        int shownPoints = 0;
        int shownComboProgress = -1;
        AuraMove shownCandidate = null;
        ValueAnimator scoreAnimator;

        final Runnable resetAccent;

        Player(int index) {
            this.index = index;
            this.baseColor = OverlayView.PLAYER_COLORS[index];
            this.resetAccent = () -> {
                if (candidatePanel.getVisibility() != View.VISIBLE) overlayView.setAccent(this.index, this.baseColor);
            };
        }
    }
    private FirebaseAuth mAuth;
    private FirebaseFirestore firestoreDb;
    private String firebaseUserId = "";
    // Modo de juego
    private int playerCount = 1;
    private Player[] players;
    private final int[] assignScratch = new int[OverlayView.MAX_PLAYERS];   // reutilizado por frame: cero basura
    private int lastMissingMask = 0;                                          // solo hilo de MediaPipe

    // Vistas compartidas
    private PreviewView previewView;
    private OverlayView overlayView;
    private View viewFlash;
    private TextView tvDebug, tvStatus, btnMute, btnReset, btnBack, btnSkeleton;
    private LinearLayout chipRow;
    private HorizontalScrollView chipScroll;
    private final Map<AuraMove, TextView> chips = new EnumMap<>(AuraMove.class);

    // Cámara / IA
    private ExecutorService cameraExecutor;
    private PoseLandmarker poseLandmarker;

    // Buffers reutilizados por frame (solo se tocan desde el hilo de la cámara): cero basura y sin GC durante el juego
    private Bitmap srcBitmap;
    private Bitmap uprightBitmap;
    private Canvas uprightCanvas;
    private final Matrix rotMatrix = new Matrix();
    private volatile boolean detecting = false;      // hay un frame en manos de MediaPipe
    private volatile long detectStartMs = 0L;

    // Diagnóstico
    private volatile boolean debugOn = false;
    private long lastResultMs = 0L;
    private float emaFrameMs = 33f;
    private float emaLatencyMs = 30f;
    private long lastDebugPostMs = 0L;

    // Audio
    private SoundFx sfx;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean muted = false;

    private final Handler ui = new Handler(Looper.getMainLooper());

    // Base de datos: las escrituras van a un hilo aparte para no trabar la UI ni la cámara
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();
    private AuraDb db;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.uptimeMillis();
            for (Player p : players) {
                if (p.game.tick(now)) {
                    refreshMultiplier(p, false);
                    overlayView.setComboLevel(p.index, 1);
                    tvStatus.setText(pfx(p) + "Se acabó el combo 😮‍💨 ¡Empieza otro!");
                }
                int prog = (int) (p.game.comboRemaining(now) * 1000);
                if (prog != p.shownComboProgress) {           // solo redibuja la barra si cambió
                    p.shownComboProgress = prog;
                    p.progressCombo.setProgress(prog);
                }
            }
            ui.postDelayed(this, 50);
        }
    };

    // ------------------------------------------------------------------ ciclo de vida

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            setVolumeControlStream(AudioManager.STREAM_MUSIC);       // los botones de volumen controlan los efectos
            setContentView(R.layout.activity_main);

            playerCount = getIntent().getIntExtra(EXTRA_PLAYERS, 1) == 2 ? 2 : 1;
            players = new Player[playerCount];

            bindViews();
            applyInsets();
            buildPlayers();
            bindIdentities();
            buildChips();
            setupButtons();
            overlayView.setPlayerCount(playerCount);
            tvStatus.setText(idleStatus());

            cameraExecutor = Executors.newSingleThreadExecutor();
            sfx = new SoundFx(this);
            tts = new TextToSpeech(this, this);
            // 1. Inicializa correctamente las variables de clase
            mAuth = FirebaseAuth.getInstance();
            firestoreDb = FirebaseFirestore.getInstance();

// 2. Verifica si ya hay un usuario activo o inicia sesión de forma anónima
            FirebaseUser currentUser = mAuth.getCurrentUser();
            if (currentUser != null) {
                firebaseUserId = currentUser.getUid();
            } else {
                mAuth.signInAnonymously().addOnCompleteListener(this, task -> {
                    if (task.isSuccessful() && mAuth.getCurrentUser() != null) {
                        firebaseUserId = mAuth.getCurrentUser().getUid();
                        Log.d("AuraFarm", "¡Usuario autenticado de forma anónima con éxito! UID: " + firebaseUserId);
                    } else {
                        Log.e("AuraFarm", "Error al autenticar de forma anónima", task.getException());
                    }
                });
            }

            for (Player p : players) {
            final Player pl = p;
            p.engine.setListener(new GestureEngine.Listener() {
                @Override
                public void onMove(AuraMove move) {
                    runOnUiThread(() -> handleMove(pl, move));
                }

                @Override
                public void onCandidate(AuraMove move, float progress) {
                    runOnUiThread(() -> showCandidate(pl, move, progress));
                }
            });
            refreshScoreUi(p, false);
            refreshMultiplier(p, false);
        }
        setupPoseLandmarker();

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 100);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(ticker);
        if (sfx != null) sfx.resume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(ticker);
        if (sfx != null) sfx.pause();
        if (tts != null) tts.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        if (cameraExecutor != null) cameraExecutor.shutdown();
        dbExecutor.shutdown();                                   // termina de guardar lo pendiente y se cierra
        if (poseLandmarker != null) poseLandmarker.close();
        if (sfx != null) sfx.release();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }

    // ------------------------------------------------------------------ setup UI

    private void bindViews() {
        previewView = findViewById(R.id.previewView);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);   // el overlay usa la misma geometría
        previewView.setImplementationMode(PreviewView.ImplementationMode.PERFORMANCE);
        overlayView = findViewById(R.id.overlayView);
        viewFlash = findViewById(R.id.viewFlash);
        tvDebug = findViewById(R.id.tvDebug);
        tvStatus = findViewById(R.id.tvStatus);
        btnMute = findViewById(R.id.btnMute);
        btnReset = findViewById(R.id.btnReset);
        btnBack = findViewById(R.id.btnBack);
        btnSkeleton = findViewById(R.id.btnSkeleton);
        chipRow = findViewById(R.id.chipRow);
        chipScroll = findViewById(R.id.chipScroll);
    }

    /** Con pantalla completa (edge-to-edge) hay que dejar espacio para la barra de estado y la de navegación. */
    private void applyInsets() {
        final View hud = findViewById(R.id.hudContainer);
        final int l = hud.getPaddingLeft(), t = hud.getPaddingTop(), r = hud.getPaddingRight(), b = hud.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(hud, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(l + bars.left, t + bars.top, r + bars.right, b + bars.bottom);
            return insets;
        });
    }

    /** Infla el marcador, el popup y el panel de "detectando" de cada jugador (1 o 2 juegos de vistas). */
    private void buildPlayers() {
        final boolean two = playerCount == 2;
        final LayoutInflater inf = getLayoutInflater();
        final ViewGroup hudRow = findViewById(R.id.hudRow);
        final ViewGroup popupRow = findViewById(R.id.popupRow);
        final ViewGroup candidateRow = findViewById(R.id.candidateRow);

        for (int i = 0; i < playerCount; i++) {
            final Player p = new Player(i);
            players[i] = p;

            // --- Marcador ---
            p.hud = inf.inflate(R.layout.view_player_hud, hudRow, false);
            LinearLayout.LayoutParams hudLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) hudLp.setMarginStart(dp(8));
            hudRow.addView(p.hud, hudLp);
            p.tvName = p.hud.findViewById(R.id.tvPlayerName);
            p.tvRank = p.hud.findViewById(R.id.tvRank);
            p.tvNextRank = p.hud.findViewById(R.id.tvNextRank);
            p.tvPoints = p.hud.findViewById(R.id.tvAuraPoints);
            p.tvMult = p.hud.findViewById(R.id.tvComboMultiplier);
            p.progressRank = p.hud.findViewById(R.id.progressRank);
            p.progressCombo = p.hud.findViewById(R.id.progressCombo);

            p.tvName.setLetterSpacing(0.05f);
            p.tvName.setEllipsize(TextUtils.TruncateAt.END);
            p.tvName.setMaxWidth(dp(two ? 120 : 220));
            if (two) {                                            // versión compacta para que entren dos
                p.tvName.setTextSize(11f);
                p.tvName.setTextColor(p.baseColor);
                p.tvRank.setTextSize(11f);
                p.tvPoints.setTextSize(32f);
                p.tvMult.setTextSize(19f);
                ViewGroup.LayoutParams mlp = p.tvMult.getLayoutParams();
                mlp.width = dp(48);
                mlp.height = dp(48);
                p.tvMult.setLayoutParams(mlp);
                p.hud.setPadding(dp(10), dp(8), dp(10), dp(8));
            }

            // --- Popup del movimiento (centro, mitad izquierda / derecha) ---
            p.popup = inf.inflate(R.layout.view_popup, popupRow, false);
            FrameLayout host = new FrameLayout(this);
            host.addView(p.popup, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            popupRow.addView(host, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            p.tvPopupEmoji = p.popup.findViewById(R.id.tvPopupEmoji);
            p.tvPopupTitle = p.popup.findViewById(R.id.tvPopupTitle);
            p.tvPopupPoints = p.popup.findViewById(R.id.tvPopupPoints);
            if (two) {
                p.tvPopupEmoji.setTextSize(38f);
                p.tvPopupPoints.setTextSize(22f);
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                        p.tvPopupTitle, 12, 28, 1, TypedValue.COMPLEX_UNIT_SP);
            }

            // --- Panel "detectando..." ---
            p.candidatePanel = inf.inflate(R.layout.view_candidate, candidateRow, false);
            LinearLayout.LayoutParams candLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) candLp.setMarginStart(dp(8));
            candidateRow.addView(p.candidatePanel, candLp);
            p.tvCandidate = p.candidatePanel.findViewById(R.id.tvCandidate);
            p.progressCandidate = p.candidatePanel.findViewById(R.id.progressCandidate);
            if (two) p.tvCandidate.setTextSize(12f);
        }
    }

    // ------------------------------------------------------------------ cuentas y puntajes

    /** J1 = la sesión abierta; J2 = la cuenta que entró en el menú (o invitado). Carga récords y cuenta la partida. */
    private void bindIdentities() {
        db = AuraDb.get(this);
        players[0].userId = Session.id(this);
        players[0].userName = Session.name(this);
        if (playerCount == 2) {
            players[1].userId = getIntent().getLongExtra(EXTRA_P2_ID, -1L);
            String n = getIntent().getStringExtra(EXTRA_P2_NAME);
            players[1].userName = n == null || n.isEmpty() ? "Invitado" : n;
        }
        for (final Player p : players) {
            refreshName(p);
            if (p.userId < 0) continue;
            dbAsync(() -> {
                db.recordGameStart(p.userId);
                final AuraDb.User u = db.getUser(p.userId);
                if (u != null) runOnUiThread(() -> {
                    p.best = u.bestScore;
                    p.bestAtStart = u.bestScore;
                    refreshName(p);
                });
            });
        }
    }

    private void dbAsync(Runnable r) {
        try {
            dbExecutor.execute(r);
        } catch (RejectedExecutionException ignored) {
            // la pantalla ya se cerró: no hay nada que guardar
        }
    }

    /** Nombre (y récord) en el marcador de cada jugador. */
    private void refreshName(Player p) {
        String text = (playerCount > 1 ? "J" + (p.index + 1) + " · " : "") + p.userName;
        if (p.userId >= 0 && p.best > 0) text += "  🏆 " + AuraDb.fmt(p.best);
        p.tvName.setText(text);
    }

    private void buildChips() {
        for (AuraMove m : AuraMove.values()) {
            TextView chip = new TextView(this);
            chip.setText(m.emoji + " " + m.shortName);
            chip.setTextSize(13f);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
            chip.setPadding(dp(12), dp(8), dp(12), dp(8));
            styleChip(chip, m, false);
            chip.setOnClickListener(v -> tvStatus.setText(m.emoji + " " + m.title + ": " + m.hint + "  (+" + m.basePoints + ")"));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(8));
            chipRow.addView(chip, lp);
            chips.put(m, chip);
        }
    }

    private void styleChip(TextView chip, AuraMove m, boolean active) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(18));
        bg.setColor(active ? ((m.color & 0x00FFFFFF) | 0xE6000000) : 0x8C0B0B1A);
        bg.setStroke(dp(2), m.color);
        chip.setBackground(bg);
        chip.setTextColor(active ? 0xFF111111 : Color.WHITE);
    }

    private void setupButtons() {
        btnBack.setOnClickListener(v -> finish());                 // vuelve al menú principal

        // Esqueleto: se recuerda la última elección
        final SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        applySkeletonVisible(prefs.getBoolean(PREF_SKELETON, true));
        btnSkeleton.setOnClickListener(v -> {
            boolean show = !overlayView.isSkeletonVisible();
            applySkeletonVisible(show);
            prefs.edit().putBoolean(PREF_SKELETON, show).apply();
            tvStatus.setText(show ? "Esqueleto visible 🦴" : "Esqueleto oculto · el halo de aura sigue activo ✨");
        });
        btnMute.setOnClickListener(v -> {
            muted = !muted;
            btnMute.setText(muted ? "🔇" : "🔊");
            sfx.setMuted(muted);
            if (muted && tts != null) tts.stop();
        });
        btnReset.setOnClickListener(v -> {
            for (Player p : players) {
                p.game.reset();
                p.bestAtStart = p.best;                  // la nueva ronda se compara con el récord actual
                p.recordAnnounced = false;
                p.shownPoints = 0;
                overlayView.setComboLevel(p.index, 1);
                refreshScoreUi(p, false);
                refreshMultiplier(p, false);
            }
            tvStatus.setText(playerCount == 1
                    ? "Marcador reiniciado. ¡A farmear de nuevo! ✨"
                    : "Marcadores reiniciados. ¡Nueva ronda! ✨");
        });
        // Mantener presionado el puntaje del J1: muestra FPS y latencia de la IA
        players[0].tvPoints.setOnLongClickListener(v -> {
            debugOn = !debugOn;
            tvDebug.setVisibility(debugOn ? View.VISIBLE : View.GONE);
            return true;
        });
    }

    private void applySkeletonVisible(boolean show) {
        overlayView.setSkeletonVisible(show);
        btnSkeleton.setAlpha(show ? 1f : 0.4f);        // apagado = botón atenuado
    }

    private String idleStatus() {
        return playerCount == 1 ? STATUS_IDLE_1P : STATUS_IDLE_2P;
    }

    /** Prefijo "J1 · " en los mensajes cuando hay dos jugadores. */
    private String pfx(Player p) {
        return playerCount > 1 ? "J" + (p.index + 1) + " · " : "";
    }

    // ------------------------------------------------------------------ MediaPipe + cámara

    private void setupPoseLandmarker() {
        if (USE_GPU_DELEGATE) {
            try {
                poseLandmarker = createLandmarker(Delegate.GPU);
                return;
            } catch (Exception e) {
                Log.w(TAG, "GPU no disponible, usando CPU", e);
            }
        }
        poseLandmarker = createLandmarker(Delegate.CPU);
    }

    private PoseLandmarker createLandmarker(Delegate delegate) {
        BaseOptions baseOptions = BaseOptions.builder()
                .setModelAssetPath("pose_landmarker_lite.task")
                .setDelegate(delegate)
                .build();

        PoseLandmarker.PoseLandmarkerOptions options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumPoses(playerCount)                 // 1 o 2 cuerpos a la vez
                .setMinPoseDetectionConfidence(0.5f)
                .setMinPosePresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setResultListener((result, inputImage) ->
                        onPoseResult(result, inputImage.getWidth(), inputImage.getHeight()))
                .build();

        return PoseLandmarker.createFromOptions(this, options);
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = future.get();

                // Preview y análisis con la MISMA proporción (4:3) => ven exactamente el mismo campo de visión
                ResolutionSelector previewSelector = new ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .build();
                ResolutionSelector analysisSelector = new ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(new ResolutionStrategy(new Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build();

                Preview preview = new Preview.Builder().setResolutionSelector(previewSelector).build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setResolutionSelector(analysisSelector)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build();
                analysis.setAnalyzer(cameraExecutor, this::analyzeFrame);

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis);

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "No se pudo iniciar la cámara", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    /**
     * Corre en el hilo de la cámara, una vez por frame.
     * - Si MediaPipe todavía está procesando el frame anterior, este se descarta ANTES de convertirlo
     *   (antes se convertía y rotaba igual y luego MediaPipe lo tiraba).
     * - Los bitmaps se reutilizan: nada de crear ~1 MB de basura por frame.
     */
    private void analyzeFrame(ImageProxy proxy) {
        try {
            long now = SystemClock.uptimeMillis();
            if (poseLandmarker == null) return;
            if (detecting && now - detectStartMs < DETECT_TIMEOUT_MS) return;

            MPImage image = toUprightImage(proxy);
            detectStartMs = now;
            detecting = true;
            poseLandmarker.detectAsync(image, now);
        } catch (Exception e) {
            detecting = false;
            Log.e(TAG, "Error analizando frame", e);
        } finally {
            proxy.close();
        }
    }

    private MPImage toUprightImage(ImageProxy proxy) {
        int w = proxy.getWidth(), h = proxy.getHeight();
        ImageProxy.PlaneProxy plane = proxy.getPlanes()[0];
        int rowPixels = plane.getRowStride() / plane.getPixelStride();

        Bitmap frame;
        if (rowPixels == w) {
            if (srcBitmap == null || srcBitmap.getWidth() != w || srcBitmap.getHeight() != h) {
                srcBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            }
            ByteBuffer buf = plane.getBuffer();
            buf.rewind();
            srcBitmap.copyPixelsFromBuffer(buf);
            frame = srcBitmap;
        } else {
            frame = proxy.toBitmap();               // caso raro: filas con relleno
        }

        int rot = proxy.getImageInfo().getRotationDegrees();
        if (rot == 0) return new BitmapImageBuilder(frame).build();

        boolean swap = rot == 90 || rot == 270;
        int dw = swap ? h : w, dh = swap ? w : h;
        if (uprightBitmap == null || uprightBitmap.getWidth() != dw || uprightBitmap.getHeight() != dh) {
            uprightBitmap = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888);
            uprightCanvas = new Canvas(uprightBitmap);
        }
        rotMatrix.setRotate(rot);
        if (rot == 90) rotMatrix.postTranslate(h, 0);
        else if (rot == 180) rotMatrix.postTranslate(w, h);
        else if (rot == 270) rotMatrix.postTranslate(0, w);
        uprightCanvas.drawBitmap(frame, rotMatrix, null);   // cubre todo el destino, no hace falta limpiar
        return new BitmapImageBuilder(uprightBitmap).build();
    }

    /** Corre en el hilo de MediaPipe, una vez por frame. */
    private void onPoseResult(PoseLandmarkerResult result, int imgW, int imgH) {
        long t = result.timestampMs();
        trackPerformance();
        detecting = false;                           // ya se puede mandar otro frame

        List<List<NormalizedLandmark>> poses = result.landmarks();
        assignPoses(poses, assignScratch);

        int missing = 0;
        for (int i = 0; i < playerCount; i++) {
            Player p = players[i];
            int which = assignScratch[i];
            if (which < 0) {
                overlayView.clearPose(i);
                p.engine.noPose(t);
            } else {
                feedPlayer(p, poses.get(which), imgW, imgH, t);
            }
            if (t - p.lastSeenMs > LOST_WARN_MS) missing |= (1 << i);
        }

        // Solo se actualiza el texto cuando cambia quién falta (evita saturar la UI)
        if (missing != lastMissingMask) {
            lastMissingMask = missing;
            final String msg = missingMessage(missing);
            runOnUiThread(() -> tvStatus.setText(msg));
        }
    }

    /**
     * Reparte los cuerpos detectados entre los jugadores. out[jugador] = índice de la pose, o -1 si no hay.
     * La cámara frontal se ve en espejo: quien tiene MAYOR x en la imagen aparece a la IZQUIERDA de la
     * pantalla. J1 = lado izquierdo de la pantalla, J2 = lado derecho.
     */
    private void assignPoses(List<List<NormalizedLandmark>> poses, int[] out) {
        for (int i = 0; i < playerCount; i++) out[i] = -1;
        int n = poses.size();
        if (n == 0) return;

        if (playerCount == 1) {
            out[0] = 0;
        } else if (n >= 2) {
            if (centerX(poses.get(0)) >= centerX(poses.get(1))) {
                out[0] = 0;
                out[1] = 1;
            } else {
                out[0] = 1;
                out[1] = 0;
            }
        } else {                                      // solo se ve a uno: se asigna según el lado de la pantalla
            if (centerX(poses.get(0)) >= 0.5f) out[0] = 0;
            else out[1] = 0;
        }
    }

    private static float centerX(List<NormalizedLandmark> lm) {
        if (lm.size() > BodyPose.R_SH) return (lm.get(BodyPose.L_SH).x() + lm.get(BodyPose.R_SH).x()) * 0.5f;
        return lm.isEmpty() ? 0.5f : lm.get(0).x();
    }

    private void feedPlayer(Player p, List<NormalizedLandmark> lm, int imgW, int imgH, long t) {
        p.lastSeenMs = t;
        for (int i = 0; i < BodyPose.COUNT; i++) {
            if (i < lm.size()) {
                NormalizedLandmark l = lm.get(i);
                p.lx[i] = l.x();
                p.ly[i] = l.y();
                p.lv[i] = l.visibility().orElse(1f);
            } else {
                p.lx[i] = 0f;
                p.ly[i] = 0f;
                p.lv[i] = 0f;
            }
        }
        overlayView.setPose(p.index, p.lx, p.ly, p.lv, imgW, imgH);
        p.bodyPose.update(p.lx, p.ly, p.lv, imgW, imgH, t);
        p.engine.process(p.bodyPose);
    }

    private String missingMessage(int mask) {
        if (mask == 0) return idleStatus();
        if (playerCount == 1) return "No te veo 👀 Ponte de cuerpo entero, ~2 metros de la cámara";
        if (mask == 3) return "No los veo 👀 De cuerpo entero, ~3 metros, uno a cada lado";
        return mask == 1
                ? "No veo al Jugador 1 👀 (lado izquierdo de la pantalla)"
                : "No veo al Jugador 2 👀 (lado derecho de la pantalla)";
    }

    private void trackPerformance() {
        long now = SystemClock.uptimeMillis();
        if (lastResultMs != 0L) emaFrameMs = emaFrameMs * 0.9f + (now - lastResultMs) * 0.1f;
        emaLatencyMs = emaLatencyMs * 0.9f + (now - detectStartMs) * 0.1f;
        lastResultMs = now;
        if (debugOn && now - lastDebugPostMs > 500L) {
            lastDebugPostMs = now;
            final String text = String.format(Locale.ROOT, "%.0f fps · IA %.0f ms", 1000f / emaFrameMs, emaLatencyMs);
            runOnUiThread(() -> tvDebug.setText(text));
        }
    }

    // ------------------------------------------------------------------ reacciones del juego (hilo de UI)

    private void showCandidate(Player p, AuraMove move, float progress) {
        if (move == null) {
            p.shownCandidate = null;
            p.candidatePanel.setVisibility(View.INVISIBLE);
            overlayView.setCandidate(p.index, Color.WHITE, 0f);
            return;
        }
        if (move != p.shownCandidate) {                 // texto, color y acento solo cuando cambia el movimiento
            p.shownCandidate = move;
            p.candidatePanel.setVisibility(View.VISIBLE);
            p.tvCandidate.setText(move.emoji + "  " + move.title + "…");
            p.progressCandidate.setProgressTintList(ColorStateList.valueOf(move.color));
            overlayView.setAccent(p.index, move.color);
        }
        p.progressCandidate.setProgress((int) (progress * 100));
        overlayView.setCandidate(p.index, move.color, progress);
    }

    private void handleMove(Player p, AuraMove move) {
        long now = SystemClock.uptimeMillis();
        AuraGame.Result r = p.game.register(move, now);

        // Guardar en la base de datos (récord personal y mejor combo) y detectar récord nuevo
        boolean newRecord = false;
        if (p.userId >= 0) {
            final long uid = p.userId;
            final int gained = r.gained, total = r.total, mult = r.multiplier;
            dbAsync(() -> db.recordMove(uid, gained, total, mult));

            if (total > p.best) {
                p.best = total;
                refreshName(p);

                // 🚀 ENVIAR NUEVO RÉCORD A FIREBASE FIRESTORE (Ranking Global)
                if (p.index == 0 && !TextUtils.isEmpty(firebaseUserId)) {
                    Map<String, Object> scoreData = new HashMap<>();
                    scoreData.put("userId", firebaseUserId);
                    scoreData.put("username", p.userName);
                    scoreData.put("score", p.best);
                    scoreData.put("timestamp", com.google.firebase.firestore.FieldValue.serverTimestamp());

                    firestoreDb.collection("rankings").document(firebaseUserId)
                            .set(scoreData)
                            .addOnSuccessListener(aVoid -> Log.d(TAG, "Puntuación sincronizada en Firebase"))
                            .addOnFailureListener(e -> Log.w(TAG, "Error al sincronizar puntuación", e));
                }
            }

            if (!p.recordAnnounced && p.bestAtStart > 0 && total > p.bestAtStart) {
                p.recordAnnounced = true;
                newRecord = true;
            }
            if (!p.recordAnnounced && p.bestAtStart > 0 && total > p.bestAtStart) {
                p.recordAnnounced = true;
                newRecord = true;
            }
        }

        sfx.play(move);                                            // sonido propio del emote, al instante
        if (r.rankUp) {
            ui.postDelayed(() -> sfx.playRankUp(), 400L);
        } else if (r.comboUp) {
            final int mult = r.multiplier;
            ui.postDelayed(() -> sfx.playCombo(mult), 250L);       // "ding" más agudo cuanto mayor el combo
        }

        overlayView.setAccent(p.index, move.color);
        overlayView.setComboLevel(p.index, r.multiplier);
        overlayView.burst(p.index, move.color, 34 + 9 * r.multiplier);
        ui.removeCallbacks(p.resetAccent);
        ui.postDelayed(p.resetAccent, 1800L);

        refreshScoreUi(p, true);
        refreshMultiplier(p, r.comboUp);
        showPopup(p, move, r);
        flash(move.color);
        highlightChip(move);
        buzz();

        String status;
        String who = playerCount > 1 ? "Jugador " + (p.index + 1) + ": " : "";
        String speech = VOICE_ON_MOVES ? move.speech : null;
        if (r.rankUp) {
            status = "🏆 ¡" + (playerCount > 1 ? "J" + (p.index + 1) + " " : "") + "SUBIÓ A "
                    + p.game.rankName().toUpperCase(Locale.ROOT) + "!";
            speech = who + "¡Subiste a " + p.game.rankName() + "!";
        } else if (newRecord) {
            status = "🏆 " + pfx(p) + "¡NUEVO RÉCORD PERSONAL! " + AuraDb.fmt(p.best) + " pts";
            speech = who + "¡Nuevo récord!";
        } else if (r.repeated) {
            status = pfx(p) + "Repetido = mitad de puntos. ¡Cambia de movimiento para subir el combo!";
        } else if (r.comboUp) {
            status = "🔥 " + pfx(p) + "¡COMBO x" + r.multiplier + "! Sigue con otro movimiento distinto";
            speech = who + "¡Combo por " + r.multiplier + "!";
        } else {
            status = pfx(p) + move.emoji + " " + move.title + " realizado";
        }
        tvStatus.setText(status);
        if (speech != null) speak(speech);
    }

    private void showPopup(Player p, AuraMove move, AuraGame.Result r) {
        p.tvPopupEmoji.setText(move.emoji);
        p.tvPopupTitle.setText(move.title);
        p.tvPopupTitle.setTextColor(move.color);
        p.tvPopupPoints.setText("+" + r.gained + (r.multiplier > 1 ? "  ·  x" + r.multiplier : ""));

        final View popup = p.popup;
        popup.animate().cancel();
        popup.setAlpha(0f);
        popup.setScaleX(0.4f);
        popup.setScaleY(0.4f);
        popup.setTranslationY(dp(40));
        popup.animate()
                .alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                .setStartDelay(0L)
                .setDuration(280L)
                .setInterpolator(new OvershootInterpolator(2.2f))
                .withEndAction(() -> popup.animate()
                        .alpha(0f).translationY(-dp(60))
                        .setStartDelay(650L)
                        .setDuration(380L)
                        .setInterpolator(new DecelerateInterpolator())
                        .start())
                .start();
    }

    private void flash(int color) {
        viewFlash.animate().cancel();
        viewFlash.setBackgroundColor(color);
        viewFlash.setAlpha(playerCount > 1 ? 0.16f : 0.26f);    // con 2 jugadores el destello es más suave
        viewFlash.animate().alpha(0f).setStartDelay(0L).setDuration(420L).start();
    }

    private void highlightChip(AuraMove move) {
        final TextView chip = chips.get(move);
        if (chip == null) return;
        styleChip(chip, move, true);
        chip.animate().cancel();
        chip.setScaleX(1.25f);
        chip.setScaleY(1.25f);
        chip.animate().scaleX(1f).scaleY(1f).setStartDelay(0L).setDuration(300L)
                .setInterpolator(new OvershootInterpolator(2f)).start();
        chip.postDelayed(() -> styleChip(chip, move, false), 1500L);
        chipScroll.smoothScrollTo(Math.max(0, chip.getLeft() - dp(24)), 0);   // llevar el chip a la vista
    }

    private void refreshScoreUi(Player p, boolean animated) {
        final int target = p.game.getPoints();
        if (p.scoreAnimator != null) p.scoreAnimator.cancel();
        if (animated && target != p.shownPoints) {
            p.scoreAnimator = ValueAnimator.ofInt(p.shownPoints, target);
            p.scoreAnimator.setDuration(500L);
            p.scoreAnimator.addUpdateListener(a -> {
                p.shownPoints = (int) a.getAnimatedValue();
                p.tvPoints.setText(String.valueOf(p.shownPoints));
            });
            p.scoreAnimator.start();
            p.tvPoints.animate().cancel();
            p.tvPoints.setScaleX(1.14f);
            p.tvPoints.setScaleY(1.14f);
            p.tvPoints.setPivotX(0f);
            p.tvPoints.animate().scaleX(1f).scaleY(1f).setDuration(350L).start();
        } else {
            p.shownPoints = target;
            p.tvPoints.setText(String.valueOf(target));
        }

        p.tvRank.setText(p.game.rankName());
        int rankTarget = (int) (p.game.rankProgress() * 1000);
        if (animated) {
            ObjectAnimator.ofInt(p.progressRank, "progress", rankTarget).setDuration(500L).start();
        } else {
            p.progressRank.setProgress(rankTarget);
        }
        if (p.game.rankIndex() >= 5) {
            p.tvNextRank.setText("¡Rango máximo alcanzado! 👑");
        } else if (playerCount > 1) {
            p.tvNextRank.setText(p.game.getPoints() + " / " + p.game.nextRankPoints());
        } else {
            p.tvNextRank.setText(p.game.getPoints() + " / " + p.game.nextRankPoints() + " para el siguiente rango");
        }
    }

    private void refreshMultiplier(Player p, boolean pulse) {
        int m = p.game.getMultiplier();
        p.tvMult.setText("x" + m);
        p.tvMult.setAlpha(m > 1 ? 1f : 0.55f);
        if (pulse) {
            p.tvMult.animate().cancel();
            p.tvMult.setScaleX(1.45f);
            p.tvMult.setScaleY(1.45f);
            p.tvMult.animate().scaleX(1f).scaleY(1f).setStartDelay(0L).setDuration(420L)
                    .setInterpolator(new OvershootInterpolator(3f)).start();
        }
    }

    private void buzz() {
        int type = Build.VERSION.SDK_INT >= 30 ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.LONG_PRESS;
        previewView.performHapticFeedback(type);
    }

    // ------------------------------------------------------------------ voz

    private void speak(String text) {
        if (tts != null && ttsReady && !muted) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "AURA_GAME_ID");
        }
    }

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS || tts == null) return;
        Locale[] tries = {Locale.forLanguageTag("es-CL"), Locale.forLanguageTag("es-US"), Locale.forLanguageTag("es-ES")};
        for (Locale loc : tries) {
            int r = tts.setLanguage(loc);
            if (r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED) {
                ttsReady = true;
                return;
            }
        }
    }

    // ------------------------------------------------------------------ permisos

    private boolean allPermissionsGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 100) {
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                tvStatus.setText("Necesito el permiso de cámara para leer tus poses 🎥");
            }
        }
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
