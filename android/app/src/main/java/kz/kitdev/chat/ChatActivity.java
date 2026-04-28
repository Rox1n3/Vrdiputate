package kz.kitdev.chat;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
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
import kz.kitdev.ThemeManager;
import kz.kitdev.auth.LoginActivity;
import kz.kitdev.databinding.ActivityChatBinding;
import kz.kitdev.fcm.AppFirebaseMessagingService;
import kz.kitdev.profile.ProfileActivity;
import kz.kitdev.util.PhotoHelper;
import kz.kitdev.util.StorageUploader;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ChatActivity extends BaseActivity {

    private static final int PERM_MIC   = 100;
    private static final int PERM_NOTIF = 101;

    private PhotoHelper photoHelper;
    // Фото выбрано, но ещё не отправлено — ждёт текстового подписи от пользователя
    private android.net.Uri pendingPhotoUri = null;
    private ChatMessage     pendingImgMsg   = null;
    private static final String OPENAI_URL     = "https://api.openai.com/v1/chat/completions";
    private static final String OPENAI_TTS_URL = "https://api.openai.com/v1/audio/speech";

    private static final String KEY_MESSAGES    = "chat_messages";
    private static final String KEY_LAST_Q      = "last_question";
    private static final String KEY_LAST_A      = "last_answer";
    private static final String KEY_TTS_ENABLED  = "tts_enabled";
    private static final String KEY_WAS_LISTENING = "was_listening";

    private ActivityChatBinding binding;
    private ChatAdapter adapter;
    private OkHttpClient httpClient;

    private String currentLang = "ru";
    private String lastQuestion = "";
    private String lastAnswer   = "";
    private boolean ttsEnabled  = true;
    private boolean isLoading   = false;

    /** True while mic is active (only second tap stops it) */
    private boolean isListening = false;
    private String  voiceAccumulated = ""; // текст, накопленный за предыдущие сессии распознавания

    /** Продолжение казахского TTS (текст после первого кусочка) */
    private String pendingKkContinuation = null;

    // Android TTS — used for Russian / English
    private TextToSpeech tts;

    // AudioTrack — used for Kazakh (PCM + 200ms silence pre-roll → zero DAC startup noise)
    private volatile AudioTrack currentAudioTrack;
    /** Session counter — increments on every stopAllSpeech() to cancel stale callbacks */
    private volatile int ttsSession = 0;

    // SpeechRecognizer voice input
    private SpeechRecognizer speechRecognizer;
    private boolean kkSttUnavailable = false; // silent fallback: kk-KZ → ru-RU
    private int     networkErrorCount = 0;    // счётчик сетевых ошибок для fallback
    /** Язык, определённый по локали распознавателя — приоритет над текстовой эвристикой */
    private String voiceInputLang = null;

    // Audio focus
    private AudioManager audioManager;

    // ----------------------------------------------------------------
    //  Lifecycle
    // ----------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityChatBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Connection pool keeps the TLS session to api.openai.com alive so the
        // TTS request reuses the same connection as the LLM call — saves ~150-300 ms handshake
        httpClient = new OkHttpClient.Builder()
                .connectionPool(new okhttp3.ConnectionPool(5, 5, TimeUnit.MINUTES))
                .build();
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        currentLang = LangManager.get(this);

        setupToolbar();
        setupBottomNav();
        setupRecyclerView();
        setupInputBar();
        setupTts();

        if (savedInstanceState != null) {
            @SuppressWarnings("unchecked")
            ArrayList<ChatMessage> saved =
                    (ArrayList<ChatMessage>) savedInstanceState.getSerializable(KEY_MESSAGES);
            if (saved != null && !saved.isEmpty()) {
                adapter.restoreMessages(saved);
                scrollToBottom();
            }
            lastQuestion = savedInstanceState.getString(KEY_LAST_Q, "");
            lastAnswer   = savedInstanceState.getString(KEY_LAST_A, "");
            ttsEnabled   = savedInstanceState.getBoolean(KEY_TTS_ENABLED, true);
            updateTtsIcon();
            // Восстанавливаем голосовой ввод если был активен до смены языка
            if (savedInstanceState.getBoolean(KEY_WAS_LISTENING, false)) {
                requestMicPermission();
            }
        }

        String prefill = getIntent().getStringExtra("prefill_question");
        if (prefill != null && !prefill.isEmpty()) {
            binding.etInput.setText(prefill);
        }

        // Android 13+ требует явного разрешения POST_NOTIFICATIONS
        requestNotificationPermission();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putSerializable(KEY_MESSAGES, adapter.getMessages());
        outState.putString(KEY_LAST_Q, lastQuestion);
        outState.putString(KEY_LAST_A, lastAnswer);
        outState.putBoolean(KEY_TTS_ENABLED, ttsEnabled);
        outState.putBoolean(KEY_WAS_LISTENING, isListening);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopAllSpeech();
        if (tts != null) { tts.shutdown(); }
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
    }

    // ----------------------------------------------------------------
    //  Toolbar
    // ----------------------------------------------------------------

    private void setupToolbar() {
        binding.toolbar.inflateMenu(R.menu.menu_chat);
        updateThemeIcon();
        updateLangIcon();
        updateTtsIcon();
        binding.toolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_theme) {
                ThemeManager.toggle(this);
                return true;
            } else if (id == R.id.action_tts) {
                toggleTts();
                return true;
            } else if (id == R.id.action_lang) {
                showLangPopup(binding.toolbar.findViewById(R.id.action_lang));
                return true;
            }
            return false;
        });

        binding.toolbar.setTitle("");
        com.bumptech.glide.Glide.with(this)
                .load(R.drawable.ic_bitriks)
                .circleCrop()
                .into(binding.ivBitriks);
        binding.ivBitriks.setOnClickListener(v -> showBitriksInfo());
    }

    private void updateThemeIcon() {
        MenuItem themeItem = binding.toolbar.getMenu().findItem(R.id.action_theme);
        if (themeItem != null) {
            themeItem.setIcon(ThemeManager.isDark(this)
                    ? R.drawable.ic_light_mode
                    : R.drawable.ic_dark_mode);
        }
    }

    private void updateLangIcon() {
        MenuItem langItem = binding.toolbar.getMenu().findItem(R.id.action_lang);
        if (langItem != null) {
            // Показываем текст вместо флажка: рус / қаз / en
            String label = currentLang.equals("kk") ? "қаз"
                    : currentLang.equals("en") ? "en" : "рус";
            langItem.setTitle(label);
        }
    }

    private void showLangPopup(android.view.View anchor) {
        String current = LangManager.get(this);
        String[] items = { "рус  —  Русский", "қаз  —  Қазақша", "en  —  English" };
        String[] codes = { "ru", "kk", "en" };
        int checked = 0;
        for (int i = 0; i < codes.length; i++) {
            if (codes[i].equals(current)) { checked = i; break; }
        }
        new MaterialAlertDialogBuilder(this)
                .setSingleChoiceItems(items, checked, (dialog, which) -> {
                    dialog.dismiss();
                    setLang(codes[which]);
                })
                .show();
    }

    private void setLang(String lang) {
        currentLang = lang;
        LangManager.set(this, lang);
        recreate();
    }

    // ----------------------------------------------------------------
    //  Bottom nav
    // ----------------------------------------------------------------

    private void setupBottomNav() {
        binding.bottomNav.setSelectedItemId(R.id.nav_chat);
        binding.bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_history) {
                startActivity(new Intent(this, kz.kitdev.history.HistoryActivity.class));
                binding.bottomNav.setSelectedItemId(R.id.nav_chat);
                return false;
            } else if (id == R.id.nav_profile) {
                startActivity(new Intent(this, ProfileActivity.class));
                binding.bottomNav.setSelectedItemId(R.id.nav_chat);
                return false;
            }
            return true;
        });
    }

    // ----------------------------------------------------------------
    //  RecyclerView
    // ----------------------------------------------------------------

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

        // Тап по фото → полноэкранный просмотр
        adapter.setImageTapListener(this::showFullScreenPhoto);

        // Тап вне выделенного TextView → сброс выделения текста.
        // Работает как при нажатии сбоку от пузыря, так и в пустой области ниже сообщений.
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

    // ----------------------------------------------------------------
    //  Input bar
    // ----------------------------------------------------------------

    private void setupInputBar() {
        photoHelper = new PhotoHelper(this);

        binding.btnSend.setOnClickListener(v -> {
            String text = binding.etInput.getText().toString().trim();
            if (pendingPhotoUri != null) {
                // Отправляем фото вместе с подписью (текст может быть пустым)
                sendMessageWithPhoto(pendingPhotoUri, text);
            } else if (!text.isEmpty()) {
                sendMessage(text);
            }
        });

        binding.btnMic.setOnClickListener(v -> toggleVoiceInput());
        binding.btnCamera.setOnClickListener(v -> photoHelper.showPicker());

        // Кнопка удаления превью фото
        binding.btnRemovePhoto.setOnClickListener(v -> clearPhotoPreview());

        // Кнопка «Живой чат» — открывает LiveChatActivity с плавной анимацией.
        // CLEAR_TOP + SINGLE_TOP: если инстанция уже в стеке — поднимаем её (без дубля),
        // если нет — создаём новую. singleTop в манифесте + эти флаги = один экземпляр
        // И корректная работа recreate() при каждой смене языка.
        binding.btnLiveChat.setOnClickListener(v -> {
            android.content.Intent intent = new android.content.Intent(
                    this, kz.kitdev.live.LiveChatActivity.class);
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            overridePendingTransition(R.anim.live_chat_enter, R.anim.live_chat_bg_hold);
        });
    }

    // ----------------------------------------------------------------
    //  Send message → OpenAI
    // ----------------------------------------------------------------

    private void sendMessage(String text) {
        if (isLoading) return;

        // Stop any ongoing speech immediately
        stopAllSpeech();

        // Stop voice input if active
        if (isListening) stopVoiceInput();

        binding.etInput.setText("");
        adapter.addMessage(new ChatMessage(ChatMessage.TYPE_USER, text));
        adapter.addMessage(ChatMessage.loading());
        scrollToBottom();

        isLoading = true;
        setInputEnabled(false);

        callOpenAI(text);
    }

    private void callOpenAI(String question) {
        loadSimilarQueries(question, similarContext -> callOpenAIWithContext(question, similarContext));
    }

    private void callOpenAIWithContext(String question, String similarContext) {
        try {
            String detectedLang = detectMessageLang(question);
            boolean detailed = wantsDetailedAnswer(question);
            String system  = buildSystemPrompt(detectedLang, detailed, similarContext);

            JSONObject body = new JSONObject();
            body.put("model", "gpt-4.1");
            body.put("stream", true);
            body.put("temperature", 0.3);

            JSONArray msgs = new JSONArray();
            JSONObject sysMsg = new JSONObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", system);
            msgs.put(sysMsg);

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

            JSONObject usrMsg = new JSONObject();
            usrMsg.put("role", "user");
            usrMsg.put("content", question);
            msgs.put(usrMsg);
            body.put("messages", msgs);

            RequestBody requestBody = RequestBody.create(
                    body.toString(), MediaType.parse("application/json"));
            Request request = new Request.Builder()
                    .url(OPENAI_URL)
                    .addHeader("Authorization", "Bearer " + BuildConfig.OPENAI_API_KEY)
                    .post(requestBody)
                    .build();

            new Thread(() -> {
                try {
                    Response response = httpClient.newCall(request).execute();
                    if (!response.isSuccessful()) {
                        String errBody = "";
                        try { if (response.body() != null) errBody = response.body().string(); }
                        catch (Exception ignored) {}
                        final int code = response.code();
                        android.util.Log.e("OpenAI_Chat", "HTTP " + code + ": " + errBody);
                        runOnUiThread(() -> onAnswerFailedWithCode(code));
                        return;
                    }
                    if (response.body() == null) {
                        runOnUiThread(() -> onAnswerFailed());
                        return;
                    }

                    StringBuilder full = new StringBuilder();
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(
                                    response.body().byteStream(), "UTF-8"));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.startsWith("data: ")) continue;
                        String data = line.substring(6).trim();
                        if (data.equals("[DONE]")) break;
                        try {
                            String content = new JSONObject(data)
                                    .getJSONArray("choices")
                                    .getJSONObject(0)
                                    .getJSONObject("delta")
                                    .optString("content", "");
                            if (content.isEmpty()) continue;
                            full.append(content);
                        } catch (Exception ignored) {}
                    }

                    String rawAnswer = full.toString().trim();
                    if (rawAnswer.isEmpty()) {
                        runOnUiThread(() -> onAnswerFailed());
                        return;
                    }

                    // Убираем строку с маркером из отображаемого текста
                    final String finalAnswer = rawAnswer
                            .replaceAll("(?m)^##ЗАЯВКА_ГОТОВА##[^\n]*\\n?", "").trim();

                    // Детектируем маркер заявки и отправляем письмо + сохраняем переписку
                    if (rawAnswer.contains("##ЗАЯВКА_ГОТОВА##")) {
                        sendComplaintEmail(rawAnswer, finalAnswer);
                    }

                    saveToGlobalQueries(question, finalAnswer, detectedLang);

                    runOnUiThread(() -> {
                        if (ttsEnabled) {
                            String ttsText = kz.kitdev.util.TtsPreprocessor.prepare(finalAnswer, detectedLang);
                            speakOpenAIPrepareAndShow(ttsText, finalAnswer, question,
                                    voiceForLang(detectedLang));
                        } else {
                            isLoading = false;
                            setInputEnabled(true);
                            lastQuestion = question;
                            lastAnswer   = finalAnswer;
                            adapter.replaceLastWithAnswer(finalAnswer, question);
                            scrollToBottom();
                        }
                    });

                } catch (Exception e) {
                    runOnUiThread(() -> onAnswerFailed());
                }
            }).start();

        } catch (Exception e) {
            onAnswerFailed();
        }
    }

    // ----------------------------------------------------------------
    //  Отправка заявки на email
    // ----------------------------------------------------------------

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

            // Firestore сохраняем ВСЕГДА — независимо от формата номера
            final String f = fio, p = phone, pr = problem, a = address;
            runOnUiThread(() -> saveComplaintConversation(f, p, pr, a, finalAnswer));

            // Email отправляем только если номер валидный
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
     * Сохраняет всю переписку (из адаптера + finalAnswer ИИ) в коллекцию
     * users/{uid}/complaints — используется в AnalyticsActivity.
     * Вызывается на UI-потоке для безопасного доступа к адаптеру.
     * Атомарно получает следующий номер жалобы из global/stats,
     * геокодирует адрес через Nominatim, сохраняет в locations.
     */
    private void saveComplaintConversation(String fio, String phone,
                                           String problem, String address,
                                           String finalAnswer) {
        com.google.firebase.auth.FirebaseUser user =
                com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;

        // Снимок сообщений: все кроме loading-заглушки, + финальный ответ ИИ
        java.util.List<java.util.Map<String, Object>> msgs = new java.util.ArrayList<>();
        for (kz.kitdev.chat.ChatMessage m : adapter.getMessages()) {
            if (m.isLoading) continue;

            java.util.Map<String, Object> entry = new java.util.HashMap<>();

            if (m.type == kz.kitdev.chat.ChatMessage.TYPE_IMAGE) {
                // Включаем фото: base64 data URL (data:image/...) или https:// URL
                // Локальные content:// / file:// пропускаем — они недоступны извне
                String uri = m.imageUri;
                if (uri != null && (uri.startsWith("data:") || uri.startsWith("https://"))) {
                    entry.put("role", "user");
                    entry.put("imageUrl", uri);
                    msgs.add(entry);
                }
                continue;
            }

            if (m.text == null || m.text.isEmpty()) continue;
            entry.put("role", m.type == kz.kitdev.chat.ChatMessage.TYPE_USER ? "user" : "bot");
            entry.put("text", m.text);
            msgs.add(entry);
        }
        // Добавляем финальный ответ ИИ (он ещё не попал в адаптер в момент вызова)
        java.util.Map<String, Object> lastBot = new java.util.HashMap<>();
        lastBot.put("role", "bot");
        lastBot.put("text", finalAnswer);
        msgs.add(lastBot);

        final java.util.List<java.util.Map<String, Object>> finalMsgs = msgs;
        final com.google.firebase.firestore.FirebaseFirestore db =
                com.google.firebase.firestore.FirebaseFirestore.getInstance();

        // Шаг 1: Сохраняем заявку СРАЗУ — без зависимости от транзакции номера
        // Это гарантирует что заявка попадёт в БД даже если получение номера не удастся
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
                data.put("lang",            currentLang);
                data.put("status",          "processing");
                data.put("complaintNumber", 0L); // временно 0, обновим после транзакции
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
                                // Обновляем номер в уже сохранённом документе
                                ref.update("complaintNumber", num);
                                String complaintInfo = "\n\n📋 Номер вашей заявки: " + String.format("%04d", num);
                                runOnUiThread(() -> adapter.appendToLastBotMessage(complaintInfo));
                            }).addOnFailureListener(e -> {
                                // Транзакция не прошла — заявка уже сохранена, просто без номера
                                android.util.Log.e("Complaint", "Номер не получен, заявка сохранена без номера", e);
                            });

                            // Шаг 3: Сохраняем координаты в locations
                            if (finalLat != 0 || finalLng != 0) {
                                java.util.Map<String, Object> locationData = new java.util.HashMap<>();
                                locationData.put("lat",         finalLat);
                                locationData.put("lng",         finalLng);
                                locationData.put("address",     address);
                                locationData.put("description", problem);
                                locationData.put("complaintId", ref.getId());
                                locationData.put("uid",         user.getUid());
                                locationData.put("createdAt",   com.google.firebase.firestore.FieldValue.serverTimestamp());
                                db.collection("locations").add(locationData);
                            }

                            // Шаг 4: Дублируем заявку в верхнеуровневую коллекцию allComplaints.
                            // Это создаёт отдельный раздел в Firebase консоли — удобно для
                            // просмотра всех заявок без навигации по users/{uid}/complaints.
                            // Структура идентична основной заявке + поле uid и complaintId.
                            java.util.Map<String, Object> allData = new java.util.HashMap<>();
                            allData.put("uid",             user.getUid());
                            allData.put("complaintId",     ref.getId());
                            allData.put("fio",             fio);
                            allData.put("phone",           phone);
                            allData.put("problem",         problem);
                            allData.put("address",         address);
                            allData.put("lang",            currentLang);
                            allData.put("status",          "processing");
                            allData.put("complaintNumber", 0L);
                            allData.put("lat",             finalLat);
                            allData.put("lng",             finalLng);
                            allData.put("messages",        finalMsgs); // включая imageUrl фото
                            allData.put("createdAt",       com.google.firebase.firestore.FieldValue.serverTimestamp());
                            db.collection("allComplaints")
                                    .add(allData)
                                    .addOnSuccessListener(allRef ->
                                            android.util.Log.d("Complaint", "allComplaints сохранён: " + allRef.getId()))
                                    .addOnFailureListener(e ->
                                            android.util.Log.e("Complaint", "Ошибка сохранения allComplaints", e));
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

    /** true — достаточно текста и он заканчивается на точку/восклицательный/вопрос */
    private boolean isSentenceBoundary(String text) {
        if (text.length() < 50) return false; // 50 вместо 80 — TTS стартует раньше
        char last = text.charAt(text.length() - 1);
        return last == '.' || last == '!' || last == '?' || last == '\n';
    }

    private void onAnswerFailed() {
        isLoading = false;
        setInputEnabled(true);
        adapter.removeLastMessage();
        Toast.makeText(this, R.string.error_network, Toast.LENGTH_SHORT).show();
    }

    /** Показывает HTTP-код ошибки — помогает диагностировать проблему с API */
    private void onAnswerFailedWithCode(int httpCode) {
        isLoading = false;
        setInputEnabled(true);
        adapter.removeLastMessage();
        String msg = "Ошибка сервера: HTTP " + httpCode;
        if (httpCode == 401) msg = "Ошибка авторизации API (401)";
        else if (httpCode == 404) msg = "Модель недоступна (404). Проверь API ключ.";
        else if (httpCode == 429) msg = "Превышен лимит запросов (429). Подождите.";
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private void setInputEnabled(boolean enabled) {
        binding.etInput.setEnabled(enabled);
        binding.btnSend.setEnabled(enabled);
        binding.btnMic.setEnabled(enabled);
    }

    private void scrollToBottom() {
        binding.recyclerView.postDelayed(() ->
                binding.recyclerView.scrollToPosition(adapter.getItemCount() - 1), 100);
    }

    // ----------------------------------------------------------------
    //  Language auto-detection
    // ----------------------------------------------------------------

    private String detectMessageLang(String text) {
        // Если сообщение пришло с голосового ввода — Whisper определил язык точнее текста
        if (voiceInputLang != null) {
            String lang = voiceInputLang;
            voiceInputLang = null; // сбрасываем после использования
            return lang;
        }
        if (text == null || text.isEmpty()) return currentLang;
        String lower = text.toLowerCase();
        if (lower.matches(".*[ғқңөүұіәһ].*")) return "kk";
        long latinCount = 0, cyrillicCount = 0;
        for (char c : lower.toCharArray()) {
            if (c >= 'a' && c <= 'z') latinCount++;
            else if ((c >= '\u0430' && c <= '\u044f') || c == '\u0451') cyrillicCount++;
        }
        if (latinCount > cyrillicCount && latinCount > 2) return "en";
        if (cyrillicCount > 0) return currentLang.equals("en") ? "ru" : currentLang;
        return currentLang;
    }

    // ----------------------------------------------------------------
    //  Save to Firestore
    // ----------------------------------------------------------------

    private void saveToFirestore(String uid, String question, String answer) {
        Map<String, Object> data = new HashMap<>();
        data.put("question", question);
        data.put("answer", answer);
        // Use the detected response language (same logic as callOpenAI) so the history
        // dialog plays back in the correct voice regardless of current UI language.
        data.put("lang", detectMessageLang(question));
        data.put("createdAt", com.google.firebase.Timestamp.now());

        FirebaseFirestore.getInstance()
                .collection("users")
                .document(uid)
                .collection("history")
                .add(data);
    }

    // ----------------------------------------------------------------
    //  Global queries — сохранение и загрузка похожих запросов
    // ----------------------------------------------------------------

    /** Fire-and-forget: сохраняет Q&A в глобальную коллекцию global_queries */
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

    /**
     * Загружает последние 30 записей из global_queries, выбирает до 3 наиболее похожих
     * на текущий вопрос (по совпадению ключевых слов) и возвращает готовый контекстный блок
     * для системного промпта. Если похожих нет — возвращает пустую строку.
     */
    private void loadSimilarQueries(String question, SimilarQueriesCallback callback) {
        FirebaseFirestore.getInstance()
                .collection("global_queries")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(30)
                .get()
                .addOnSuccessListener(snapshots -> {
                    // Токены текущего вопроса (≥4 символа)
                    String[] tokens = question.toLowerCase().split("[\\s,.:;!?()\"']+");
                    List<String[]> candidates = new ArrayList<>(); // [score, question, answer]

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

                    // Сортируем по убыванию score
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

    // ----------------------------------------------------------------
    //  Centered save notification
    // ----------------------------------------------------------------

    private void showSaveNotification() {
        View notify = LayoutInflater.from(this).inflate(R.layout.notify_saved, null);
        TextView tv = notify.findViewById(R.id.tvNotifyText);
        tv.setText(getString(R.string.history_saved));

        ViewGroup decorView = (ViewGroup) getWindow().getDecorView();
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        notify.setAlpha(0f);
        decorView.addView(notify, params);

        notify.animate().alpha(1f).setDuration(200)
                .withEndAction(() -> notify.postDelayed(() ->
                        notify.animate().alpha(0f).setDuration(500)
                                .withEndAction(() -> decorView.removeView(notify))
                                .start(), 1300))
                .start();
    }

    // ----------------------------------------------------------------
    //  OpenAI prompt building
    // ----------------------------------------------------------------

    private boolean wantsDetailedAnswer(String prompt) {
        String p = prompt.toLowerCase();
        String[] markers = {
                // Русский
                "подробнее", "подробно", "более подробно", "детально", "детальнее",
                "развернуто", "развёрнуто", "раскрой", "объясни подробно", "расскажи подробно",
                "расшифруй", "поподробнее", "полный ответ", "полностью объясни",
                // Казахский
                "толығырақ", "толық", "егжей-тегжей", "егжей", "кеңінен",
                "барлығын айт", "толық жауап", "түсіндір",
                // Английский
                "in detail", "detailed", "more details", "elaborate", "explain fully",
                "comprehensive", "thorough", "full explanation", "step by step"
        };
        for (String m : markers) if (p.contains(m)) return true;
        return false;
    }

    private String buildEffectivePrompt(String prompt, boolean detailed) {
        if (!detailed) return prompt;
        String[] words = prompt.trim().split("\\s+");
        // Короткий запрос «подробнее» → дополняем контекстом из предыдущего диалога
        if (words.length > 7 || lastQuestion.isEmpty()) return prompt;
        StringBuilder enriched = new StringBuilder();
        enriched.append("Пользователь задаёт уточняющий вопрос к предыдущей теме.\n");
        enriched.append("Предыдущий вопрос: \"").append(lastQuestion).append("\".\n");
        if (!lastAnswer.isEmpty()) {
            int preview = Math.min(300, lastAnswer.length());
            enriched.append("Предыдущий ответ (фрагмент): \"")
                    .append(lastAnswer, 0, preview).append("\".\n");
        }
        enriched.append("Новый запрос: \"").append(prompt).append("\".\n");
        enriched.append("Дай развёрнутый экспертный ответ по праву РК с анализом всех аспектов темы.");
        return enriched.toString();
    }

    private String buildSystemPrompt(String lang, boolean detailed, String similarContext) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Ты — ИИ-помощник акимата Павлодарской области Казахстана.\n\n")
                .append("ЗОНА РАБОТЫ: только Павлодарская область — г. Павлодар, г. Экибастуз, г. Аксу, ")
                .append("Баянаульский, Железинский, Майский, Павлодарский, Успенский, Щербактинский, ")
                .append("Актогайский, Лебяжинский, Иртышский, Качирский районы и сёла области.\n\n")
                .append("ШАГ 1 — ОПРЕДЕЛИ ТИП ЗАПРОСА:\n")
                .append("• Приветствие (здравствуйте, сәлеметсіз бе, привет, сәлем, hi, hello и т.п.):\n")
                .append("   — Только приветствие без проблемы → ответь приветствием на том же языке и коротко предложи помощь.\n")
                .append("   — Приветствие + проблема в одном сообщении → сначала коротко поздоровайся, ")
                .append("затем сразу переходи к алгоритму жалобы/заявления без лишних вопросов.\n")
                .append("• Информационный вопрос (без конкретной проблемы на местности) → просто дай ответ, без запроса контактов.\n")
                .append("• Жалоба или заявление (ЖКХ, дороги, освещение, мусор, школа, больница, экология, ")
                .append("безопасность, благоустройство и др.) → переходи к алгоритму ниже.\n\n")
                .append("ШАГ 2 — АЛГОРИТМ ДЛЯ ЖАЛОБЫ/ЗАЯВЛЕНИЯ:\n")
                .append("1. Выведи строку: «Основная проблема: [суть]»\n")
                .append("2. Уточни место нахождения ПРОБЛЕМЫ:\n")
                .append("   — Пользователь назвал конкретное место (улица, дом, район, объект, село) → ")
                .append("СНАЧАЛА проверь: реально ли такое место существует в Павлодарской области?\n")
                .append("     • Если улица, дом, школа, парк, объект существуют → ")
                .append("выведи: «Место проблемы: [место]»\n")
                .append("     • Если указанное место НЕ существует в Павлодарской области (несуществующая ")
                .append("улица, несуществующий номер школы/детсада/больницы, выдуманный парк или рынок и т.п.) → ")
                .append("скажи пользователю: «[Название места] не существует в [городе/районе]. ")
                .append("Уточните, пожалуйста, точный адрес места проблемы.» — и жди исправленного ответа. ")
                .append("НЕ принимай и НЕ переходи к следующему шагу пока адрес не будет реальным.\n")
                .append("   — Место не указано или неточно → спроси: «Уточните, пожалуйста, точный адрес ")
                .append("места проблемы: улицу, дом или район Павлодарской области.» — и жди ответа.\n")
                .append("   ВАЖНО: «Место проблемы» — это ТОЛЬКО физическое место где находится проблема ")
                .append("(яма, мусор, поломка, авария и т.п.), НЕ адрес государственного органа, ")
                .append("НЕ домашний адрес заявителя.\n")
                .append("3. После уточнения места укажи:\n")
                .append("   Напиши вежливо: «Вы можете обратиться в [орган]» и сразу добавь телефон из списка ниже (только если подходит):\n")
                .append("   КОНТАКТЫ ПАВЛОДАРСКОЙ ОБЛАСТИ:\n")
                .append("   • ЖКХ, коммуналка, отопление → Упр. энергетики и ЖКХ области: 8(7182) 65-33-17\n")
                .append("   • Дороги, освещение, транспорт (город) → Отдел ЖКХ, ПТ и АД г. Павлодара: 8(7182) 32-22-60\n")
                .append("   • Дороги (область) → Упр. пасс. транспорта и автодорог области: 8(7182) 32-30-32\n")
                .append("   • Жилищная инспекция (жалобы на УК, состояние дома) → 8(7182) 65-04-60\n")
                .append("   • Жилищные отношения (очереди, соц. жильё) → 8(7182) 32-05-68\n")
                .append("   • Экология, загрязнение → Департамент экологии: 8(7182) 53-29-10\n")
                .append("   • Здравоохранение → Упр. здравоохранения области: 8(7182) 32-50-02\n")
                .append("   • Соцзащита, пособия → Отдел занятости и соц. программ: 8(7182) 32-33-32\n")
                .append("   • Земельные вопросы → Отдел земельных отношений: 8(7182) 73-05-82\n")
                .append("   • Строительство (нарушения) → Отдел строительства города: 8(7182) 21-63-21\n")
                .append("   • Обращения в акимат города → 8(7182) 65-04-28\n")
                .append("   • Онлайн-обращение → e-otinish.kz или egov.kz\n")
                .append("   • Единый call-center: 1414 (бесплатно, если орган неизвестен)\n")
                .append("   Если тип проблемы не совпадает ни с одним пунктом — укажи 1414 и e-otinish.kz. НЕ придумывай номера.\n")
                .append("4. Попроси: «Для регистрации заявки укажите ваше ФИО и номер телефона.»\n")
                .append("5. Получив номер телефона — проверь его:\n")
                .append("   Казахстанский номер — ровно 11 цифр, формат: +7 7XX XXX XX XX или 87XX XXX XX XX.\n")
                .append("   Все операторы КЗ: 700, 701, 702, 705, 706, 707, 708, 747, 771, 775, 776, 777, 778.\n")
                .append("   Примеры верных: +7 705 123 45 67 / +7 747 123 45 67 / 87011234567 / 77071234567\n")
                .append("   Отклоняй ТОЛЬКО если явно неполный (меньше 10 цифр) или явно случайные символы.\n")
                .append("   Пробелы, дефисы, скобки в номере — это нормально, не отклоняй из-за форматирования.\n")
                .append("   Если номер явно неверный — скажи: «Укажите, пожалуйста, казахстанский номер в формате +7 7XX XXX XX XX» — и жди нового.\n")
                .append("5б. Получив ФИО — принимай любое имя из 2+ слов. Не отклоняй казахские, русские, любые имена.\n")
                .append("    Отклоняй только если написано явно не имя (например одна буква или цифры).\n")
                .append("6. Только после ФИО И номера телефона выведи СТРОГО:\n")
                .append("##ЗАЯВКА_ГОТОВА## ФИО: [фио] | Телефон: [телефон] | Проблема: [суть] | МестоПроблемы: [геокодируемый адрес места проблемы]\n")
                .append("В поле МестоПроблемы — ТОЧНЫЙ адрес для поиска на карте. Правила:\n")
                .append("  • Школа/детсад/больница/поликлиника → «Школа №15, ул. Ленина, Павлодар»\n")
                .append("  • Рынок/ТЦ/парк/стадион/акимат/другой объект → «Рынок Аян, ул. Торайгырова, Павлодар»\n")
                .append("  • Улица/дом → «ул. Карла Маркса 45, Экибастуз»\n")
                .append("  • Микрорайон → «мкр. Химки, Павлодар»\n")
                .append("  • Посёлок/село → «с. Успенка, Успенский район, Павлодарская область»\n")
                .append("  ВСЕГДА добавляй название города или посёлка Павлодарской области.\n")
                .append("  НЕ пиши адрес акимата, суда или любого другого органа — только место проблемы.\n")
                .append("Затем напиши: «Ваша заявка зарегистрирована и направлена. Ожидайте обратной связи.»\n\n")
                .append("БАЗА ЗНАНИЙ (типичные обращения жителей):\n")
                .append("• Уличное освещение → Ваша заявка принята, специалистами отдела ЖКХ будет произведено обследование согласно Гл.5 Правил благоустройства (adilet.zan.kz/rus/docs/G24P013414M).\n")
                .append("• Порыв трубы/авария водоснабжения → аварийная служба КСК/ОСИ; Павлодар-водоканал: 60-45-68, 57-24-72; 109.\n")
                .append("• Подпор канализации → КСК/ОСИ устраняет за 24–48 ч; Павлодар-водоканал: 60-45-68; 109.\n")
                .append("• Отключение электричества → Горэлектросеть: 61-06-06, 61-38-38; ПРЭК: 32-20-22; 112.\n")
                .append("• Нет отопления/горячей воды → КСК/ОСИ; плановые отключения — по графику АО «Павлодарэнерго»; Упр. энергетики и ЖКХ: 65-33-17.\n")
                .append("• Некачественный ремонт после коммунальных работ → Отдел жилищной инспекции, ул. Кривенко 25; согласно п.101 Правил благоустройства все нарушенные элементы подлежат восстановлению подрядчиком.\n")
                .append("• Затопление квартиры → составьте акт о затоплении с КСК/ОСИ; виновная сторона возмещает ущерб по Закону о жилищных отношениях (adilet.zan.kz/rus/docs/Z970000094_).\n")
                .append("• Ремонт крыши/подвала МЖД → Отдел жилищной инспекции, ул. Кривенко 25, тел. 32-55-05; или ТОО «Горкомхоз модернизация жилья».\n")
                .append("• Переход в ОСИ → ст.42, 43 Закона РК «О жилищных отношениях» (adilet.zan.kz/rus/docs/Z970000094_).\n")
                .append("• Очередь на жильё → п.1 ст.67 Закона о жилищных отношениях; постановка через ЭЦП на orken.otbasybank.kz.\n")
                .append("• Аварийное жильё → экспертное заключение → Отдел жилищных отношений города Павлодара.\n")
                .append("• Жилищные программы «5-10-20» и «2-10-20»; статус → orken.otbasybank.kz.\n")
                .append("• Теплоснабжение частного сектора → «Павлодарские тепловые сети», ул. Камзина 149.\n")
                .append("• Санитарная обрезка деревьев → осенне-весенний период по Приказу № 62 от 23.02.2023 (adilet.zan.kz/rus/docs/V2300031996); КСК/ОСИ подаёт заявку в ЖКХ с согласием 3/4 жителей.\n")
                .append("• Аварийные/сухие деревья → заявка через КСК/ОСИ в ЖКХ; спил после осмотра биологами.\n")
                .append("• Детская/спортивная площадка, футбольное поле, тренажёры → будет включено в график на 2026 год после обследования территории совместно с коммунальными службами города.\n")
                .append("• Асфальтирование двора → депутат + МИО выедут; по итогам — бюджетная комиссия.\n")
                .append("• ИДН, дорожные знаки → депутат + МИО + полиция выедут; по итогам — бюджетная комиссия.\n")
                .append("• Ремонт остановочного павильона → Ваша заявка принята, специалистами отдела ЖКХ будет произведено обследование; при необходимости — бюджетная комиссия.\n")
                .append("• Светофор → Управление полиции + отдел ЖКХ проведут обследование; при необходимости — бюджетная комиссия.\n")
                .append("• Ямочный ремонт → депутат + отдел ЖКХ выедут; по итогам — бюджетная комиссия.\n")
                .append("• Парковочные места → возможно при условиях Гл.13 Правил благоустройства (adilet.zan.kz/rus/docs/G24P013414M).\n")
                .append("• Площадка для выгула собак → депутат + МИО выедут; по итогам — бюджетная комиссия.\n")
                .append("• Лавочки, урны → за счёт собственников жилья; в бюджете МИО не предусмотрено.\n")
                .append("• Крупногабаритный мусор → ТОО «Горкомхоз-Павлодар» — раз в месяц + по заявкам жителей.\n")
                .append("• Вывоз снега → согласно п.28 Правил благоустройства ежедневная уборка включена в содержание дорог.\n")
                .append("• Нормы контейнеров для бизнеса → Решение маслихата от 16.11.2022 № 179/24 (adilet.zan.kz/rus/docs/V22PA030576).\n")
                .append("• Незаконная парковка → ст.597 КоАП РК; обратитесь в Управление административной полиции; п.98 Правил благоустройства.\n")
                .append("• Откачка дождевых вод → заявка направлена в 109 и отдел ЖКХ.\n")
                .append("• Бюджет народного участия → правила на сайте акимата gov.kz.\n")
                .append("• Паводки → ежегодно акиматом утверждается план подготовки к паводковому периоду.\n")
                .append("• Борьба с комарами/мошкой → ежегодные мероприятия МИО.\n")
                .append("• Нарушение тишины → 102; штраф по ст. 437 КоАП: 5–15 МРП.\n")
                .append("• Бродячие собаки → WhatsApp ветстанции: 8708 277 31 19; согласно Правилам отлова животных (adilet.zan.kz/rus/docs/G22P018114M).\n")
                .append("• Соцпомощь многодетным, неполным семьям → Отдел занятости: ул. Кривенко 25, тел. 32-33-32; решение маслихата № 65/8.\n")
                .append("• Помощь на уголь → инвалиды 1–3 гр., многодетные, малообеспеченные с печным отоплением; решение маслихата № 65/8.\n")
                .append("• Кредиты для бизнеса → безвозмездные: Карьерный центр, ул. Бектурова 115В; под залог: АО «Фонд ДАМУ».\n")
                .append("• Принятие в детсад без прививок → при доле непривитых >10% детсад вправе отказать (п.8 Правил допуска, adilet.zan.kz/rus/docs/V2000021832).\n")
                .append("• Летние лагеря → список на сайте Управления образования, gov.kz.\n")
                .append("• Земельный участок под ИЖС → egov.kz «Постановка на очередь под ИЖС».\n")
                .append("• Почётный гражданин Павлодара → решение маслихата от 11.12.2020 № 538/44 (adilet.zan.kz/rus/docs/V20P0007102).\n")
                .append("• Наружная реклама → ст.11 Закона РК «О рекламе» № 508 (adilet.zan.kz/rus/docs/Z030000508_).\n")
                .append("• Экологический мониторинг → Департамент экологии ПО по Экологическому кодексу РК (adilet.zan.kz/rus/docs/K2100000400).\n")
                .append("• Мониторинг цен → Отдел предпринимательства согласно ст.9 Закона о торговой деятельности (adilet.zan.kz/rus/docs/Z040000544_).\n")
                .append("• Вызов сотрудника ЦОН на дом → тел. 1414 или gov4c.kz.\n")
                .append("• График ЦОН → пн–пт 9:00–20:00, сб 9:00–13:00, вс выходной; тел. 1414.\n")
                .append("• Служба 109 → принимает обращения по ЖКХ и госуслугам; экстренные вызовы — 112.\n\n")
                .append("ЯЗЫК: Отвечай СТРОГО на ").append("ru".equals(lang) ? "русском" : "kk".equals(lang) ? "казахском" : "английском")
                .append(" языке. Если сообщение написано на нескольких языках или смешанное — всё равно отвечай только на ").append("ru".equals(lang) ? "русском" : "kk".equals(lang) ? "казахском" : "английском").append(" языке.\n")
                .append("ВАЖНО: Не выдумывай несуществующие адреса и телефоны органов власти.");

        return prompt.toString();
    }

    // ----------------------------------------------------------------
    //  TTS — OpenAI TTS (tts-1-hd) для всех языков
    //
    //  echo — тёплый живой мужской голос, speed 0.92 — естественный темп
    // ----------------------------------------------------------------

    /** Голос OpenAI TTS — onyx (глубокий мужской) для всех языков. */
    private String voiceForLang(String lang) {
        return "onyx";
    }

    /** Останавливает любое воспроизведение — и AudioTrack, и Android TTS */
    private void stopAllSpeech() {
        pendingKkContinuation = null;
        ttsSession++;  // invalidates any in-flight completion callbacks
        AudioTrack track = currentAudioTrack;
        currentAudioTrack = null;
        if (track != null) {
            try { track.pause(); track.flush(); } catch (Exception ignored) {}
            try { track.stop(); } catch (Exception ignored) {}
            try { track.release(); } catch (Exception ignored) {}
        }
        if (tts != null) tts.stop();
        abandonAudioFocus();
    }

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

    /** Воспроизводит текст на языке ответа ИИ через OpenAI TTS */
    private void speak(String text, String lang) {
        stopAllSpeech();
        speakViaOpenAI(kz.kitdev.util.TtsPreprocessor.prepare(text, lang), voiceForLang(lang));
    }

    private void speak(String text) {
        speak(text, currentLang);
    }

    // ---- OpenAI TTS для казахского — PCM + AudioTrack MODE_STATIC ----

    /**
     * Казахский TTS для нового ответа ИИ — схема «подготовить, потом показать».
     *
     * Алгоритм (все языки — nova/shimmer/alloy через tts-1-hd):
     *  1. Весь PCM скачивается в фоне — индикатор загрузки продолжает крутиться.
     *  2. Когда PCM готов, в runOnUiThread АТОМАРНО:
     *       replaceLastWithAnswer(ответ)  — сообщение появляется в чате
     *       track.play()                  — голос стартует в тот же кадр
     *  3. Пользователь видит сообщение и слышит голос одновременно, без задержки.
     */
    /** ttsText — предобработанный текст для озвучки; answer — оригинал для отображения */
    private void speakOpenAIPrepareAndShow(String ttsText, String answer, String question, String voice) {
        final int session = ttsSession;
        new Thread(() -> {
            try {
                JSONObject body = buildTtsBody(ttsText, voice);
                RequestBody requestBody = RequestBody.create(
                        body.toString(), MediaType.parse("application/json"));
                Request request = new Request.Builder()
                        .url(OPENAI_TTS_URL)
                        .addHeader("Authorization", "Bearer " + BuildConfig.OPENAI_API_KEY)
                        .post(requestBody)
                        .build();

                Response response = httpClient.newCall(request).execute();
                if (!response.isSuccessful() || response.body() == null) {
                    runOnUiThread(() -> {
                        if (ttsSession != session) return;
                        isLoading = false; setInputEnabled(true);
                        lastQuestion = question; lastAnswer = answer;
                        adapter.replaceLastWithAnswer(answer, question);
                        scrollToBottom();
                    });
                    return;
                }

                ByteArrayOutputStream baos = new ByteArrayOutputStream(65536);
                try (InputStream is = response.body().byteStream()) {
                    byte[] chunk = new byte[8192]; int n;
                    while ((n = is.read(chunk)) != -1) {
                        if (ttsSession != session) return;
                        baos.write(chunk, 0, n);
                    }
                }
                if (ttsSession != session) return;
                byte[] pcmData = baos.toByteArray();
                if (pcmData.length == 0) {
                    runOnUiThread(() -> {
                        if (ttsSession != session) return;
                        isLoading = false; setInputEnabled(true);
                        lastQuestion = question; lastAnswer = answer;
                        adapter.replaceLastWithAnswer(answer, question);
                        scrollToBottom();
                    });
                    return;
                }

                final int SAMPLE_RATE  = 24000;
                final int silenceBytes = 200 * SAMPLE_RATE * 2 / 1000;
                byte[] fullData = new byte[silenceBytes + pcmData.length];
                System.arraycopy(pcmData, 0, fullData, silenceBytes, pcmData.length);

                AudioTrack track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setSampleRate(SAMPLE_RATE)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setBufferSizeInBytes(fullData.length)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build();

                if (ttsSession != session) { track.release(); return; }
                track.write(fullData, 0, fullData.length);

                final long durationMs = (long) fullData.length * 1000L / (SAMPLE_RATE * 2L);
                final AudioTrack ft = track;

                runOnUiThread(() -> {
                    if (ttsSession != session) { ft.release(); return; }
                    isLoading = false;
                    setInputEnabled(true);
                    lastQuestion = question;
                    lastAnswer   = answer;
                    adapter.replaceLastWithAnswer(answer, question);
                    scrollToBottom();
                    requestAudioFocus();
                    currentAudioTrack = ft;
                    ft.play();
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        if (ttsSession != session) return;
                        ttsSession++;
                        currentAudioTrack = null;
                        try { ft.stop(); } catch (Exception ignored) {}
                        try { ft.release(); } catch (Exception ignored) {}
                        abandonAudioFocus();
                        if (pendingKkContinuation != null && ttsEnabled) {
                            String next = pendingKkContinuation;
                            pendingKkContinuation = null;
                            speakViaOpenAI(next, voice);
                        }
                    }, durationMs + 300);
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (ttsSession != session) return;
                    isLoading = false; setInputEnabled(true);
                    lastQuestion = question; lastAnswer = answer;
                    adapter.replaceLastWithAnswer(answer, question);
                    scrollToBottom();
                });
            }
        }).start();
    }

    /**
     * OpenAI TTS для повторного воспроизведения (кнопка TTS).
     * tts-1-hd, 200мс тишины перед речью — нет щелчка DAC.
     */
    private void speakViaOpenAI(String text, String voice) {
        final int session = ttsSession;

        new Thread(() -> {
            try {
                JSONObject body = buildTtsBody(text, voice);
                RequestBody requestBody = RequestBody.create(
                        body.toString(), MediaType.parse("application/json"));
                Request request = new Request.Builder()
                        .url(OPENAI_TTS_URL)
                        .addHeader("Authorization", "Bearer " + BuildConfig.OPENAI_API_KEY)
                        .post(requestBody)
                        .build();

                Response response = httpClient.newCall(request).execute();
                if (!response.isSuccessful() || response.body() == null) return;

                ByteArrayOutputStream baos = new ByteArrayOutputStream(65536);
                try (InputStream is = response.body().byteStream()) {
                    byte[] chunk = new byte[8192]; int n;
                    while ((n = is.read(chunk)) != -1) {
                        if (ttsSession != session) return;
                        baos.write(chunk, 0, n);
                    }
                }
                if (ttsSession != session) return;
                byte[] pcmData = baos.toByteArray();
                if (pcmData.length == 0) return;

                final int SAMPLE_RATE  = 24000;
                final int silenceBytes = 200 * SAMPLE_RATE * 2 / 1000;
                byte[] fullData = new byte[silenceBytes + pcmData.length];
                System.arraycopy(pcmData, 0, fullData, silenceBytes, pcmData.length);

                AudioTrack track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setSampleRate(SAMPLE_RATE)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setBufferSizeInBytes(fullData.length)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build();

                if (ttsSession != session) { track.release(); return; }
                track.write(fullData, 0, fullData.length);
                requestAudioFocus();
                currentAudioTrack = track;
                track.play();

                final long durationMs = (long) fullData.length * 1000L / (SAMPLE_RATE * 2L);
                final AudioTrack finalTrack = track;
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    if (ttsSession != session) return;
                    ttsSession++;
                    currentAudioTrack = null;
                    try { finalTrack.stop(); } catch (Exception ignored) {}
                    try { finalTrack.release(); } catch (Exception ignored) {}
                    abandonAudioFocus();
                    if (pendingKkContinuation != null && ttsEnabled) {
                        String next = pendingKkContinuation;
                        pendingKkContinuation = null;
                        speakViaOpenAI(next, voice);
                    }
                }, durationMs + 300);

            } catch (Exception ignored) {}
        }).start();
    }

    // ---- Построение тела запроса к OpenAI TTS ----

    private JSONObject buildTtsBody(String text, String voice) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", "tts-1-hd");
        body.put("voice", voice);
        body.put("input", text);
        body.put("response_format", "pcm");
        body.put("speed", 0.95);
        return body;
    }

    // ---- Android TTS — оставлен для инициализации TTS engine ----

    private void setupTts() {
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) setTtsLocale();
        }, "com.google.android.tts");
    }

    private void setTtsLocale() {
        if (tts == null) return;
        Locale locale = LangManager.toLocale(currentLang);
        int result = tts.setLanguage(locale);
        if (result == TextToSpeech.LANG_NOT_SUPPORTED || result == TextToSpeech.LANG_MISSING_DATA) {
            tts.setLanguage(new Locale("ru"));
        }
        tts.setSpeechRate(0.9f);
        tts.setPitch(1.05f);
    }

    private void speakViaAndroidTts(String text, String lang) {
        if (tts == null || text.isEmpty()) return;
        // Динамически задаём язык озвучки по языку ответа, а не по языку интерфейса
        Locale locale = "en".equals(lang) ? Locale.ENGLISH
                : "kk".equals(lang) ? new Locale("kk") : new Locale("ru");
        int r = tts.setLanguage(locale);
        if (r == TextToSpeech.LANG_NOT_SUPPORTED || r == TextToSpeech.LANG_MISSING_DATA)
            tts.setLanguage(new Locale("ru")); // фоллбэк на русский
        requestAudioFocus();
        Bundle ttsParams = new Bundle();
        ttsParams.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "chat_tts");
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, ttsParams, "chat_tts");
    }

    private void toggleTts() {
        ttsEnabled = !ttsEnabled;
        if (!ttsEnabled) stopAllSpeech();
        updateTtsIcon();
    }

    private void updateTtsIcon() {
        MenuItem ttsItem = binding.toolbar.getMenu().findItem(R.id.action_tts);
        if (ttsItem != null) {
            ttsItem.setIcon(ttsEnabled ? R.drawable.ic_volume_on : R.drawable.ic_volume_off);
        }
    }

    // ----------------------------------------------------------------
    //  Voice input — toggle: first tap = start, second tap = stop
    //  Auto-restart on silence/timeout so mic never stops due to pauses
    // ----------------------------------------------------------------

    private void toggleVoiceInput() {
        if (isListening) {
            stopVoiceInput();
        } else {
            requestMicPermission();
        }
    }

    // ── Разрешение на уведомления (Android 13+) ──────────────────────────

    private void requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, PERM_NOTIF);
            } else {
                // Разрешение уже есть — убедимся что FCM-токен сохранён
                com.google.firebase.messaging.FirebaseMessaging.getInstance().getToken()
                        .addOnSuccessListener(AppFirebaseMessagingService::saveTokenToFirestore);
            }
        }
    }

    private void requestMicPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, PERM_MIC);
        } else {
            startVoiceInput();
        }
    }

    private void stopVoiceInput() {
        if (!isListening) return;
        isListening = false;
        voiceAccumulated = "";
        binding.btnMic.setImageResource(R.drawable.ic_mic);
        if (speechRecognizer != null) {
            speechRecognizer.stopListening();
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERM_MIC) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startVoiceInput();
            } else {
                Toast.makeText(this, R.string.error_mic_denied, Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == PhotoHelper.REQUEST_CAMERA_PERM) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                photoHelper.launchCamera();
            }
        } else if (requestCode == PERM_NOTIF) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Разрешение выдано — сохраняем FCM-токен чтобы уведомления начали приходить
                com.google.firebase.messaging.FirebaseMessaging.getInstance().getToken()
                        .addOnSuccessListener(AppFirebaseMessagingService::saveTokenToFirestore);
            }
            // Если отказано — уведомления не будут приходить, но приложение работает нормально
        }
    }

    private void startVoiceInput() {
        isListening = true;
        voiceInputLang = null;
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
                networkErrorCount = 0; // успешный результат — сбрасываем счётчик сетевых ошибок
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
                // Небольшая задержка перед перезапуском — иначе ERROR_RECOGNIZER_BUSY
                // на Samsung/Xiaomi при немедленном вызове startListening после onResults
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
                // Фатальные ошибки — останавливаем, не зацикливаемся
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
                    // Тишина — перезапуск, но не слишком быстро чтобы не дёргать микрофон
                    delay = 300;
                } else if (error == SpeechRecognizer.ERROR_NETWORK
                        || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) {
                    // Нет сети — даём время и пробуем офлайн
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

    /**
     * Из списка кандидатов STT выбирает лучший результат.
     * Google STT сортирует кандидатов по убыванию уверенности — кандидат 0 самый точный.
     * Дополнительно: если UI=kk ищем вариант с казахскими буквами;
     * если UI=en ищем вариант с латиницей чтобы не вернуть транслитерацию.
     */
    private String pickBestVoiceResult(java.util.ArrayList<String> candidates) {
        if (candidates == null || candidates.isEmpty()) return "";
        if ("kk".equals(currentLang)) {
            for (String c : candidates) {
                if (c.matches(".*[ғқңөүұіәһ].*")) return c;
            }
        } else if ("en".equals(currentLang)) {
            for (String c : candidates) {
                long lat = 0, cyr = 0;
                for (char ch : c.toCharArray()) {
                    if (ch >= 'a' && ch <= 'z' || ch >= 'A' && ch <= 'Z') lat++;
                    else if (ch >= '\u0430' && ch <= '\u044f') cyr++;
                }
                if (lat > cyr) return c; // явно латинский — подходит для EN
            }
        }
        return candidates.get(0);
    }

    /** Строит Intent для SpeechRecognizer */
    private Intent buildRecognizerIntent() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        // Онлайн только если не было сетевых ошибок подряд — иначе офлайн-fallback
        boolean useOffline = networkErrorCount >= 2;
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, useOffline);

        // Основной язык по UI
        String primary;
        if ("kk".equals(currentLang) && !kkSttUnavailable) {
            primary = "kk-KZ";
            voiceInputLang = "kk";
        } else if ("en".equals(currentLang)) {
            primary = "en-US";
            voiceInputLang = "en";
        } else {
            primary = "ru-RU";
            voiceInputLang = "ru";
        }
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, primary);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, primary);
        intent.putExtra("android.speech.extra.ONLY_RETURN_LANGUAGE_PREFERENCE", false);
        // Дополнительные языки — работает на устройствах с Google STT (Android 8+)
        // На Samsung/MIUI со своим движком игнорируется, но не ломает ничего
        java.util.ArrayList<String> extra = new java.util.ArrayList<>();
        if (!"ru-RU".equals(primary)) extra.add("ru-RU");
        if (!"kk-KZ".equals(primary) && !kkSttUnavailable) extra.add("kk-KZ");
        if (!"en-US".equals(primary)) extra.add("en-US");
        if (!extra.isEmpty()) {
            intent.putStringArrayListExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", extra);
        }
        return intent;
    }

    // ── Умное геокодирование для Павлодарской области ──────────────────────

    /** Населённые пункты Павлодарской области для поиска в переписке */
    private static final String[] PAVLODAR_PLACES = {
        "Павлодар", "Экибастуз", "Аксу", "Баянаул", "Железинка",
        "Майкаин", "Щербакты", "Успенка", "Качиры", "Лебяжье",
        "Иртышск", "Актогай", "Шидерты", "Аксуат", "Чалдай",
        "Ертис", "Аккулы", "Коктобе", "Железинский", "Павлодарский"
    };

    /**
     * Сканирует адрес + всю переписку на упоминания городов/сёл Павлодарской области.
     * Строит несколько запросов к Nominatim в порядке убывания точности, возвращает
     * первый успешный результат. Всегда применяет viewbox по границам области.
     */
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
        // (фразы типа "на улице Ленина", "в районе", "у дома", "около", "возле" и т.п.)
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

        // Fallback: просим OpenAI определить место
        // GPT возвращает либо LAT:x,LNG:y (для объектов — обходим Nominatim),
        // либо адресную строку (для улиц — передаём в Nominatim).
        String aiAddr = aiGeocode(problemAddress, convText);
        if (!aiAddr.isEmpty()) {
            if (aiAddr.startsWith("LAT:")) {
                // GPT вернул координаты напрямую — используем без Nominatim
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
            // Адресная строка — пробуем через Nominatim
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
     *
     * Для известных объектов (пляж, парк, школа, больница, рынок, стадион и т.п.)
     * GPT возвращает координаты напрямую в формате LAT:52.2873,LNG:76.9674 —
     * это обходит ограниченное покрытие Nominatim по Казахстану.
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
        // Паттерны: ул. / улица / пр. / проспект / пер. / переулок / р-н / район / посёлок / с. / село
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
            if (found.length() > 80) break; // не берём слишком длинный кусок
        }
        return found.toString();
    }

    /**
     * Один запрос к Nominatim с viewbox по Павлодарской области.
     * Возвращает {lat, lng} или null если ничего не найдено.
     */
    private double[] tryNominatim(String query) {
        try {
            String encoded = java.net.URLEncoder.encode(query, "UTF-8");
            // viewbox: lon_min,lat_min,lon_max,lat_max границ Павлодарской области
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

    private String selectBestSpeechResult(ArrayList<String> matches, String lang) {
        if (matches == null || matches.isEmpty()) return "";
        if (matches.size() == 1) return matches.get(0);
        if ("kk".equals(lang)) {
            for (String candidate : matches) {
                if (candidate.matches(".*[ғқңөүұіәһ].*")) return candidate;
            }
        }
        return matches.get(0); // первый = наибольшая уверенность движка
    }

    // ----------------------------------------------------------------
    //  Logout
    // ----------------------------------------------------------------

    private void logout() {
        FirebaseAuth.getInstance().signOut();
        Intent intent = new Intent(this, LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
    }

    // ── Фото ────────────────────────────────────────────────

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) return;
        if (requestCode == PhotoHelper.REQUEST_CAMERA) {
            if (photoHelper.cameraFileUri != null) {
                showPhotoPreview(photoHelper.cameraFileUri);
            }
        } else if (requestCode == PhotoHelper.REQUEST_GALLERY) {
            if (data != null && data.getData() != null) {
                showPhotoPreview(data.getData());
            }
        }
    }

    /**
     * Показывает превью выбранного фото над строкой ввода (как в ChatGPT).
     * Фото не отправляется — пользователь может написать подпись и нажать Send.
     */
    private void showPhotoPreview(android.net.Uri imageUri) {
        pendingPhotoUri = imageUri;
        binding.photoPreviewContainer.setVisibility(android.view.View.VISIBLE);
        // Glide для плавной загрузки с закруглёнными углами
        com.bumptech.glide.Glide.with(this)
                .load(imageUri)
                .centerCrop()
                .transform(new com.bumptech.glide.load.resource.bitmap.RoundedCorners(
                        (int) (16 * getResources().getDisplayMetrics().density)))
                .into(binding.ivPhotoPreview);
        binding.etInput.requestFocus();
    }

    /**
     * Сбрасывает превью фото — пользователь нажал X.
     */
    private void clearPhotoPreview() {
        pendingPhotoUri = null;
        pendingImgMsg   = null;
        binding.photoPreviewContainer.setVisibility(android.view.View.GONE);
        binding.ivPhotoPreview.setImageDrawable(null);
    }

    /**
     * Отправляет фото вместе с текстовой подписью.
     * Фото добавляется в чат, загружается в Firebase Storage,
     * ИИ получает контекст: подпись + пометка о фото.
     */
    private void sendMessageWithPhoto(android.net.Uri imageUri, String caption) {
        if (isLoading) return;
        stopAllSpeech();
        if (isListening) stopVoiceInput();

        // Очищаем превью и поле ввода
        clearPhotoPreview();
        binding.etInput.setText("");

        // 1. Добавляем фото-пузырёк в чат
        ChatMessage imgMsg = ChatMessage.imageMessage(imageUri.toString());
        adapter.addMessage(imgMsg);
        pendingImgMsg = imgMsg;

        // 2. Если есть подпись — добавляем как текстовое сообщение пользователя
        if (!caption.isEmpty()) {
            adapter.addMessage(new ChatMessage(ChatMessage.TYPE_USER, caption));
        }

        adapter.addMessage(ChatMessage.loading());
        scrollToBottom();

        isLoading = true;
        setInputEnabled(false);

        // 3. Загружаем фото в Firebase Storage (обновляем URL в объекте сообщения)
        StorageUploader.upload(imageUri, new StorageUploader.UploadCallback() {
            @Override public void onSuccess(String url) {
                imgMsg.imageUri = url;
                android.util.Log.d("Photo", "Загружено: " + url);
            }
            @Override public void onFailure(Exception e) {
                android.util.Log.e("Photo", "Ошибка загрузки фото", e);
            }
        });

        // 4. Отправляем ИИ: подпись + пометка о фото для контекста
        String aiPrompt = caption.isEmpty()
                ? getString(R.string.photo_context_no_caption)
                : caption + "\n" + getString(R.string.photo_context_with_caption);
        callOpenAI(aiPrompt);
    }

    /**
     * Открывает фото на весь экран в модальном диалоге.
     * Поддерживает base64 data URL и обычные https:// ссылки.
     * Закрытие — тап по фото или за его пределами.
     */
    private void showFullScreenPhoto(String imageUri) {
        // Создаём контейнер на чёрном фоне
        android.widget.FrameLayout container = new android.widget.FrameLayout(this);
        container.setBackgroundColor(0xFF000000);

        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT);
        container.addView(iv, lp);

        // Загружаем изображение
        if (imageUri != null && imageUri.startsWith("data:image")) {
            try {
                String b64 = imageUri.substring(imageUri.indexOf(",") + 1);
                byte[] bytes = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP);
                android.graphics.Bitmap bmp = android.graphics.BitmapFactory
                        .decodeByteArray(bytes, 0, bytes.length);
                iv.setImageBitmap(bmp);
            } catch (Exception ignored) {}
        } else {
            com.bumptech.glide.Glide.with(this).load(imageUri).into(iv);
        }

        // Диалог на весь экран
        android.app.Dialog dialog = new android.app.Dialog(this,
                android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.setContentView(container);
        dialog.getWindow().setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT);

        // Закрыть по тапу
        container.setOnClickListener(v -> dialog.dismiss());
        iv.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    private void showBitriksInfo() {
        android.view.View dialogView = getLayoutInflater().inflate(
                android.R.layout.simple_list_item_2, null);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.bitriks_info_title))
                .setMessage(getString(R.string.bitriks_info_msg))
                .setPositiveButton("OK", null)
                .show();
    }
}
