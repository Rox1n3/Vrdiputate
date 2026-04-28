package kz.kitdev.analytics;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.SearchView;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.firestore.WriteBatch;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import kz.kitdev.BaseActivity;
import kz.kitdev.R;
import kz.kitdev.chat.LangManager;
import kz.kitdev.databinding.ActivityAnalyticsBinding;
import kz.kitdev.fcm.AppFirebaseMessagingService;


public class AnalyticsActivity extends BaseActivity {

    private ActivityAnalyticsBinding binding;
    private AnalyticsAdapter adapter;
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();
    private ListenerRegistration complaintsListener;

    /** Язык на момент создания активити — для отслеживания смены языка */
    private String langOnCreate;

    /** ID заявки из FCM-уведомления, которую нужно подсветить */
    private String highlightComplaintId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        langOnCreate = LangManager.get(this);
        binding = ActivityAnalyticsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Получаем ID заявки из FCM-уведомления (если активити открыта через тап на уведомление)
        highlightComplaintId = getIntent().getStringExtra(AppFirebaseMessagingService.EXTRA_HIGHLIGHT_ID);

        binding.toolbar.setNavigationOnClickListener(v -> finish());

        // Кнопка «Вернуться в историю запросов»
        binding.btnBackToHistory.setOnClickListener(v -> finish());

        adapter = new AnalyticsAdapter();
        binding.recyclerView.setLayoutManager(new LinearLayoutManager(this));
        binding.recyclerView.setItemAnimator(null);
        binding.recyclerView.setAdapter(adapter);

        adapter.setListener(new AnalyticsAdapter.OnItemClickListener() {
            @Override public void onItemClick(ComplaintItem item) {
                // Снимаем подсветку и открываем диалог с перепиской
                adapter.clearHighlight();
                highlightComplaintId = null;
                showDetailDialog(item);
            }
            @Override public void onDeleteClick(ComplaintItem item) { showDeleteDialog(item); }
            @Override public void onSelectionChanged(int count)   { updateSelectionUi(count); }
        });

        binding.fabDeleteSelected.setOnClickListener(v -> {
            int count = adapter.getSelectedCount();
            if (count == 0) return;
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.history_delete_title)
                    .setMessage("Удалить " + count + " " + pluralRecords(count) + "?")
                    .setPositiveButton(R.string.history_delete_confirm, (d, w) -> deleteSelectedItems())
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (adapter.isSelectionMode()) {
                    adapter.setSelectionMode(false);
                    updateSelectionUi(0);
                } else {
                    finish();
                }
            }
        });

        binding.searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override public boolean onQueryTextSubmit(String q) { adapter.filter(q); return true; }
            @Override public boolean onQueryTextChange(String t) { adapter.filter(t); return true; }
        });

        loadComplaints();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Если язык сменился пока активити была в фоне — пересоздаём, чтобы применить новую локаль
        if (!LangManager.get(this).equals(langOnCreate)) {
            recreate();
        }
    }

    private void loadComplaints() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) { finish(); return; }

        complaintsListener = db.collection("users")
                .document(user.getUid())
                .collection("complaints")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .addSnapshotListener((snapshots, e) -> {
                    if (e != null || snapshots == null) { updateEmptyState(true); return; }
                    List<ComplaintItem> items = new ArrayList<>();
                    for (QueryDocumentSnapshot doc : snapshots) {
                        ComplaintItem item = new ComplaintItem();
                        item.id      = doc.getId();
                        item.fio     = strOrEmpty(doc.getString("fio"));
                        item.phone   = strOrEmpty(doc.getString("phone"));
                        item.problem = strOrEmpty(doc.getString("problem"));
                        item.address = strOrEmpty(doc.getString("address"));
                        item.lang    = strOrEmpty(doc.getString("lang"));
                        item.status  = strOrEmpty(doc.getString("status"));
                        Long num = doc.getLong("complaintNumber");
                        item.complaintNumber = num != null ? num.intValue() : 0;
                        com.google.firebase.Timestamp ts = doc.getTimestamp("createdAt");
                        item.timestampMillis = ts != null ? ts.toDate().getTime() : 0;
                        //noinspection unchecked
                        item.messages = (List<Map<String, Object>>) doc.get("messages");
                        if (item.messages == null) item.messages = new ArrayList<>();
                        items.add(item);
                    }
                    adapter.setItems(items);
                    updateEmptyState(items.isEmpty());

                    // Подсветить и прокрутить к заявке из push-уведомления
                    if (highlightComplaintId != null && !highlightComplaintId.isEmpty()) {
                        adapter.setHighlightedId(highlightComplaintId);
                        scrollToHighlighted(items);
                    }
                });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Активити уже открыта — обновляем подсветку из нового уведомления
        String newId = intent.getStringExtra(AppFirebaseMessagingService.EXTRA_HIGHLIGHT_ID);
        if (newId != null && !newId.isEmpty()) {
            highlightComplaintId = newId;
            adapter.setHighlightedId(highlightComplaintId);
            // Прокрутить к нужной заявке
            List<ComplaintItem> current = adapter.getAllItems();
            scrollToHighlighted(current);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Снять подсветку при уходе с экрана (пользователь увидел заявку)
        adapter.clearHighlight();
        highlightComplaintId = null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (complaintsListener != null) complaintsListener.remove();
    }

    // ── Прокрутка к подсвеченной заявке ─────────────────────────────────

    private void scrollToHighlighted(List<ComplaintItem> items) {
        if (highlightComplaintId == null) return;
        for (int i = 0; i < items.size(); i++) {
            if (highlightComplaintId.equals(items.get(i).id)) {
                final int pos = i;
                binding.recyclerView.post(() ->
                        binding.recyclerView.smoothScrollToPosition(pos));
                break;
            }
        }
    }

    // ── Диалог с полной перепиской ────────────────────────────────────────

    private void showDetailDialog(ComplaintItem item) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_complaint_detail, null);

        TextView tvHeader       = view.findViewById(R.id.tvComplaintHeader);
        TextView tvMessages     = view.findViewById(R.id.tvComplaintMessages);
        TextView tvDialogStatus = view.findViewById(R.id.tvDialogStatus);

        // Шапка с данными заявления
        String numStr = item.complaintNumber > 0
                ? "№" + String.format("%04d", item.complaintNumber) + "\n"
                : "";
        tvHeader.setText(
                numStr +
                getString(R.string.analytics_label_fio) + item.fio + "\n" +
                getString(R.string.analytics_label_phone).trim() + ": " + item.phone + "\n" +
                getString(R.string.analytics_label_problem) + item.problem + "\n" +
                getString(R.string.analytics_label_address) + item.address);

        // Статус в шапке диалога
        String status = item.status != null ? item.status : "processing";
        String statusText;
        int statusColor;
        switch (status) {
            case "in_work":  statusText = getString(R.string.status_in_work);  statusColor = 0xFF14B8A6; break;
            case "done":     statusText = getString(R.string.status_done);     statusColor = 0xFF22C55E; break;
            case "rejected": statusText = getString(R.string.status_rejected); statusColor = 0xFFEF4444; break;
            default:         statusText = getString(R.string.status_processing); statusColor = 0xFF9E9E9E; break;
        }
        tvDialogStatus.setText(statusText);
        tvDialogStatus.setBackgroundTintList(android.content.res.ColorStateList.valueOf(statusColor));

        // Полная переписка
        StringBuilder sb = new StringBuilder();
        if (item.messages != null) {
            for (Map<String, Object> msg : item.messages) {
                String role = String.valueOf(msg.get("role"));
                String text = String.valueOf(msg.get("text"));
                if ("user".equals(role)) {
                    sb.append(getString(R.string.analytics_dialog_you)).append(text).append("\n\n");
                } else {
                    sb.append(getString(R.string.analytics_dialog_ai)).append(text).append("\n\n");
                }
            }
        }
        if (sb.length() == 0) sb.append(getString(R.string.analytics_no_messages));
        tvMessages.setText(sb.toString().trim());

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(view)
                .create();

        view.findViewById(R.id.btnDialogClose).setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    // ── Удаление ─────────────────────────────────────────────────────────

    private void showDeleteDialog(ComplaintItem item) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.history_delete_title)
                .setMessage("Удалить это заявление из аналитики?")
                .setPositiveButton(R.string.history_delete_confirm, (d, w) -> deleteItem(item))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void deleteItem(ComplaintItem item) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        db.collection("users").document(user.getUid())
                .collection("complaints").document(item.id)
                .delete()
                .addOnSuccessListener(v -> {
                    adapter.removeItem(item.id);
                    if (adapter.getItemCount() == 0) updateEmptyState(true);
                });
    }

    private void deleteSelectedItems() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        Set<String> ids = adapter.getSelectedIds();
        if (ids.isEmpty()) return;
        WriteBatch batch = db.batch();
        for (String id : ids) {
            batch.delete(db.collection("users").document(user.getUid())
                    .collection("complaints").document(id));
        }
        batch.commit().addOnSuccessListener(v -> {
            adapter.removeItems(ids);
            adapter.setSelectionMode(false);
            updateSelectionUi(0);
            if (adapter.getItemCount() == 0) updateEmptyState(true);
        });
    }

    // ── UI ────────────────────────────────────────────────────────────────

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

    private void updateEmptyState(boolean isEmpty) {
        binding.emptyView.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        binding.recyclerView.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }

    private String pluralRecords(int count) {
        String lang = kz.kitdev.chat.LangManager.get(this);
        if ("en".equals(lang)) return count == 1 ? "record" : "records";
        if ("kk".equals(lang)) return "жазба";
        int m10 = count % 10, m100 = count % 100;
        if (m10 == 1 && m100 != 11) return "запись";
        if (m10 >= 2 && m10 <= 4 && (m100 < 10 || m100 >= 20)) return "записи";
        return "записей";
    }

    private String strOrEmpty(String s) { return s != null ? s : ""; }
}
