package kz.kitdev.history;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import kz.kitdev.R;

public class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.ViewHolder> {

    public interface OnItemClickListener {
        void onItemClick(HistoryItem item);
        void onDeleteClick(HistoryItem item);
        void onSelectionChanged(int count);
    }

    private final List<HistoryItem> items    = new ArrayList<>();
    private       List<HistoryItem> filtered = new ArrayList<>();
    private OnItemClickListener listener;

    // ── Мультивыбор ──────────────────────────────────────────────────────
    private boolean           selectionMode      = false;
    private boolean           skipEntryAnimation = false;
    private final Set<String> selectedIds        = new HashSet<>();

    public void setListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    // ── Данные ───────────────────────────────────────────────────────────

    public void setItems(List<HistoryItem> newItems) {
        items.clear();
        items.addAll(newItems);
        filtered.clear();
        filtered.addAll(newItems);
        notifyDataSetChanged();
    }

    public void filter(String query) {
        filtered.clear();
        if (query.isEmpty()) {
            filtered.addAll(items);
        } else {
            String q = query.toLowerCase();
            for (HistoryItem item : items) {
                if (item.question.toLowerCase().contains(q) ||
                        item.answer.toLowerCase().contains(q)) {
                    filtered.add(item);
                }
            }
        }
        notifyDataSetChanged();
    }

    public void removeItem(String id) {
        items.removeIf(i -> i.id.equals(id));
        filtered.removeIf(i -> i.id.equals(id));
        notifyDataSetChanged();
    }

    public void removeItems(Set<String> ids) {
        items.removeIf(i -> ids.contains(i.id));
        filtered.removeIf(i -> ids.contains(i.id));
        notifyDataSetChanged();
    }

    // ── Мультивыбор ──────────────────────────────────────────────────────

    public boolean isSelectionMode() { return selectionMode; }

    public int getSelectedCount() { return selectedIds.size(); }

    public Set<String> getSelectedIds() { return new HashSet<>(selectedIds); }

    public void setSelectionMode(boolean active) {
        selectionMode = active;
        if (!active) {
            selectedIds.clear();
            skipEntryAnimation = true;
        }
        notifyDataSetChanged();
    }

    private void toggleSelection(String id) {
        if (selectedIds.contains(id)) selectedIds.remove(id);
        else selectedIds.add(id);
        if (listener != null) listener.onSelectionChanged(selectedIds.size());
        notifyDataSetChanged();
    }

    // ── RecyclerView ─────────────────────────────────────────────────────

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_history, parent, false);
        ViewHolder holder = new ViewHolder(v);

        // ── Слушатели устанавливаются ОДИН РАЗ при создании ViewHolder ───────
        // Читают selectionMode и holder.boundItem в момент нажатия (runtime),
        // не при привязке (bind-time). Это гарантирует корректное поведение
        // независимо от того, успел ли notifyDataSetChanged перебиндить view.

        holder.itemView.setOnClickListener(view -> {
            if (holder.boundItem == null) return;
            if (selectionMode) {
                toggleSelection(holder.boundItem.id);
            } else {
                if (listener != null) listener.onItemClick(holder.boundItem);
            }
        });

        holder.itemView.setOnLongClickListener(view -> {
            if (holder.boundItem == null) return true;
            if (!selectionMode) {
                selectionMode = true;
                toggleSelection(holder.boundItem.id);
            }
            return true;
        });

        holder.btnDelete.setOnClickListener(view -> {
            if (holder.boundItem == null) return;
            if (listener != null) listener.onDeleteClick(holder.boundItem);
        });

        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        HistoryItem item = filtered.get(position);
        Context ctx = holder.itemView.getContext();

        // Обновляем ссылку на текущий элемент — слушатели читают её в момент нажатия
        holder.boundItem = item;

        holder.tvQuestion.setText(item.question);
        holder.tvAnswer.setText(item.answer);
        SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault());
        holder.tvDate.setText(sdf.format(new Date(item.timestampMillis)));

        boolean selected = selectedIds.contains(item.id);

        if (selectionMode) {
            holder.btnDelete.setVisibility(View.GONE);
            holder.ivCheck.setVisibility(View.VISIBLE);

            if (selected) {
                holder.ivCheck.setImageResource(R.drawable.ic_check_selected);
                ImageViewCompat.setImageTintList(holder.ivCheck, null);
                holder.card.setCardBackgroundColor(0x1414B8A6);
            } else {
                holder.ivCheck.setImageResource(R.drawable.ic_circle_outline);
                ImageViewCompat.setImageTintList(holder.ivCheck,
                        ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.colorTextSecondary)));
                holder.card.setCardBackgroundColor(
                        ContextCompat.getColor(ctx, R.color.colorSurface));
            }

        } else {
            holder.itemView.animate().cancel();
            holder.btnDelete.setVisibility(View.VISIBLE);
            holder.ivCheck.setVisibility(View.GONE);
            holder.card.setCardBackgroundColor(
                    ContextCompat.getColor(ctx, R.color.colorSurface));
            holder.card.invalidate();
            holder.itemView.setAlpha(1f);
            holder.itemView.setTranslationY(0f);

            // Slide-up + fade-in только при первоначальной загрузке
            if (!skipEntryAnimation) {
                holder.itemView.setAlpha(0f);
                holder.itemView.setTranslationY(48f);
                holder.itemView.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(280)
                        .setStartDelay(position * 45L)
                        .setInterpolator(new DecelerateInterpolator(1.4f))
                        .start();
            }
        }

        if (position == filtered.size() - 1) skipEntryAnimation = false;
    }

    @Override
    public int getItemCount() { return filtered.size(); }

    // ── ViewHolder ────────────────────────────────────────────────────────

    static class ViewHolder extends RecyclerView.ViewHolder {
        MaterialCardView card;
        ImageView        ivCheck;
        TextView         tvQuestion, tvAnswer, tvDate;
        ImageButton      btnDelete;
        HistoryItem      boundItem; // обновляется в onBindViewHolder

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            card       = (MaterialCardView) itemView;
            ivCheck    = itemView.findViewById(R.id.ivCheck);
            tvQuestion = itemView.findViewById(R.id.tvQuestion);
            tvAnswer   = itemView.findViewById(R.id.tvAnswer);
            tvDate     = itemView.findViewById(R.id.tvDate);
            btnDelete  = itemView.findViewById(R.id.btnDelete);
            card.setStrokeWidth(0);
            card.setStrokeColor(android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.TRANSPARENT));
            card.setStateListAnimator(null);
        }
    }

}
