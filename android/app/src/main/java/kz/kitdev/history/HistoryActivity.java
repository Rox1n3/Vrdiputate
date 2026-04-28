package kz.kitdev.history;

import android.content.res.ColorStateList;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.SearchView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;

import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.firebase.firestore.WriteBatch;

import java.util.Set;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import kz.kitdev.BaseActivity;
import kz.kitdev.BuildConfig;
import kz.kitdev.R;
import kz.kitdev.chat.LangManager;
import kz.kitdev.databinding.ActivityHistoryBinding;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class HistoryActivity extends BaseActivity {

    private static final String OPENAI_TTS_URL = "https://api.openai.com/v1/audio/speech";

    private ActivityHistoryBinding binding;
    private HistoryAdapter adapter;
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();

    private TextToSpeech tts;
    private boolean ttsReady = false;

    private OkHttpClient httpClient;
    private volatile AudioTrack dialogAudioTrack;
    private volatile int dialogTtsSession = 0;

    private String langOnCreate;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        langOnCreate = LangManager.get(this);
        binding = ActivityHistoryBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.toolbar.setNavigationOnClickListener(v -> finish());

        binding.btnAnalytics.setOnClickListener(v ->
                startActivity(new android.content.Intent(this,
                        kz.kitdev.analytics.AnalyticsActivity.class)));

        httpClient = new OkHttpClient.Builder()
                .connectionPool(new okhttp3.ConnectionPool(5, 5, TimeUnit.MINUTES))
                .build();
        tts = new TextToSpeech(this, status -> ttsReady = (status == TextToSpeech.SUCCESS),
                "com.google.android.tts");

        adapter = new HistoryAdapter();
        binding.recyclerView.setLayoutManager(new LinearLayoutManager(this));
        binding.recyclerView.setItemAnimator(null); // отключаем анимации смены элементов
        binding.recyclerView.setAdapter(adapter);

        adapter.setListener(new HistoryAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(HistoryItem item) {
                showAnswerDialog(item);
            }

            @Override
            public void onDeleteClick(HistoryItem item) {
                showDeleteDialog(item);
            }

            @Override
            public void onSelectionChanged(int count) {
                updateSelectionUi(count);
            }
        });

        // FAB удаление выбранных
        binding.fabDeleteSelected.setOnClickListener(v -> {
            int count = adapter.getSelectedCount();
            if (count == 0) return;
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(HistoryActivity.this)
                    .setTitle(R.string.history_delete_title)
                    .setMessage("Удалить " + count + " " + pluralRecords(count) + "?")
                    .setPositiveButton(R.string.history_delete_confirm, (d, w) -> deleteSelectedItems())
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });

        // Назад: если режим выбора — выйти из него, иначе закрыть экран
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (adapter.isSelectionMode()) {
                    adapter.setSelectionMode(false);
                    updateSelectionUi(0);
                } else {
                    finish();
                }
            }
        });

        binding.searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                adapter.filter(query);
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                adapter.filter(newText);
                return true;
            }
        });

        loadHistory();
    }

    private void loadHistory() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            finish();
            return;
        }

        db.collection("users")
                .document(user.getUid())
                .collection("history")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .get()
                .addOnSuccessListener(snapshots -> {
                    List<HistoryItem> items = new ArrayList<>();
                    for (QueryDocumentSnapshot doc : snapshots) {
                        HistoryItem item = new HistoryItem();
                        item.id = doc.getId();
                        item.question = doc.getString("question");
                        item.answer = doc.getString("answer");
                        item.lang = doc.getString("lang");
                        com.google.firebase.Timestamp ts = doc.getTimestamp("createdAt");
                        item.timestampMillis = ts != null ? ts.toDate().getTime() : 0;
                        if (item.question == null) item.question = "";
                        if (item.answer == null) item.answer = "";
                        if (item.lang == null) item.lang = "";
                        items.add(item);
                    }
                    adapter.setItems(items);
                    updateEmptyState(items.isEmpty());
                })
                .addOnFailureListener(e -> updateEmptyState(true));
    }

    private void showAnswerDialog(HistoryItem item) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_history_answer, null);
        TextView tvQuestion = view.findViewById(R.id.tvDialogQuestion);
        TextView tvAnswer = view.findViewById(R.id.tvDialogAnswer);
        ImageButton btnTts = view.findViewById(R.id.btnDialogTts);
        ImageButton btnClose = view.findViewById(R.id.btnDialogClose);

        tvQuestion.setText(item.question);
        tvAnswer.setText(item.answer.isEmpty() ? "—" : item.answer);

        // MaterialAlertDialogBuilder без setTitle / setPositiveButton — нет серых полос
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(view)
                .create();

        // Устанавливаем начальный цвет иконки — нейтральный серый
        setTtsButtonColor(btnTts, false);

        btnTts.setOnClickListener(v -> {
            if (item.answer.isEmpty()) return;
            // Detect Kazakh from stored lang OR from unique Kazakh chars in the answer text.
            // The second check covers older Firestore entries saved before the lang-fix.
            boolean isKazakh = "kk".equals(item.lang)
                    || item.answer.matches(".*[ғқңөүұіәһ].*")
                    || item.question.matches(".*[ғқңөүұіәһ].*");
            String itemLang = isKazakh ? "kk"
                    : (item.lang != null && !item.lang.isEmpty() ? item.lang : "ru");
            String voice = "onyx"; // глубокий мужской для всех языков
            boolean currentlyPlaying = (dialogAudioTrack != null);

            if (currentlyPlaying) {
                stopDialogAudioTrack();
                setTtsButtonColor(btnTts, false);
            } else {
                setTtsButtonColor(btnTts, true);
                String ttsText = kz.kitdev.util.TtsPreprocessor.prepare(item.answer, itemLang);
                speakViaOpenAIInDialog(ttsText, voice, btnTts);
            }
        });

        // При закрытии диалога — остановить озвучку и сбросить иконку
        dialog.setOnDismissListener(d -> {
            stopDialogAudioTrack();
            setTtsButtonColor(btnTts, false);
        });

        btnClose.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    /** Меняет цвет иконки говорящего человека: активный = colorPrimary, обычный = colorTextSecondary */
    private void setTtsButtonColor(ImageButton btn, boolean active) {
        int color = active
                ? ContextCompat.getColor(this, R.color.colorPrimary)
                : ContextCompat.getColor(this, R.color.colorTextSecondary);
        ImageViewCompat.setImageTintList(btn, ColorStateList.valueOf(color));
    }

    /** Применяет локаль TTS с фолбэком на ru-RU */
    private void applyTtsLocale(String lang) {
        String langCode = lang.isEmpty() ? "ru" : lang;
        Locale locale = LangManager.toLocale(langCode);
        int result = tts.setLanguage(locale);
        if (result == TextToSpeech.LANG_NOT_SUPPORTED || result == TextToSpeech.LANG_MISSING_DATA) {
            if (langCode.equals("kk")) {
                result = tts.setLanguage(new Locale("kk", "KZ"));
                if (result == TextToSpeech.LANG_NOT_SUPPORTED || result == TextToSpeech.LANG_MISSING_DATA) {
                    tts.setLanguage(new Locale("ru", "RU"));
                }
            } else {
                tts.setLanguage(new Locale("ru", "RU"));
            }
        }
        tts.setSpeechRate(langCode.equals("kk") ? 0.82f : 0.9f);
    }

    private void showDeleteDialog(HistoryItem item) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.history_delete_title)
                .setMessage(R.string.history_delete_message)
                .setPositiveButton(R.string.history_delete_confirm, (d, w) -> deleteItem(item))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void deleteItem(HistoryItem item) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;

        db.collection("users")
                .document(user.getUid())
                .collection("history")
                .document(item.id)
                .delete()
                .addOnSuccessListener(v -> {
                    adapter.removeItem(item.id);
                    if (adapter.getItemCount() == 0) {
                        updateEmptyState(true);
                    }
                });
    }

    private void updateSelectionUi(int count) {
        if (count <= 0) {
            binding.tvSelectionCount.setVisibility(View.GONE);
            binding.fabDeleteSelected.setVisibility(View.GONE);
        } else {
            binding.tvSelectionCount.setText(count + " " + pluralRecords(count));
            binding.tvSelectionCount.setVisibility(View.VISIBLE);
            binding.fabDeleteSelected.setVisibility(View.VISIBLE);
        }
    }

    private void deleteSelectedItems() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        Set<String> ids = adapter.getSelectedIds();
        if (ids.isEmpty()) return;
        WriteBatch batch = db.batch();
        for (String id : ids) {
            batch.delete(db.collection("users")
                    .document(user.getUid())
                    .collection("history")
                    .document(id));
        }
        batch.commit().addOnSuccessListener(v -> {
            adapter.removeItems(ids);
            adapter.setSelectionMode(false);
            updateSelectionUi(0);
            if (adapter.getItemCount() == 0) updateEmptyState(true);
        });
    }

    private String pluralRecords(int count) {
        int mod10 = count % 10;
        int mod100 = count % 100;
        String lang = kz.kitdev.chat.LangManager.get(this);
        if ("en".equals(lang)) return count == 1 ? "record" : "records";
        if ("kk".equals(lang)) return "жазба";
        if (mod10 == 1 && mod100 != 11) return "запись";
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 10 || mod100 >= 20)) return "записи";
        return "записей";
    }

    private void updateEmptyState(boolean isEmpty) {
        binding.emptyView.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        binding.recyclerView.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }

    /**
     * OpenAI TTS (tts-1-hd) для воспроизведения в диалоге истории.
     * voice: nova (kk) · shimmer (ru) · alloy (en).
     * 200мс тишины в начале — DAC просыпается без щелчка.
     */
    private void speakViaOpenAIInDialog(String text, String voice, ImageButton btnTts) {
        stopDialogAudioTrack();
        final int session = ++dialogTtsSession;

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
                if (!response.isSuccessful() || response.body() == null) {
                    runOnUiThread(() -> setTtsButtonColor(btnTts, false));
                    return;
                }

                // Скачиваем все PCM байты, затем воспроизводим — без артефактов
                ByteArrayOutputStream baos = new ByteArrayOutputStream(65536);
                try (InputStream is = response.body().byteStream()) {
                    byte[] chunk = new byte[8192];
                    int n;
                    while ((n = is.read(chunk)) != -1) {
                        if (dialogTtsSession != session) return;
                        baos.write(chunk, 0, n);
                    }
                }
                if (dialogTtsSession != session) return;
                byte[] pcmData = baos.toByteArray();
                if (pcmData.length == 0) {
                    runOnUiThread(() -> setTtsButtonColor(btnTts, false));
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

                if (dialogTtsSession != session) { track.release(); return; }

                track.write(fullData, 0, fullData.length);
                dialogAudioTrack = track;
                track.play();

                final long durationMs = (long) fullData.length * 1000L / (SAMPLE_RATE * 2L);
                final AudioTrack finalTrack = track;
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    if (dialogTtsSession != session) return;
                    dialogTtsSession++;
                    dialogAudioTrack = null;
                    try { finalTrack.stop(); } catch (Exception ignored) {}
                    try { finalTrack.release(); } catch (Exception ignored) {}
                    setTtsButtonColor(btnTts, false);
                }, durationMs + 300);

            } catch (Exception e) {
                runOnUiThread(() -> setTtsButtonColor(btnTts, false));
            }
        }).start();
    }

    private JSONObject buildTtsBody(String text, String voice) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", "tts-1-hd");
        body.put("voice", voice);
        body.put("input", text);
        body.put("response_format", "pcm");
        body.put("speed", 0.95);
        return body;
    }

    private void stopDialogAudioTrack() {
        dialogTtsSession++;
        AudioTrack track = dialogAudioTrack;
        dialogAudioTrack = null;
        if (track != null) {
            try { track.pause(); track.flush(); } catch (Exception ignored) {}
            try { track.stop(); } catch (Exception ignored) {}
            try { track.release(); } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!LangManager.get(this).equals(langOnCreate)) {
            recreate();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        stopDialogAudioTrack();
    }
}
