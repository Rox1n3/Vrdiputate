package kz.kitdev.live;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import kz.kitdev.BaseActivity;
import kz.kitdev.BuildConfig;
import kz.kitdev.R;
import kz.kitdev.chat.ChatAdapter;
import kz.kitdev.chat.ChatMessage;
import kz.kitdev.chat.LangManager;
import kz.kitdev.databinding.ActivityLiveChatBinding;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * LiveChatActivity — «Живой чат с ИИ».
 *
 * Белая тема, язык авто-определяется, видео-аватар синхронизирован с TTS.
 * Текст ответа появляется одновременно с началом воспроизведения аудио.
 */
public class LiveChatActivity extends BaseActivity {

    private static final int    PERM_MIC        = 200;

    private static final String OPENAI_URL       = "https://api.openai.com/v1/chat/completions";
    private static final String OPENAI_TTS_URL   = "https://api.openai.com/v1/audio/speech";
    private static final String TTS_UTT_ID       = "live_tts";

    // ── Состояние аватара ────────────────────────────────────────────
    private enum AiState { IDLE, THINKING, SPEAKING }
    private AiState aiState = AiState.IDLE;

    // ── ViewBinding ──────────────────────────────────────────────────
    private ActivityLiveChatBinding binding;
    private ChatAdapter adapter;

    // ── Видео (TextureView + MediaPlayer) ───────────────────────────
    private boolean     videoReady  = false;
    private Bitmap      idleBitmap;
    private MediaPlayer mediaPlayer;

    // ── Сеть ─────────────────────────────────────────────────────────
    private OkHttpClient httpClient;

    // ── TTS — казахский (PCM + AudioTrack) ──────────────────────────
    private volatile AudioTrack currentAudioTrack;
    private volatile int         ttsSession            = 0;
    private          String      pendingKkContinuation = null;

    // ── TTS — рус/англ (Android TTS) ────────────────────────────────
    private TextToSpeech tts;

    // ── Голосовой ввод (SpeechRecognizer) ───────────────────────────
    private SpeechRecognizer speechRecognizer;
    private boolean          kkSttUnavailable = false;
    private int              networkErrorCount = 0;
    private boolean          isListening      = false;
    private String           voiceAccumulated = "";
    /** Язык, определённый по локали STT — приоритет над текстовой эвристикой */
    private String           voiceInputLang   = null;

    // ── Загрузка ─────────────────────────────────────────────────────
    private boolean isLoading = false;

    // ── Язык на момент создания (для отслеживания смены языка) ───────
    private String langOnCreate;

    // ── Typing dots ──────────────────────────────────────────────────
    private final ObjectAnimator[] dotAnimators = new ObjectAnimator[3];

    // ── Audio focus ──────────────────────────────────────────────────
    private AudioManager audioManager;

    // ══════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ══════════════════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Принудительно светлая тема
        getDelegate().setLocalNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        super.onCreate(savedInstanceState);
        langOnCreate = LangManager.get(this);

        binding = ActivityLiveChatBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // adjustPan: при появлении клавиатуры весь layout сдвигается вверх,
        // поле ввода всегда остаётся видным. setDecorFitsSystemWindows(true)
        // обеспечивает корректную обработку системных insets на Android 15+.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);

        getWindow().setStatusBarColor(0xFFFFFFFF);
        new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView())
                .setAppearanceLightStatusBars(true);

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        httpClient = new OkHttpClient.Builder()
                .connectionPool(new okhttp3.ConnectionPool(5, 5, TimeUnit.MINUTES))
                .build();

        setupToolbar();
        setupRecyclerView();
        setupVideoAvatar();
        setupTts();
        setupInputBar();
    }

    @Override protected void onResume() {
        super.onResume();
        // Если язык сменился пока активити была в фоне — пересоздаём
        if (!LangManager.get(this).equals(langOnCreate)) {
            recreate();
            return;
        }
        if (aiState == AiState.SPEAKING && videoReady && mediaPlayer != null
                && !mediaPlayer.isPlaying())
            mediaPlayer.start();
    }

    @Override protected void onPause() {
        super.onPause();
        if (mediaPlayer != null && mediaPlayer.isPlaying()) mediaPlayer.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopAllSpeech();
        abandonAudioFocus();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
        stopTypingDots();
        releaseMediaPlayer();
        if (idleBitmap != null && !idleBitmap.isRecycled()) idleBitmap.recycle();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.live_chat_bg_hold, R.anim.live_chat_exit);
    }

    /**
     * Глобальный сброс выделения текста — работает при тапе ВЕЗДЕ на экране:
     * в RecyclerView (сбоку от пузыря), в heroSection (видео/картинка), в пустых зонах.
     * Если в фокусе textIsSelectable TextView и тап пришёл не в него — сброс фокуса.
     * EditText (поле ввода) не трогаем — он должен хранить фокус при наборе текста.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            View focused = getCurrentFocus();
            if (focused instanceof android.widget.TextView
                    && !(focused instanceof android.widget.EditText)) {
                android.graphics.Rect rect = new android.graphics.Rect();
                focused.getGlobalVisibleRect(rect);
                if (!rect.contains((int) ev.getRawX(), (int) ev.getRawY())) {
                    focused.clearFocus();
                }
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    // ══════════════════════════════════════════════════════════════════
    //  Toolbar, RecyclerView, Input bar
    // ══════════════════════════════════════════════════════════════════

    private void setupToolbar() {
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setTitle("");
        binding.toolbar.setTitle("");
        android.view.View titleView = getLayoutInflater().inflate(
                R.layout.layout_live_toolbar, binding.toolbar, false);
        binding.toolbar.addView(titleView);
    }

    private void setupRecyclerView() {
        adapter = new ChatAdapter();
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        binding.recyclerView.setLayoutManager(lm);
        binding.recyclerView.setAdapter(adapter);

        adapter.setBotTapListener((question, answer) -> {
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user != null) {
                saveToFirestore(user.getUid(), question, answer);
                showSaveNotification();
            }
        });

        // Тап вне выделенного TextView → сброс выделения текста.
        // Логика: если есть сфокусированный дочерний view (textIsSelectable TextView)
        // и палец упал НЕ внутри него (т.е. сбоку от пузыря или ниже всех сообщений),
        // RecyclerView запрашивает фокус → TextView теряет фокус → выделение сбрасывается.
        binding.recyclerView.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    View focused = rv.findFocus();
                    if (focused != null) {
                        android.graphics.Rect rect = new android.graphics.Rect();
                        focused.getGlobalVisibleRect(rect);
                        if (!rect.contains((int) e.getRawX(), (int) e.getRawY())) {
                            rv.requestFocus();
                        }
                    } else if (rv.findChildViewUnder(e.getX(), e.getY()) == null) {
                        rv.requestFocus();
                    }
                }
                return false;
            }
        });

    }

    private void scrollToBottom() {
        binding.recyclerView.postDelayed(
                () -> binding.recyclerView.scrollToPosition(adapter.getItemCount() - 1), 80);
    }

    private void setupInputBar() {
        binding.btnSend.setOnClickListener(v -> {
            String text = binding.etInput.getText().toString().trim();
            if (!text.isEmpty()) sendMessage(text);
        });
        binding.btnMic.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.RECORD_AUDIO}, PERM_MIC);
            } else {
                toggleVoiceInput();
            }
        });
        binding.btnExit.setOnClickListener(v -> finish());
    }

    private void setInputEnabled(boolean en) {
        binding.etInput.setEnabled(en);
        binding.btnSend.setEnabled(en);
        binding.btnMic.setEnabled(en);
    }

    // ══════════════════════════════════════════════════════════════════
    //  Видео-аватар
    // ══════════════════════════════════════════════════════════════════

    private void setupVideoAvatar() {
        // ── Стоп-кадр: извлекаем первый кадр видео в фоне ─────────────────────
        new Thread(() -> {
            try {
                MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                Uri uri = Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.ai_visual);
                mmr.setDataSource(this, uri);
                Bitmap frame = mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                mmr.release();
                if (frame != null) {
                    idleBitmap = frame;
                    runOnUiThread(() -> {
                        binding.imgAiIdle.setImageBitmap(idleBitmap);
                        binding.imgAiIdle.animate().alpha(1f).setDuration(300).start();
                    });
                }
            } catch (Exception ignored) {}
        }).start();

        // TextureView — часть обычной View-иерархии: поддерживает alpha и Matrix-трансформ.
        // SurfaceTextureListener связывает жизненный цикл Surface с MediaPlayer.
        binding.videoAi.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface,
                                                  int width, int height) {
                startMediaPlayer(surface);
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface,
                                                    int width, int height) {
                // Размер изменился (например, ориентация) — пересчитываем матрицу
                if (mediaPlayer != null) {
                    applyCenterCropMatrix(mediaPlayer.getVideoWidth(),
                                         mediaPlayer.getVideoHeight());
                }
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                releaseMediaPlayer();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {}
        });
    }

    /** Создаёт MediaPlayer, подключает его к SurfaceTexture и начинает async-подготовку. */
    private void startMediaPlayer(SurfaceTexture surfaceTexture) {
        releaseMediaPlayer();
        try {
            Uri uri = Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.ai_visual);
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(this, uri);
            mediaPlayer.setSurface(new Surface(surfaceTexture));
            mediaPlayer.setLooping(true);
            mediaPlayer.setVolume(0f, 0f); // видео немое — голос идёт через OpenAI TTS
            mediaPlayer.setOnPreparedListener(mp -> {
                videoReady = true;
                applyCenterCropMatrix(mp.getVideoWidth(), mp.getVideoHeight());
                if (aiState == AiState.SPEAKING) {
                    mp.start();
                    binding.videoCover.animate().alpha(0f).setDuration(350).start();
                    binding.imgAiIdle.animate().alpha(0f).setDuration(350).start();
                }
            });
            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            if (mediaPlayer != null) { mediaPlayer.release(); mediaPlayer = null; }
        }
    }

    /**
     * Применяет Matrix-трансформ к TextureView для эффекта centerCrop.
     * VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING кадрирует сверху, а не по центру.
     * Matrix позволяет масштабировать симметрично относительно центра View.
     */
    private void applyCenterCropMatrix(int videoWidth, int videoHeight) {
        binding.videoAi.post(() -> {
            float viewW = binding.videoAi.getWidth();
            float viewH = binding.videoAi.getHeight();
            if (viewW == 0 || viewH == 0 || videoWidth == 0 || videoHeight == 0) return;
            float videoAspect = (float) videoWidth / videoHeight;
            float viewAspect  = viewW / viewH;
            Matrix matrix = new Matrix();
            if (videoAspect < viewAspect) {
                // Портретное видео: масштабируем Y чтобы заполнить по ширине
                float sy = viewAspect / videoAspect;
                matrix.setScale(1f, sy, viewW / 2f, viewH / 2f);
            } else {
                // Ландшафтное видео: масштабируем X чтобы заполнить по высоте
                float sx = videoAspect / viewAspect;
                matrix.setScale(sx, 1f, viewW / 2f, viewH / 2f);
            }
            binding.videoAi.setTransform(matrix);
        });
    }

    /** Останавливает и освобождает MediaPlayer. */
    private void releaseMediaPlayer() {
        videoReady = false;
        if (mediaPlayer != null) {
            try { mediaPlayer.stop(); }    catch (Exception ignored) {}
            try { mediaPlayer.release(); } catch (Exception ignored) {}
            mediaPlayer = null;
        }
    }

    /**
     * IDLE    → стоп-кадр, нет точек
     * THINKING→ стоп-кадр + пульсирующие точки (ИИ генерирует / загружает аудио)
     * SPEAKING→ видео играет (ИИ говорит), нет точек
     *
     * Переходы всегда crossfade (350мс) — нет видимого скачка.
     */
    private void setAiState(AiState newState) {
        if (newState == aiState) return;
        AiState prev = aiState;
        aiState = newState;

        switch (newState) {
            case IDLE:
                stopTypingDots();
                if (prev == AiState.SPEAKING) crossfadeToIdle();
                break;
            case THINKING:
                startTypingDots();
                if (prev == AiState.SPEAKING) crossfadeToIdle();
                break;
            case SPEAKING:
                stopTypingDots();
                crossfadeToSpeaking();
                break;
        }
    }

    /** Стоп-кадр → видео (кадр 0 = тот же пиксел что стоп-кадр → переход незаметен) */
    private void crossfadeToSpeaking() {
        if (!videoReady || mediaPlayer == null) {
            // Видео ещё не готово (onPrepared не вернулся).
            // onPrepared проверит aiState==SPEAKING и запустит видео когда будет готово.
            return;
        }
        mediaPlayer.seekTo(0);
        mediaPlayer.start();
        // Убираем заглушку → TextureView становится виден
        binding.videoCover.animate().alpha(0f).setDuration(350).start();
        binding.imgAiIdle.animate().alpha(0f).setDuration(350).start();
    }

    /** Видео → стоп-кадр. После crossfade паузим и перематываем в начало. */
    private void crossfadeToIdle() {
        binding.imgAiIdle.animate().alpha(1f).setDuration(350).start();
        if (videoReady && mediaPlayer != null) {
            binding.videoCover.animate().alpha(1f).setDuration(350)
                    .withEndAction(() -> {
                        if (mediaPlayer != null && mediaPlayer.isPlaying()) mediaPlayer.pause();
                        if (mediaPlayer != null) mediaPlayer.seekTo(0);
                    }).start();
        }
    }

    // ── Typing dots ──────────────────────────────────────────────────

    private void startTypingDots() {
        binding.typingIndicator.setVisibility(View.VISIBLE);
        View[] dots = {binding.dot1, binding.dot2, binding.dot3};
        for (int i = 0; i < 3; i++) {
            dotAnimators[i] = ObjectAnimator.ofFloat(dots[i], "alpha", 1f, 0.15f, 1f);
            dotAnimators[i].setDuration(900);
            dotAnimators[i].setStartDelay(i * 200L);
            dotAnimators[i].setRepeatCount(ObjectAnimator.INFINITE);
            dotAnimators[i].start();
        }
    }

    private void stopTypingDots() {
        binding.typingIndicator.setVisibility(View.GONE);
        for (ObjectAnimator a : dotAnimators) { if (a != null) a.cancel(); }
    }

    // ══════════════════════════════════════════════════════════════════
    //  Голосовой ввод
    // ══════════════════════════════════════════════════════════════════

    private void toggleVoiceInput() {
        if (isListening) stopVoiceInput();
        else             startVoiceInput();
    }

    private void stopVoiceInput() {
        if (!isListening) return;
        isListening      = false;
        voiceAccumulated = "";
        binding.btnMic.setImageResource(R.drawable.ic_mic);
        if (aiState == AiState.THINKING) setAiState(AiState.IDLE);
        if (speechRecognizer != null) {
            speechRecognizer.stopListening();
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
    }

    private void startVoiceInput() {
        isListening      = true;
        voiceInputLang   = null;
        voiceAccumulated = "";
        binding.etInput.setText("");
        binding.btnMic.setImageResource(R.drawable.ic_mic_active);

        if (speechRecognizer != null) { speechRecognizer.destroy(); }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle p)    {}
            @Override public void onBeginningOfSpeech()         {}
            @Override public void onRmsChanged(float v)         {}
            @Override public void onBufferReceived(byte[] b)    {}
            @Override public void onEndOfSpeech()               {}
            @Override public void onEvent(int t, Bundle b)      {}

            @Override
            public void onPartialResults(Bundle partial) {
                java.util.ArrayList<String> parts =
                        partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (parts != null && !parts.isEmpty()) {
                    String best = pickBestVoiceResult(parts);
                    if (!best.isEmpty()) {
                        String live = voiceAccumulated.isEmpty()
                                ? best : voiceAccumulated + " " + best;
                        binding.etInput.setText(live);
                        binding.etInput.setSelection(live.length());
                    }
                }
            }

            @Override
            public void onResults(Bundle results) {
                networkErrorCount = 0;
                java.util.ArrayList<String> r =
                        results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (r != null && !r.isEmpty()) {
                    String best = pickBestVoiceResult(r);
                    if (!best.isEmpty()) {
                        voiceAccumulated = voiceAccumulated.isEmpty()
                                ? best : voiceAccumulated + " " + best;
                        binding.etInput.setText(voiceAccumulated);
                        binding.etInput.setSelection(voiceAccumulated.length());
                    }
                }
                if (isListening) {
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if (isListening && speechRecognizer != null) {
                            speechRecognizer.startListening(buildRecognizerIntent());
                        }
                    }, 100);
                }
            }

            @Override
            public void onError(int error) {
                if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
                        || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
                    kkSttUnavailable = true;
                }
                if (!isListening) return;
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                        || error == SpeechRecognizer.ERROR_CLIENT) {
                    stopVoiceInput();
                    return;
                }
                int delay;
                if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                    delay = 600;
                } else if (error == SpeechRecognizer.ERROR_NO_MATCH
                        || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    delay = 300;
                } else if (error == SpeechRecognizer.ERROR_NETWORK
                        || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) {
                    networkErrorCount++;
                    delay = 800;
                } else {
                    delay = 300;
                }
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (isListening && speechRecognizer != null) {
                        speechRecognizer.startListening(buildRecognizerIntent());
                    }
                }, delay);
            }
        });
        speechRecognizer.startListening(buildRecognizerIntent());
    }

    private String pickBestVoiceResult(java.util.ArrayList<String> candidates) {
        if (candidates == null || candidates.isEmpty()) return "";
        String uiLang = LangManager.get(this);
        if ("kk".equals(uiLang)) {
            for (String c : candidates) {
                if (c.matches(".*[ғқңөүұіәһ].*")) return c;
            }
        } else if ("en".equals(uiLang)) {
            for (String c : candidates) {
                long lat = 0, cyr = 0;
                for (char ch : c.toCharArray()) {
                    if (ch >= 'a' && ch <= 'z' || ch >= 'A' && ch <= 'Z') lat++;
                    else if (ch >= '\u0430' && ch <= '\u044f') cyr++;
                }
                if (lat > cyr) return c;
            }
        }
        return candidates.get(0);
    }

    private Intent buildRecognizerIntent() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        boolean useOffline = networkErrorCount >= 2;
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, useOffline);

        String uiLang = LangManager.get(this);
        String primary;
        if ("kk".equals(uiLang) && !kkSttUnavailable) {
            primary = "kk-KZ";
            voiceInputLang = "kk";
        } else if ("en".equals(uiLang)) {
            primary = "en-US";
            voiceInputLang = "en";
        } else {
            primary = "ru-RU";
            voiceInputLang = "ru";
        }
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, primary);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, primary);
        intent.putExtra("android.speech.extra.ONLY_RETURN_LANGUAGE_PREFERENCE", false);
        java.util.ArrayList<String> extra = new java.util.ArrayList<>();
        if (!"ru-RU".equals(primary)) extra.add("ru-RU");
        if (!"kk-KZ".equals(primary) && !kkSttUnavailable) extra.add("kk-KZ");
        if (!"en-US".equals(primary)) extra.add("en-US");
        if (!extra.isEmpty()) {
            intent.putStringArrayListExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", extra);
        }
        return intent;
    }

    @Override
    public void onRequestPermissionsResult(int code,
            @NonNull String[] perms, @NonNull int[] grants) {
        super.onRequestPermissionsResult(code, perms, grants);
        if (code == PERM_MIC && grants.length > 0
                && grants[0] == PackageManager.PERMISSION_GRANTED) {
            toggleVoiceInput();
        } else if (code == PERM_MIC) {
            Toast.makeText(this, R.string.error_mic_denied, Toast.LENGTH_SHORT).show();
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  Отправка → OpenAI LLM
    // ══════════════════════════════════════════════════════════════════

    private void sendMessage(String text) {
        if (isLoading) return;
        stopAllSpeech();

        // Останавливаем запись если была активна
        boolean wasListening = isListening;
        if (isListening) {
            isListening = false;
            if (speechRecognizer != null) {
                speechRecognizer.stopListening();
                speechRecognizer.destroy();
                speechRecognizer = null;
            }
        }
        if (wasListening) binding.btnMic.setImageResource(R.drawable.ic_mic);

        binding.etInput.setText("");
        adapter.addMessage(new ChatMessage(ChatMessage.TYPE_USER, text));
        adapter.addMessage(ChatMessage.loading());
        scrollToBottom();

        isLoading = true;
        setInputEnabled(false);
        setAiState(AiState.THINKING);

        callOpenAI(text);
    }

    private void callOpenAI(String question) {
        loadSimilarQueries(question, similarContext -> callOpenAIWithContext(question, similarContext));
    }

    private void callOpenAIWithContext(String question, String similarContext) {
        final String lang = detectMessageLang(question);
        try {
            JSONObject body = new JSONObject();
            body.put("model", "gpt-4.1");
            body.put("stream", false);
            body.put("temperature", 0.3);

            JSONArray msgs = new JSONArray();
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", buildSystemPrompt(lang, similarContext));
            msgs.put(sys);

            // Добавляем историю диалога (кроме последних 2: текущий вопрос + loading)
            List<ChatMessage> history = adapter.getMessages();
            int histEnd = Math.max(0, history.size() - 2);
            for (int i = 0; i < histEnd; i++) {
                ChatMessage hm = history.get(i);
                if (hm.isLoading || hm.text == null || hm.text.isEmpty()) continue;
                JSONObject h = new JSONObject();
                h.put("role", hm.type == ChatMessage.TYPE_USER ? "user" : "assistant");
                h.put("content", hm.text);
                msgs.put(h);
            }

            JSONObject usr = new JSONObject();
            usr.put("role", "user");
            usr.put("content", question);
            msgs.put(usr);
            body.put("messages", msgs);

            RequestBody rb = RequestBody.create(
                    body.toString(), MediaType.parse("application/json"));
            Request req = new Request.Builder()
                    .url(OPENAI_URL)
                    .addHeader("Authorization", "Bearer " + BuildConfig.OPENAI_API_KEY)
                    .post(rb).build();

            new Thread(() -> {
                try {
                    Response resp = httpClient.newCall(req).execute();
                    if (!resp.isSuccessful()) {
                        String errBody = "";
                        try { if (resp.body() != null) errBody = resp.body().string(); }
                        catch (Exception ignored) {}
                        final int code = resp.code();
                        android.util.Log.e("OpenAI_Live", "HTTP " + code + ": " + errBody);
                        runOnUiThread(() -> onAnswerFailedWithCode(code)); return;
                    }
                    if (resp.body() == null) {
                        runOnUiThread(this::onAnswerFailed); return;
                    }
                    String json = resp.body().string();
                    String rawAnswer = new JSONObject(json)
                            .getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content")
                            .trim();

                    if (rawAnswer.isEmpty()) {
                        runOnUiThread(this::onAnswerFailed); return;
                    }

                    // Убираем строку с маркером из отображаемого текста
                    final String answer = rawAnswer
                            .replaceAll("(?m)^##ЗАЯВКА_ГОТОВА##[^\n]*\\n?", "").trim();

                    // Детектируем маркер заявки и отправляем письмо + сохраняем переписку
                    if (rawAnswer.contains("##ЗАЯВКА_ГОТОВА##")) {
                        sendComplaintEmail(rawAnswer, answer);
                    }

                    saveToGlobalQueries(question, answer, lang);

                    final String ttsText = kz.kitdev.util.TtsPreprocessor.prepare(answer, lang);
                    runOnUiThread(() -> {
                        speakOpenAIPrepareAndShow(ttsText, answer, question, voiceForLang(lang));
                    });
                } catch (Exception e) {
                    runOnUiThread(this::onAnswerFailed);
                }
            }).start();

        } catch (Exception e) {
            onAnswerFailed();
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  Отправка заявки на email
    // ══════════════════════════════════════════════════════════════════

    private void sendComplaintEmail(String rawAnswer, String finalAnswer) {
        try {
            int start = rawAnswer.indexOf("##ЗАЯВКА_ГОТОВА##");
            if (start == -1) return;
            String markerLine = rawAnswer.substring(start + "##ЗАЯВКА_ГОТОВА##".length());
            int end = markerLine.indexOf('\n');
            if (end != -1) markerLine = markerLine.substring(0, end);
            markerLine = markerLine.trim();

            String fio     = extractComplaintField(markerLine, "ФИО");
            String phone   = extractComplaintField(markerLine, "Телефон");
            String problem = extractComplaintField(markerLine, "Проблема");
            String address = extractComplaintField(markerLine, "МестоПроблемы");

            // Firestore сохраняем ВСЕГДА
            final String f = fio, p = phone, pr = problem, a = address;
            runOnUiThread(() -> saveComplaintConversation(f, p, pr, a, finalAnswer));

            // Email — только при валидном номере
            if (!kz.kitdev.util.EmailSender.isValidKzPhone(phone)) {
                android.util.Log.w("Complaint", "Email не отправлен (формат номера): " + phone);
                return;
            }

            java.text.SimpleDateFormat sdf =
                    new java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault());
            String timestamp = sdf.format(new java.util.Date());

            kz.kitdev.util.EmailSender.sendComplaint(
                    "roxn927@gmail.com",
                    BuildConfig.EMAIL_SENDER,
                    BuildConfig.EMAIL_PASSWORD,
                    fio, phone, problem, address, timestamp,
                    new kz.kitdev.util.EmailSender.EmailCallback() {
                        @Override public void onSuccess() {
                            android.util.Log.d("Complaint", "Email отправлен: " + fio + " " + phone);
                        }
                        @Override public void onError(String error) {
                            android.util.Log.e("Complaint", "Ошибка отправки email: " + error);
                        }
                    });

        } catch (Exception e) {
            android.util.Log.e("Complaint", "Ошибка разбора заявки", e);
        }
    }

    /**
     * Сохраняет всю переписку в users/{uid}/complaints.
     * Атомарно получает номер заявки, геокодирует адрес, сохраняет координаты в locations.
     */
    private void saveComplaintConversation(String fio, String phone,
                                           String problem, String address,
                                           String finalAnswer) {
        com.google.firebase.auth.FirebaseUser user =
                com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;

        // Снимок переписки из адаптера + финальный ответ ИИ
        java.util.List<java.util.Map<String, Object>> msgs = new java.util.ArrayList<>();
        for (kz.kitdev.chat.ChatMessage m : adapter.getMessages()) {
            if (m.isLoading || m.text == null || m.text.isEmpty()) continue;
            java.util.Map<String, Object> entry = new java.util.HashMap<>();
            entry.put("role", m.type == kz.kitdev.chat.ChatMessage.TYPE_USER ? "user" : "bot");
            entry.put("text", m.text);
            msgs.add(entry);
        }
        java.util.Map<String, Object> lastBot = new java.util.HashMap<>();
        lastBot.put("role", "bot");
        lastBot.put("text", finalAnswer);
        msgs.add(lastBot);

        final java.util.List<java.util.Map<String, Object>> finalMsgs = msgs;
        final com.google.firebase.firestore.FirebaseFirestore db =
                com.google.firebase.firestore.FirebaseFirestore.getInstance();

        // Шаг 1: Сохраняем заявку СРАЗУ — без зависимости от транзакции номера
        new Thread(() -> {
            double[] coords = smartGeocode(address, finalMsgs);
            final double finalLat = coords[0];
            final double finalLng = coords[1];

            runOnUiThread(() -> {
                java.util.Map<String, Object> data = new java.util.HashMap<>();
                data.put("fio",             fio);
                data.put("phone",           phone);
                data.put("problem",         problem);
                data.put("address",         address);
                data.put("lang",            kz.kitdev.chat.LangManager.get(this));
                data.put("status",          "processing");
                data.put("complaintNumber", 0L);
                data.put("lat",             finalLat);
                data.put("lng",             finalLng);
                data.put("createdAt",       com.google.firebase.firestore.FieldValue.serverTimestamp());
                data.put("messages",        finalMsgs);

                db.collection("users").document(user.getUid())
                        .collection("complaints")
                        .add(data)
                        .addOnSuccessListener(ref -> {
                            android.util.Log.d("Complaint", "Заявка сохранена: " + ref.getId());

                            // Шаг 2: Получаем порядковый номер и обновляем документ
                            com.google.firebase.firestore.DocumentReference statsRef =
                                    db.collection("global").document("stats");
                            db.runTransaction(transaction -> {
                                com.google.firebase.firestore.DocumentSnapshot snap = transaction.get(statsRef);
                                long newCount = snap.exists() && snap.getLong("complaintCount") != null
                                        ? snap.getLong("complaintCount") + 1 : 1;
                                transaction.set(statsRef,
                                        java.util.Collections.singletonMap("complaintCount", newCount),
                                        com.google.firebase.firestore.SetOptions.merge());
                                return newCount;
                            }).addOnSuccessListener(complaintNumber -> {
                                final long num = (long) complaintNumber;
                                ref.update("complaintNumber", num);
                                String complaintInfo = "\n\n📋 Номер вашей заявки: " + String.format("%04d", num);
                                runOnUiThread(() -> adapter.appendToLastBotMessage(complaintInfo));
                            }).addOnFailureListener(e ->
                                    android.util.Log.e("Complaint", "Номер не получен, заявка сохранена без номера", e));

                            // Шаг 3: Сохраняем координаты в locations
                            if (finalLat != 0 || finalLng != 0) {
                                java.util.Map<String, Object> locData = new java.util.HashMap<>();
                                locData.put("lat",         finalLat);
                                locData.put("lng",         finalLng);
                                locData.put("address",     address);
                                locData.put("description", problem);
                                locData.put("complaintId", ref.getId());
                                locData.put("uid",         user.getUid());
                                locData.put("createdAt",   com.google.firebase.firestore.FieldValue.serverTimestamp());
                                db.collection("locations").add(locData);
                            }
                        })
                        .addOnFailureListener(e ->
                                android.util.Log.e("Complaint", "Ошибка сохранения заявки", e));
            });
        }).start();
    }

    private String extractComplaintField(String line, String field) {
        String key = field + ": ";
        int idx = line.indexOf(key);
        if (idx == -1) return "не указано";
        int vs = idx + key.length();
        int ve = line.indexOf(" | ", vs);
        return ve == -1 ? line.substring(vs).trim() : line.substring(vs, ve).trim();
    }

    private void onAnswerFailed() {
        isLoading = false;
        setInputEnabled(true);
        adapter.removeLastMessage();
        setAiState(AiState.IDLE);
        Toast.makeText(this, R.string.error_network, Toast.LENGTH_SHORT).show();
    }

    private void onAnswerFailedWithCode(int httpCode) {
        isLoading = false;
        setInputEnabled(true);
        adapter.removeLastMessage();
        setAiState(AiState.IDLE);
        String msg = "Ошибка сервера: HTTP " + httpCode;
        if (httpCode == 401) msg = "Ошибка авторизации API (401)";
        else if (httpCode == 404) msg = "Модель недоступна (404). Проверь API ключ.";
        else if (httpCode == 429) msg = "Превышен лимит запросов (429). Подождите.";
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    // ══════════════════════════════════════════════════════════════════
    //  TTS — маршрутизация
    // ══════════════════════════════════════════════════════════════════

    // ── Умное геокодирование для Павлодарской области ──────────────────────

    private static final String[] PAVLODAR_PLACES = {
        "Павлодар", "Экибастуз", "Аксу", "Баянаул", "Железинка",
        "Майкаин", "Щербакты", "Успенка", "Качиры", "Лебяжье",
        "Иртышск", "Актогай", "Шидерты", "Аксуат", "Чалдай",
        "Ертис", "Аккулы", "Коктобе", "Железинский", "Павлодарский"
    };

    private double[] smartGeocode(String problemAddress,
                                  java.util.List<java.util.Map<String, Object>> msgs) {
        // Собираем весь текст переписки
        StringBuilder convBuilder = new StringBuilder();
        for (java.util.Map<String, Object> m : msgs) {
            Object txt = m.get("text");
            if (txt != null) convBuilder.append(" ").append(txt);
        }
        String convText = convBuilder.toString();

        // Ищем упоминание населённого пункта Павлодарской области
        // сначала в явном адресе места проблемы, потом во всей переписке
        String cityHint = "";
        String searchIn = problemAddress + " " + convText;
        for (String place : PAVLODAR_PLACES) {
            if (searchIn.toLowerCase().contains(place.toLowerCase())) {
                cityHint = place;
                break;
            }
        }

        // Если явный адрес пуст — пробуем извлечь место из переписки по ключевым паттернам
        String effectiveAddress = problemAddress.trim();
        if (effectiveAddress.isEmpty() || effectiveAddress.equals("не указано")) {
            effectiveAddress = extractLocationFromText(convText);
        }

        // Список запросов от наиболее конкретного к общему
        java.util.List<String> queries = new java.util.ArrayList<>();
        if (!effectiveAddress.isEmpty()) {
            if (!cityHint.isEmpty() && !effectiveAddress.toLowerCase().contains(cityHint.toLowerCase())) {
                queries.add(effectiveAddress + ", " + cityHint + ", Павлодарская область, Казахстан");
            }
            queries.add(effectiveAddress + ", Павлодарская область, Казахстан");
        }
        if (!cityHint.isEmpty()) {
            queries.add(cityHint + ", Павлодарская область, Казахстан");
        }

        for (String q : queries) {
            double[] result = tryNominatim(q);
            if (result != null) {
                android.util.Log.d("Geocoding", "✓ " + result[0] + "," + result[1] + " ← " + q);
                return result;
            }
        }

        // Fallback: просим OpenAI определить место.
        // GPT возвращает либо LAT:x,LNG:y (для объектов — обходим Nominatim),
        // либо адресную строку (для улиц — передаём в Nominatim).
        String aiAddr = aiGeocode(problemAddress, convText);
        if (!aiAddr.isEmpty()) {
            if (aiAddr.startsWith("LAT:")) {
                try {
                    String[] parts = aiAddr.replace("LAT:", "").split(",LNG:");
                    double lat = Double.parseDouble(parts[0].trim());
                    double lng = Double.parseDouble(parts[1].trim());
                    if (lat != 0 && lng != 0
                            && lat >= 50.0 && lat <= 55.0
                            && lng >= 72.5 && lng <= 83.5) {
                        android.util.Log.d("Geocoding", "✓ AI direct coords: " + lat + "," + lng);
                        return new double[]{lat, lng};
                    }
                } catch (Exception ignored) {}
            }
            double[] r = tryNominatim(aiAddr);
            if (r != null) {
                android.util.Log.d("Geocoding", "✓ AI geocode: " + r[0] + "," + r[1] + " ← " + aiAddr);
                return r;
            }
            String aiAddrObl = aiAddr.replaceAll(",?\\s*Казахстан$", "").trim()
                    + ", Павлодарская область, Казахстан";
            r = tryNominatim(aiAddrObl);
            if (r != null) {
                android.util.Log.d("Geocoding", "✓ AI+oblast: " + r[0] + "," + r[1]);
                return r;
            }
        }

        android.util.Log.w("Geocoding", "Координаты не найдены. МестоПроблемы: " + problemAddress);
        return new double[]{0, 0};
    }

    /**
     * Запрашивает у GPT координаты или адрес места проблемы.
     * Для известных объектов GPT возвращает LAT:x,LNG:y напрямую (обход Nominatim).
     * Для уличных адресов возвращает строку для Nominatim.
     */
    private String aiGeocode(String problemAddress, String convText) {
        try {
            String userContent = "Место проблемы: " + problemAddress + "\n\nДиалог:\n"
                    + convText.substring(0, Math.min(convText.length(), 2000));

            JSONObject body = new JSONObject();
            body.put("model", "gpt-4.1-mini");
            body.put("temperature", 0);
            body.put("max_tokens", 60);

            JSONArray msgs = new JSONArray();
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content",
                "Ты — геокодировщик для Павлодарской области Казахстана.\n" +
                "Из текста определи ТОЧНОЕ место проблемы и ответь ОДНОЙ строкой в одном из двух форматов:\n\n" +
                "ФОРМАТ 1 — если место является известным объектом (пляж, набережная, парк, сквер, " +
                "рынок, торговый центр, школа, детский сад, больница, поликлиника, стадион, " +
                "аэропорт, вокзал, мечеть, церковь, кинотеатр, дворец культуры, университет, " +
                "колледж, акимат, суд, полиция, пожарная часть, завод, фабрика, " +
                "любой топоним или инфраструктурный объект):\n" +
                "LAT:52.2873,LNG:76.9674\n" +
                "Используй свои знания о реальных координатах этого объекта в Павлодарской области.\n\n" +
                "ФОРМАТ 2 — если место задано точным адресом улицы:\n" +
                "ул. Ленина 45, Павлодар, Казахстан\n\n" +
                "Без пояснений. Только одна строка."
            );
            msgs.put(sys);
            JSONObject usr = new JSONObject();
            usr.put("role", "user");
            usr.put("content", userContent);
            msgs.put(usr);
            body.put("messages", msgs);

            RequestBody requestBody = RequestBody.create(
                    body.toString(), MediaType.parse("application/json"));
            Request request = new Request.Builder()
                    .url(OPENAI_URL)
                    .post(requestBody)
                    .addHeader("Authorization", "Bearer " + BuildConfig.OPENAI_API_KEY)
                    .addHeader("Content-Type", "application/json")
                    .build();

            try (Response resp = httpClient.newCall(request).execute()) {
                if (resp.isSuccessful() && resp.body() != null) {
                    JSONObject json = new JSONObject(resp.body().string());
                    String result = json.getJSONArray("choices").getJSONObject(0)
                            .getJSONObject("message").getString("content").trim();
                    android.util.Log.d("Geocoding", "AI extracted: " + result);
                    return result;
                }
            }
        } catch (Exception e) {
            android.util.Log.w("Geocoding", "aiGeocode failed: " + e.getMessage());
        }
        return "";
    }

    /**
     * Пытается извлечь адрес места проблемы из свободного текста переписки.
     * Ищет паттерны: «ул. Ленина», «улица Карла Маркса», «на проспекте», «в районе», «в посёлке» и т.п.
     */
    private String extractLocationFromText(String text) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
            "(?i)(ул\\.?\\s+\\S+|улица\\s+\\S+(?:\\s+\\S+)?|пр\\.?\\s+\\S+|проспект\\s+\\S+(?:\\s+\\S+)?" +
            "|пер\\.?\\s+\\S+|переулок\\s+\\S+|мкр\\.?\\s+\\S+|микрорайон\\s+\\S+" +
            "|(?:в|на)\\s+(?:районе?|р-не?|посёлке|поселке|с\\.?|селе)\\s+\\S+(?:\\s+\\S+)?)"
        );
        java.util.regex.Matcher m = pattern.matcher(text);
        StringBuilder found = new StringBuilder();
        while (m.find()) {
            if (found.length() > 0) found.append(", ");
            found.append(m.group().trim());
            if (found.length() > 80) break;
        }
        return found.toString();
    }

    private double[] tryNominatim(String query) {
        try {
            String encoded = java.net.URLEncoder.encode(query, "UTF-8");
            String url = "https://nominatim.openstreetmap.org/search?q=" + encoded
                    + "&format=json&limit=1&countrycodes=kz"
                    + "&viewbox=72.5%2C50.0%2C83.5%2C55.0&bounded=0";
            okhttp3.Request req = new okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", "PavlodarAssistantApp/2.0 (geocoding)")
                    .build();
            try (okhttp3.Response resp = httpClient.newCall(req).execute()) {
                if (resp.isSuccessful() && resp.body() != null) {
                    String body = resp.body().string();
                    org.json.JSONArray arr = new org.json.JSONArray(body);
                    if (arr.length() > 0) {
                        org.json.JSONObject first = arr.getJSONObject(0);
                        double lat = Double.parseDouble(first.getString("lat"));
                        double lng = Double.parseDouble(first.getString("lon"));
                        return new double[]{lat, lng};
                    }
                }
            }
        } catch (Exception e) {
            android.util.Log.w("Geocoding", "tryNominatim: " + e.getMessage());
        }
        return null;
    }

    // ────────────────────────────────────────────────────────────────────────

    /** Голос OpenAI TTS — onyx (глубокий мужской) для всех языков. */
    private String voiceForLang(String lang) {
        return "onyx";
    }

    private void speak(String text, String lang) {
        stopAllSpeech();
        speakViaOpenAI(kz.kitdev.util.TtsPreprocessor.prepare(text, lang), voiceForLang(lang), null);
    }

    private void stopAllSpeech() {
        pendingKkContinuation = null;
        ttsSession++;
        AudioTrack track = currentAudioTrack;
        currentAudioTrack = null;
        if (track != null) {
            try { track.pause(); track.flush(); } catch (Exception ignored) {}
            try { track.stop(); }               catch (Exception ignored) {}
            try { track.release(); }            catch (Exception ignored) {}
        }
        if (tts != null) tts.stop();
        abandonAudioFocus();
    }

    // ── Audio focus helpers ──────────────────────────────────────────

    @SuppressWarnings("deprecation")
    private void requestAudioFocus() {
        if (audioManager != null) {
            audioManager.requestAudioFocus(null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
        }
    }

    @SuppressWarnings("deprecation")
    private void abandonAudioFocus() {
        if (audioManager != null) audioManager.abandonAudioFocus(null);
    }

    // ══════════════════════════════════════════════════════════════════
    //  OpenAI TTS — PCM + AudioTrack MODE_STATIC (все языки)
    //
    //  speakOpenAIPrepareAndShow — для первого ответа: скачивает аудио,
    //  затем АТОМАРНО показывает текст + запускает воспроизведение.
    //  Аватар переходит в SPEAKING в тот же момент → полная синхронизация.
    //
    //  speakViaOpenAI — для повторного воспроизведения / продолжений.
    //
    //  Голоса: nova (kk) · shimmer (ru) · alloy (en)  — tts-1-hd качество
    // ══════════════════════════════════════════════════════════════════

    /**
     * Скачивает аудио, затем одновременно:
     * – показывает ответ в чате
     * – включает видео-аватар (SPEAKING)
     * – начинает воспроизведение
     * Таким образом текст и голос появляются синхронно, без разрыва.
     */
    /** ttsText — предобработанный текст для озвучки; answer — оригинал для отображения */
    private void speakOpenAIPrepareAndShow(String ttsText, String answer, String question, String voice) {
        final int session = ttsSession;

        new Thread(() -> {
            byte[] fullData = downloadOpenAiPcm(ttsText, voice, session);
            if (fullData == null || ttsSession != session) {
                // Ошибка сети — показываем текст без озвучки
                runOnUiThread(() -> {
                    if (ttsSession != session) return;
                    isLoading = false; setInputEnabled(true);
                    adapter.replaceLastWithAnswer(answer, question);
                    scrollToBottom();
                    setAiState(AiState.IDLE);
                });
                return;
            }

            // Создаём AudioTrack ещё в фоне — полностью готов до старта
            AudioTrack track = buildStaticTrack(fullData);
            if (track == null || ttsSession != session) {
                if (track != null) track.release();
                return;
            }
            track.write(fullData, 0, fullData.length);

            final long durationMs = (long) fullData.length * 1000L / (24000L * 2);
            final AudioTrack ft = track;

            // Всё готово → переходим на главный поток
            runOnUiThread(() -> {
                if (ttsSession != session) { ft.release(); return; }

                // ── Всё три действия выполняются за один кадр ──────────────
                isLoading = false;
                setInputEnabled(true);
                adapter.replaceLastWithAnswer(answer, question); // текст в чате
                scrollToBottom();
                setAiState(AiState.SPEAKING);   // видео начинает играть
                requestAudioFocus();
                currentAudioTrack = ft;
                ft.play();                      // аудио начинается одновременно

                // Завершение — либо продолжение, либо IDLE
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (ttsSession != session) return;
                    ttsSession++;
                    currentAudioTrack = null;
                    try { ft.stop(); } catch (Exception ignored) {}
                    try { ft.release(); } catch (Exception ignored) {}
                    abandonAudioFocus();
                    if (pendingKkContinuation != null) {
                        String next = pendingKkContinuation;
                        pendingKkContinuation = null;
                        speakViaOpenAI(next, voice, null);
                    } else {
                        setAiState(AiState.IDLE);
                    }
                }, durationMs + 300);
            });
        }).start();
    }

    /**
     * Воспроизведение OpenAI TTS (повторное / продолжение).
     * onReady (nullable) вызывается прямо перед track.play().
     */
    private void speakViaOpenAI(String text, String voice, Runnable onReady) {
        final int session = ttsSession;

        new Thread(() -> {
            byte[] fullData = downloadOpenAiPcm(text, voice, session);
            if (fullData == null || ttsSession != session) {
                runOnUiThread(() -> { if (ttsSession == session) setAiState(AiState.IDLE); });
                return;
            }

            AudioTrack track = buildStaticTrack(fullData);
            if (track == null || ttsSession != session) {
                if (track != null) track.release();
                return;
            }
            track.write(fullData, 0, fullData.length);

            final long durationMs = (long) fullData.length * 1000L / (24000L * 2);
            final AudioTrack ft = track;

            runOnUiThread(() -> {
                if (ttsSession != session) { ft.release(); return; }
                if (onReady != null) onReady.run();
                requestAudioFocus();
                currentAudioTrack = ft;
                ft.play();

                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (ttsSession != session) return;
                    ttsSession++;
                    currentAudioTrack = null;
                    try { ft.stop(); } catch (Exception ignored) {}
                    try { ft.release(); } catch (Exception ignored) {}
                    abandonAudioFocus();
                    if (pendingKkContinuation != null) {
                        String next = pendingKkContinuation;
                        pendingKkContinuation = null;
                        speakViaOpenAI(next, voice, null);
                    } else {
                        setAiState(AiState.IDLE);
                    }
                }, durationMs + 300);
            });
        }).start();
    }

    /**
     * Скачивает PCM с OpenAI TTS (tts-1-hd) и добавляет 200мс тишины в начале.
     * Тишина нужна: DAC «просыпается» на нулевом сигнале → нет щелчка.
     * @return fullData (silence + pcm) или null при ошибке/отмене
     */
    private byte[] downloadOpenAiPcm(String text, String voice, int session) {
        try {
            JSONObject body = buildTtsBody(text, voice);

            RequestBody rb = RequestBody.create(
                    body.toString(), MediaType.parse("application/json"));
            Request req = new Request.Builder()
                    .url(OPENAI_TTS_URL)
                    .addHeader("Authorization", "Bearer " + BuildConfig.OPENAI_API_KEY)
                    .post(rb).build();

            Response resp = httpClient.newCall(req).execute();
            if (!resp.isSuccessful() || resp.body() == null) return null;

            ByteArrayOutputStream baos = new ByteArrayOutputStream(65536);
            try (InputStream is = resp.body().byteStream()) {
                byte[] chunk = new byte[8192]; int n;
                while ((n = is.read(chunk)) != -1) {
                    if (ttsSession != session) return null;
                    baos.write(chunk, 0, n);
                }
            }
            if (ttsSession != session) return null;
            byte[] pcmData = baos.toByteArray();
            if (pcmData.length == 0) return null;

            // 200мс тишины перед речью: DAC стартует на нулевом сигнале → нет шума
            final int RATE         = 24000;
            final int silenceBytes = 200 * RATE * 2 / 1000; // 9 600 байт
            byte[] fullData = new byte[silenceBytes + pcmData.length];
            System.arraycopy(pcmData, 0, fullData, silenceBytes, pcmData.length);
            return fullData;

        } catch (Exception e) {
            return null;
        }
    }

    /** Создаёт AudioTrack (MODE_STATIC) заданного размера */
    private AudioTrack buildStaticTrack(byte[] fullData) {
        try {
            return new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(24000)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(fullData.length)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();
        } catch (Exception e) {
            return null;
        }
    }

    // ── Построение тела запроса к OpenAI TTS ────────────────────────

    private JSONObject buildTtsBody(String text, String voice) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", "tts-1-hd");
        body.put("voice", voice);
        body.put("input", text);
        body.put("response_format", "pcm");
        body.put("speed", 0.95);
        return body;
    }

    // ── Android TTS — рус/англ ───────────────────────────────────────

    private void setupTts() {
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setSpeechRate(0.9f);
                tts.setPitch(1.05f);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) {}
                    @Override public void onDone(String id) {
                        // Аватар переходит в IDLE когда Android TTS реально закончил
                        runOnUiThread(() -> {
                            if (aiState == AiState.SPEAKING) setAiState(AiState.IDLE);
                            abandonAudioFocus();
                        });
                    }
                    @Override public void onError(String id) {
                        runOnUiThread(() -> {
                            setAiState(AiState.IDLE);
                            abandonAudioFocus();
                        });
                    }
                });
            }
        }, "com.google.android.tts");
    }

    private void speakViaAndroidTts(String text, String lang) {
        if (tts == null || text.isEmpty()) { setAiState(AiState.IDLE); return; }
        Locale locale = "en".equals(lang) ? Locale.ENGLISH
                : "kk".equals(lang)       ? new Locale("kk")
                : new Locale("ru");
        int r = tts.setLanguage(locale);
        if (r == TextToSpeech.LANG_NOT_SUPPORTED || r == TextToSpeech.LANG_MISSING_DATA)
            tts.setLanguage(new Locale("ru"));

        requestAudioFocus();
        Bundle params = new Bundle();
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, TTS_UTT_ID);
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, TTS_UTT_ID);
        // IDLE наступит в UtteranceProgressListener.onDone (точное время)
    }

    // ══════════════════════════════════════════════════════════════════
    //  Языковое определение, промпт, сохранение
    // ══════════════════════════════════════════════════════════════════

    private String detectMessageLang(String text) {
        // Whisper определил язык из аудио точнее текстовой эвристики
        if (voiceInputLang != null) {
            String lang = voiceInputLang;
            voiceInputLang = null;
            return lang;
        }
        if (text == null || text.isEmpty()) return "ru";
        String lower = text.toLowerCase();
        if (lower.matches(".*[ғқңөүұіәһ].*")) return "kk";
        long lat = 0, cyr = 0;
        for (char c : lower.toCharArray()) {
            if (c >= 'a' && c <= 'z') lat++;
            else if ((c >= '\u0430' && c <= '\u044f') || c == '\u0451') cyr++;
        }
        if (lat > cyr && lat > 2) return "en";
        // Возвращаем язык интерфейса — если не английский, используем текущий UI-язык
        String uiLang = LangManager.get(this);
        return "en".equals(uiLang) ? "ru" : uiLang;
    }

    /**
     * Из 5 кандидатов STT выбирает наиболее подходящий.
     * Если UI на казахском или в поле уже есть кз буквы — приоритет результату с казахскими буквами.
     * Иначе — первый кандидат (наивысшая уверенность движка).
     */
    private String selectBestSpeechResult(ArrayList<String> matches) {
        if (matches == null || matches.isEmpty()) return "";
        if (matches.size() == 1) return matches.get(0);
        String inputText = binding.etInput.getText().toString();
        boolean kkPrimary = "kk".equals(LangManager.get(this))
                            || inputText.matches(".*[ғқңөүұіәһ].*");
        if (kkPrimary) {
            for (String c : matches) {
                if (c.matches(".*[ғқңөүұіәһ].*")) return c;
            }
        }
        return matches.get(0);
    }

    private String buildSystemPrompt(String lang, String similarContext) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Ты — ИИ-помощник акимата Павлодарской области Казахстана.\n\n")
                .append("ЗОНА РАБОТЫ: только Павлодарская область (г. Павлодар, г. Экибастуз, г. Аксу ")
                .append("и все районы области).\n\n")
                .append("ЛОГИКА:\n")
                .append("• Приветствие (здравствуйте, сәлеметсіз бе, привет, сәлем, hi, hello и т.п.):\n")
                .append("   — Только приветствие без проблемы → ответь приветствием на том же языке и коротко предложи помощь.\n")
                .append("   — Приветствие + проблема в одном сообщении → сначала коротко поздоровайся, ")
                .append("затем сразу переходи к алгоритму жалобы/заявления без лишних вопросов.\n")
                .append("• Простой вопрос → ответь коротко (3-5 предложений), без запроса контактов.\n")
                .append("• Жалоба/заявление (ЖКХ, дороги, освещение, мусор, школа, больница, экология, ")
                .append("безопасность, благоустройство и др.) → алгоритм:\n")
                .append("  1. «Основная проблема: [суть]»\n")
                .append("  2. Уточни место нахождения ПРОБЛЕМЫ:\n")
                .append("     — Пользователь назвал конкретное место (улица, дом, школа, парк, объект, село) → ")
                .append("СНАЧАЛА проверь: реально ли такое место существует в Павлодарской области?\n")
                .append("       • Если место существует → выведи: «Место проблемы: [место]»\n")
                .append("       • Если место НЕ существует (несуществующая улица, несуществующий номер ")
                .append("школы/детсада/больницы/парка и т.п.) → скажи: «[Название] не существует в ")
                .append("[городе/районе]. Уточните точный адрес места проблемы.» — жди исправления. ")
                .append("НЕ переходи к следующему шагу пока адрес не будет реальным.\n")
                .append("     — Место не указано → спроси: «Уточните адрес места проблемы: улицу, дом или район.» — жди ответа.\n")
                .append("     ВАЖНО: «Место проблемы» — ТОЛЬКО физическое место где находится проблема,\n")
                .append("     НЕ адрес государственного органа, НЕ домашний адрес заявителя.\n")
                .append("  3. Напиши вежливо: «Вы можете обратиться в [орган]» + телефон из списка (только если подходит):\n")
                .append("     • ЖКХ, коммуналка, отопление → Упр. энергетики и ЖКХ области: 8(7182) 65-33-17\n")
                .append("     • Дороги, освещение, транспорт (город) → Отдел ЖКХ, ПТ и АД г. Павлодара: 8(7182) 32-22-60\n")
                .append("     • Дороги (область) → Упр. автодорог области: 8(7182) 32-30-32\n")
                .append("     • Жилищная инспекция (УК, состояние дома) → 8(7182) 65-04-60\n")
                .append("     • Жилищные отношения (очереди, соц. жильё) → 8(7182) 32-05-68\n")
                .append("     • Экология → Департамент экологии: 8(7182) 53-29-10\n")
                .append("     • Здравоохранение → Упр. здравоохранения: 8(7182) 32-50-02\n")
                .append("     • Соцзащита, пособия → Отдел занятости: 8(7182) 32-33-32\n")
                .append("     • Земельные вопросы → 8(7182) 73-05-82\n")
                .append("     • Обращения в акимат → 8(7182) 65-04-28\n")
                .append("     • Онлайн → e-otinish.kz / egov.kz\n")
                .append("     • Если орган неизвестен → 1414 (бесплатно). НЕ придумывай номера.\n")
                .append("  4. «Для регистрации — укажите ваше ФИО и номер телефона.»\n")
                .append("  5. Получив номер — проверь:\n")
                .append("     КЗ номер — 11 цифр: +7 7XX XXX XX XX или 87XX XXX XX XX.\n")
                .append("     Операторы КЗ: 700, 701, 702, 705, 706, 707, 708, 747, 771, 775, 776, 777, 778.\n")
                .append("     Примеры верных: +7 705 123 45 67 / +7 747 123 45 67 / 87011234567\n")
                .append("     Отклоняй только явно неполный номер (меньше 10 цифр) или случайные символы.\n")
                .append("     Пробелы, дефисы, скобки — нормально, не отклоняй из-за форматирования.\n")
                .append("  5б. ФИО — принимай любое имя из 2+ слов (казахские, русские, любые).\n")
                .append("      Отклоняй только если явно не имя (одна буква или цифры).\n")
                .append("  6. Только после ФИО + номера → выведи СТРОГО:\n")
                .append("     ##ЗАЯВКА_ГОТОВА## ФИО: [фио] | Телефон: [телефон] | Проблема: [суть] | МестоПроблемы: [геокодируемый адрес места проблемы]\n")
                .append("     В МестоПроблемы — ТОЧНЫЙ адрес для поиска на карте. Правила:\n")
                .append("       • Школа/детсад/больница/поликлиника → «Школа №15, ул. Ленина, Павлодар»\n")
                .append("       • Рынок/ТЦ/парк/стадион/акимат/другой объект → «Рынок Аян, ул. Торайгырова, Павлодар»\n")
                .append("       • Улица/дом → «ул. Карла Маркса 45, Экибастуз»\n")
                .append("       • Микрорайон → «мкр. Химки, Павлодар»\n")
                .append("       • Посёлок/село → «с. Успенка, Успенский район, Павлодарская область»\n")
                .append("       ВСЕГДА добавляй название города или посёлка Павлодарской области.\n")
                .append("       НЕ пиши адрес акимата, суда или любого другого органа — только место проблемы.\n")
                .append("     Затем: «Заявка зарегистрирована. Ожидайте ответа.»\n\n")
                .append("БАЗА ЗНАНИЙ (типичные обращения жителей):\n")
                .append("• Уличное освещение → Ваша заявка принята, специалистами отдела ЖКХ будет произведено обследование согласно Гл.5 Правил благоустройства (adilet.zan.kz/rus/docs/G24P013414M).\n")
                .append("• Порыв трубы/авария водоснабжения → аварийная служба КСК/ОСИ; Павлодар-водоканал: 60-45-68, 57-24-72; 109.\n")
                .append("• Подпор канализации → КСК/ОСИ устраняет за 24–48 ч; Павлодар-водоканал: 60-45-68; 109.\n")
                .append("• Отключение электричества → Горэлектросеть: 61-06-06, 61-38-38; ПРЭК: 32-20-22; 112.\n")
                .append("• Нет отопления/горячей воды → КСК/ОСИ; плановые отключения — по графику АО «Павлодарэнерго»; Упр. энергетики и ЖКХ: 65-33-17.\n")
                .append("• Некачественный ремонт после коммунальных работ → Отдел жилищной инспекции, ул. Кривенко 25; п.101 Правил благоустройства: нарушенные элементы подлежат восстановлению подрядчиком.\n")
                .append("• Затопление квартиры → составьте акт с КСК/ОСИ; виновная сторона возмещает ущерб по Закону о жилищных отношениях (adilet.zan.kz/rus/docs/Z970000094_).\n")
                .append("• Ремонт крыши/подвала МЖД → Отдел жилищной инспекции, ул. Кривенко 25, тел. 32-55-05; или ТОО «Горкомхоз модернизация жилья».\n")
                .append("• Переход в ОСИ → ст.42, 43 Закона «О жилищных отношениях» (adilet.zan.kz/rus/docs/Z970000094_).\n")
                .append("• Очередь на жильё → п.1 ст.67 Закона о жилищных отношениях; постановка с ЭЦП на orken.otbasybank.kz.\n")
                .append("• Аварийное жильё → экспертное заключение → Отдел жилищных отношений города Павлодара.\n")
                .append("• Теплоснабжение частного сектора → «Павлодарские тепловые сети», ул. Камзина 149.\n")
                .append("• Санитарная обрезка деревьев → осенне-весенний период по Приказу № 62 от 23.02.2023; КСК/ОСИ подаёт заявку в ЖКХ с согласием 3/4 жителей.\n")
                .append("• Детская/спортивная площадка, тренажёры → включат в график на 2026 год после обследования территории с коммунальными службами.\n")
                .append("• Асфальтирование → депутат + МИО выедут; по итогам — бюджетная комиссия.\n")
                .append("• ИДН, дорожные знаки → депутат + МИО + полиция выедут; по итогам — бюджетная комиссия.\n")
                .append("• Ремонт остановки → Ваша заявка принята, специалистами ЖКХ будет произведено обследование; при необходимости — бюджетная комиссия.\n")
                .append("• Светофор → Управление полиции + ЖКХ проведут обследование; при необходимости — бюджетная комиссия.\n")
                .append("• Ямочный ремонт → депутат + отдел ЖКХ выедут; по итогам — бюджетная комиссия.\n")
                .append("• Лавочки, урны → за счёт собственников жилья; в бюджете МИО не предусмотрено.\n")
                .append("• Крупногабаритный мусор → ТОО «Горкомхоз-Павлодар» раз в месяц + по заявкам.\n")
                .append("• Незаконная парковка → ст.597 КоАП РК; Управление административной полиции; п.98 Правил благоустройства.\n")
                .append("• Бродячие собаки → WhatsApp ветстанции: 8708 277 31 19; Правила отлова животных (adilet.zan.kz/rus/docs/G22P018114M).\n")
                .append("• Соцпомощь многодетным, неполным семьям → Отдел занятости: ул. Кривенко 25, тел. 32-33-32; решение маслихата № 65/8.\n")
                .append("• Помощь на уголь → инвалиды 1–3 гр., многодетные, малообеспеченные с печным отоплением; решение маслихата № 65/8.\n")
                .append("• Кредиты для бизнеса → Карьерный центр, ул. Бектурова 115В (безвозмездно); АО «Фонд ДАМУ» (под залог).\n")
                .append("• Принятие в детсад без прививок → при доле непривитых >10% детсад вправе отказать (п.8 Правил допуска, adilet.zan.kz/rus/docs/V2000021832).\n")
                .append("• Летние лагеря → список на сайте Управления образования, gov.kz.\n")
                .append("• Земельный участок под ИЖС → egov.kz «Постановка на очередь под ИЖС».\n")
                .append("• Экологический мониторинг → Департамент экологии ПО по Экологическому кодексу РК (adilet.zan.kz/rus/docs/K2100000400).\n")
                .append("• Мониторинг цен → Отдел предпринимательства согласно ст.9 Закона о торговой деятельности.\n")
                .append("• Служба 109 → принимает обращения по ЖКХ и госуслугам; экстренные вызовы — 112.\n\n")
                .append("ЯЗЫК: Отвечай СТРОГО на ").append("ru".equals(lang) ? "русском" : "kk".equals(lang) ? "казахском" : "английском")
                .append(" языке. Если сообщение написано на нескольких языках или смешанное — всё равно отвечай только на ").append("ru".equals(lang) ? "русском" : "kk".equals(lang) ? "казахском" : "английском").append(" языке. ")
                .append("Ответы разговорные, краткие.");

        return prompt.toString();
    }

    private void saveToFirestore(String uid, String question, String answer) {
        String lang = answer.matches(".*[ғқңөүұіәһ].*") ? "kk"
                : detectMessageLang(question);
        Map<String, Object> data = new HashMap<>();
        data.put("question",  question);
        data.put("answer",    answer);
        data.put("lang",      lang);
        data.put("createdAt", com.google.firebase.Timestamp.now());
        FirebaseFirestore.getInstance()
                .collection("users").document(uid)
                .collection("history").add(data);
    }

    // ----------------------------------------------------------------
    //  Global queries — сохранение и загрузка похожих запросов
    // ----------------------------------------------------------------

    private void saveToGlobalQueries(String question, String answer, String lang) {
        Map<String, Object> data = new HashMap<>();
        data.put("question",  question);
        data.put("answer",    answer);
        data.put("lang",      lang);
        data.put("createdAt", com.google.firebase.Timestamp.now());
        FirebaseFirestore.getInstance()
                .collection("global_queries")
                .add(data);
    }

    interface SimilarQueriesCallback {
        void onReady(String context);
    }

    private void loadSimilarQueries(String question, SimilarQueriesCallback callback) {
        FirebaseFirestore.getInstance()
                .collection("global_queries")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(30)
                .get()
                .addOnSuccessListener(snapshots -> {
                    String[] tokens = question.toLowerCase().split("[\\s,.:;!?()\"']+");
                    List<String[]> candidates = new ArrayList<>();

                    for (QueryDocumentSnapshot doc : snapshots) {
                        String q = doc.getString("question");
                        String a = doc.getString("answer");
                        if (q == null || a == null || q.isEmpty()) continue;
                        String qLow = q.toLowerCase();
                        int score = 0;
                        for (String token : tokens) {
                            if (token.length() >= 4 && qLow.contains(token)) score++;
                        }
                        if (score > 0) candidates.add(new String[]{String.valueOf(score), q, a});
                    }

                    candidates.sort((x, y) -> Integer.parseInt(y[0]) - Integer.parseInt(x[0]));

                    if (candidates.isEmpty()) {
                        callback.onReady("");
                        return;
                    }

                    StringBuilder ctx = new StringBuilder();
                    ctx.append("ОПЫТ ПРОШЛЫХ ЗАПРОСОВ (используй как ориентир, не как источник закона; ")
                       .append("всегда применяй актуальные нормы права):\n");
                    int limit = Math.min(3, candidates.size());
                    for (int i = 0; i < limit; i++) {
                        String[] entry = candidates.get(i);
                        String answerPreview = entry[2].length() > 300
                                ? entry[2].substring(0, 300) + "…"
                                : entry[2];
                        ctx.append(i + 1).append(". В: ").append(entry[1])
                           .append("\n   О: ").append(answerPreview).append("\n");
                    }
                    callback.onReady(ctx.toString());
                })
                .addOnFailureListener(e -> callback.onReady(""));
    }

    private void showSaveNotification() {
        View notify = LayoutInflater.from(this).inflate(R.layout.notify_saved, null);
        TextView tv = notify.findViewById(R.id.tvNotifyText);
        tv.setText(getString(R.string.history_saved));
        ViewGroup dv = (ViewGroup) getWindow().getDecorView();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        notify.setAlpha(0f);
        dv.addView(notify, lp);
        notify.animate().alpha(1f).setDuration(200)
                .withEndAction(() -> notify.postDelayed(() ->
                        notify.animate().alpha(0f).setDuration(400)
                                .withEndAction(() -> dv.removeView(notify)).start(), 1200))
                .start();
    }
}
