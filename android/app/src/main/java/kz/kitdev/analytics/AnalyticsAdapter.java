package kz.kitdev.analytics;

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

public class AnalyticsAdapter extends RecyclerView.Adapter<AnalyticsAdapter.ViewHolder> {

    public interface OnItemClickListener {
        void onItemClick(ComplaintItem item);
        void onDeleteClick(ComplaintItem item);
        void onSelectionChanged(int count);
    }

    private final List<ComplaintItem> items    = new ArrayList<>();
    private       List<ComplaintItem> filtered = new ArrayList<>();
    private OnItemClickListener listener;

    private boolean           selectionMode      = false;
    private boolean           skipEntryAnimation = false;
    private final Set<String> selectedIds        = new HashSet<>();

    /** ID заявки, подсвечиваемой после тапа по push-уведомлению */
    private String highlightedId = null;

    public void setListener(OnItemClickListener l) { this.listener = l; }

    /** Подсвечивает указанную заявку бирюзовым цветом. */
    public void setHighlightedId(String id) {
        highlightedId = id;
        notifyDataSetChanged();
    }

    /** Сбрасывает подсветку (вызывается при тапе на карточку или выходе с экрана). */
    public void clearHighlight() {
        if (highlightedId != null) {
            highlightedId = null;
            notifyDataSetChanged();
        }
    }

    public boolean hasHighlight() { return highlightedId != null; }

    // ── Данные ───────────────────────────────────────────────────────────

    public void setItems(List<ComplaintItem> newItems) {
        items.clear();
        items.addAll(newItems);
        filtered.clear();
        filtered.addAll(newItems);
        notifyDataSetChanged();
    }

    public List<ComplaintItem> getAllItems() { return new ArrayList<>(items); }

    public void filter(String query) {
        filtered.clear();
        if (query.isEmpty()) {
            filtered.addAll(items);
        } else {
            String q = query.toLowerCase();
            for (ComplaintItem item : items) {
                if (item.problem.toLowerCase().contains(q)
                        || item.fio.toLowerCase().contains(q)
                        || item.address.toLowerCase().contains(q)
                        || item.phone.contains(q)) {
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

    public boolean isSelectionMode()    { return selectionMode; }
    public int     getSelectedCount()   { return selectedIds.size(); }
    public Set<String> getSelectedIds() { return new HashSet<>(selectedIds); }

    public void setSelectionMode(boolean active) {
        selectionMode = active;
        if (!active) { selectedIds.clear(); skipEntryAnimation = true; }
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
                .inflate(R.layout.item_complaint, parent, false);
        ViewHolder holder = new ViewHolder(v);

        holder.itemView.setOnClickListener(view -> {
            if (holder.boundItem == null) return;
            // Подсветка снимается в onItemClick (AnalyticsActivity) — не блокируем открытие
            if (selectionMode) toggleSelection(holder.boundItem.id);
            else if (listener != null) listener.onItemClick(holder.boundItem);
        });

        holder.itemView.setOnLongClickListener(view -> {
            if (holder.boundItem == null) return true;
            if (!selectionMode) { selectionMode = true; toggleSelection(holder.boundItem.id); }
            return true;
        });

        holder.btnDelete.setOnClickListener(view -> {
            if (holder.boundItem != null && listener != null)
                listener.onDeleteClick(holder.boundItem);
        });

        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ComplaintItem item = filtered.get(position);
        Context ctx = holder.itemView.getContext();
        holder.boundItem = item;

        holder.tvProblem.setText(item.problem);
        holder.tvMeta.setText("ФИО: " + item.fio + "  •  Тел: " + item.phone);
        holder.tvAddress.setText("Адрес: " + item.address);
        SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault());
        holder.tvDate.setText(sdf.format(new Date(item.timestampMillis)));

        // Статус
        if (holder.tvStatus != null) {
            String status = item.status != null ? item.status : "processing";
            holder.tvStatus.setText(statusLabel(ctx, status));
            holder.tvStatus.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(statusColor(status)));
        }

        boolean selected    = selectedIds.contains(item.id);
        boolean highlighted = item.id.equals(highlightedId);

        // Подсветка: бирюзовая полоска снизу карточки (после тапа по push-уведомлению)
        if (highlighted && !selectionMode) {
            holder.vHighlight.setVisibility(View.VISIBLE);
            holder.card.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.colorSurface));
            holder.btnDelete.setVisibility(View.VISIBLE);
            holder.ivCheck.setVisibility(View.GONE);
            holder.itemView.setAlpha(1f);
            holder.itemView.setTranslationY(0f);
            return;
        }

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
                holder.card.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.colorSurface));
            }
        } else {
            holder.itemView.animate().cancel();
            holder.btnDelete.setVisibility(View.VISIBLE);
            holder.ivCheck.setVisibility(View.GONE);
            holder.vHighlight.setVisibility(View.GONE);
            holder.card.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.colorSurface));
            holder.card.invalidate();
            holder.itemView.setAlpha(1f);
            holder.itemView.setTranslationY(0f);

            if (!skipEntryAnimation) {
                holder.itemView.setAlpha(0f);
                holder.itemView.setTranslationY(48f);
                holder.itemView.animate()
                        .alpha(1f).translationY(0f)
                        .setDuration(280)
                        .setStartDelay(position * 45L)
                        .setInterpolator(new DecelerateInterpolator(1.4f))
                        .start();
            }
        }

        if (position == filtered.size() - 1) skipEntryAnimation = false;
    }

    @Override public int getItemCount() { return filtered.size(); }

    // ── ViewHolder ────────────────────────────────────────────────────────

    static class ViewHolder extends RecyclerView.ViewHolder {
        MaterialCardView card;
        ImageView        ivCheck;
        TextView         tvProblem, tvMeta, tvAddress, tvDate;
        TextView         tvStatus;
        ImageButton      btnDelete;
        View             vHighlight; // бирюзовая полоска снизу карточки
        ComplaintItem    boundItem;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            card      = (MaterialCardView) itemView;
            ivCheck   = itemView.findViewById(R.id.ivCheck);
            tvProblem = itemView.findViewById(R.id.tvProblem);
            tvMeta    = itemView.findViewById(R.id.tvMeta);
            tvAddress = itemView.findViewById(R.id.tvAddress);
            tvDate    = itemView.findViewById(R.id.tvDate);
            tvStatus  = itemView.findViewById(R.id.tvStatus);
            btnDelete = itemView.findViewById(R.id.btnDelete);
            vHighlight = itemView.findViewById(R.id.vHighlight);
            card.setStrokeWidth(0);
            card.setStrokeColor(android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.TRANSPARENT));
            card.setStateListAnimator(null);
        }
    }

    private String statusLabel(Context ctx, String status) {
        switch (status) {
            case "in_work":  return ctx.getString(R.string.status_in_work);
            case "done":     return ctx.getString(R.string.status_done);
            case "rejected": return ctx.getString(R.string.status_rejected);
            default:         return ctx.getString(R.string.status_processing);
        }
    }

    private int statusColor(String status) {
        switch (status) {
            case "in_work":  return 0xFF14B8A6; // teal/бирюзовая
            case "done":     return 0xFF22C55E; // green/зелёная
            case "rejected": return 0xFFEF4444; // red/красная
            default:         return 0xFF9E9E9E; // grey/серая (в обработке)
        }
    }

}
