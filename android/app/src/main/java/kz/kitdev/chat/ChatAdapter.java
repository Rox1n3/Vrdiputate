package kz.kitdev.chat;

import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import kz.kitdev.R;

public class ChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface OnBotTapListener {
        void onBotMessageTap(String question, String answer);
    }

    public interface OnImageTapListener {
        void onImageTap(String imageUri);
    }

    private final List<ChatMessage> messages = new ArrayList<>();
    private OnBotTapListener  botTapListener;
    private OnImageTapListener imageTapListener;

    public void setBotTapListener(OnBotTapListener l)   { botTapListener   = l; }
    public void setImageTapListener(OnImageTapListener l) { imageTapListener = l; }

    // --- ViewHolders ---

    static class UserViewHolder extends RecyclerView.ViewHolder {
        TextView tvText;
        UserViewHolder(View v) {
            super(v);
            tvText = v.findViewById(R.id.tvMessageText);
        }
    }

    static class BotViewHolder extends RecyclerView.ViewHolder {
        TextView tvText;
        ProgressBar progressBar;
        BotViewHolder(View v) {
            super(v);
            tvText = v.findViewById(R.id.tvMessageText);
            progressBar = v.findViewById(R.id.progressBar);
        }
    }

    static class ImageViewHolder extends RecyclerView.ViewHolder {
        ImageView ivPhoto;
        ImageViewHolder(View v) {
            super(v);
            ivPhoto = v.findViewById(R.id.ivPhoto);
        }
    }

    // --- Adapter methods ---

    @Override
    public int getItemViewType(int position) {
        return messages.get(position).type;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == ChatMessage.TYPE_USER) {
            return new UserViewHolder(inf.inflate(R.layout.item_message_user, parent, false));
        } else if (viewType == ChatMessage.TYPE_IMAGE) {
            return new ImageViewHolder(inf.inflate(R.layout.item_message_image_user, parent, false));
        } else {
            return new BotViewHolder(inf.inflate(R.layout.item_message_bot, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        ChatMessage msg = messages.get(position);
        if (holder instanceof ImageViewHolder) {
            ImageView iv = ((ImageViewHolder) holder).ivPhoto;
            if (msg.imageUri != null && !msg.imageUri.isEmpty()) {
                if (msg.imageUri.startsWith("data:image")) {
                    // base64 data URL → декодируем в Bitmap вручную
                    try {
                        String b64 = msg.imageUri.substring(msg.imageUri.indexOf(",") + 1);
                        byte[] bytes = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP);
                        android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                        iv.setImageBitmap(bmp);
                        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    } catch (Exception ignored) {}
                } else {
                    Glide.with(iv.getContext())
                            .load(msg.imageUri.startsWith("http") ? msg.imageUri : Uri.parse(msg.imageUri))
                            .centerCrop()
                            .into(iv);
                }
                // Тап — открывает фото на весь экран
                iv.setOnClickListener(v -> {
                    if (imageTapListener != null) imageTapListener.onImageTap(msg.imageUri);
                });
            }
        } else if (holder instanceof UserViewHolder) {
            ((UserViewHolder) holder).tvText.setText(msg.text);
        } else if (holder instanceof BotViewHolder) {
            BotViewHolder bvh = (BotViewHolder) holder;
            if (msg.isLoading) {
                bvh.tvText.setVisibility(View.GONE);
                bvh.progressBar.setVisibility(View.VISIBLE);
                bvh.itemView.setOnClickListener(null);
                bvh.tvText.setOnClickListener(null);
            } else {
                bvh.progressBar.setVisibility(View.GONE);
                bvh.tvText.setVisibility(View.VISIBLE);
                bvh.tvText.setText(parseLinks(msg.text), TextView.BufferType.SPANNABLE);
                bvh.tvText.setMovementMethod(LinkMovementMethod.getInstance());
                bvh.tvText.setLinkTextColor(0xFF14B8A6);
                // Нажатие на ответ бота → сохранить в историю.
                // Слушатель ставится на tvText (а не itemView), потому что
                // textIsSelectable="true" перехватывает касания на TextView,
                // не давая им дойти до родительского view.
                if (botTapListener != null && msg.question != null) {
                    // Слушатель ТОЛЬКО на tvText — не на itemView.
                    // itemView занимает всю ширину строки, поэтому тап в пустом месте
                    // слева/справа от пузыря тоже попадал бы в itemView и сохранял
                    // ответ в историю. Пользователь должен нажать именно на текст.
                    bvh.tvText.setOnClickListener(v ->
                            botTapListener.onBotMessageTap(msg.question, msg.text));
                    bvh.itemView.setOnClickListener(null); // явно сбрасываем, если view переиспользовано
                } else {
                    bvh.tvText.setOnClickListener(null);
                    bvh.itemView.setOnClickListener(null);
                }
            }
        }
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    // --- Public helpers ---

    public void addMessage(ChatMessage msg) {
        messages.add(msg);
        notifyItemInserted(messages.size() - 1);
    }

    /** Обновляет текст последнего сообщения во время стриминга */
    public void updateLastText(String text) {
        if (messages.isEmpty()) return;
        int idx = messages.size() - 1;
        ChatMessage msg = messages.get(idx);
        msg.text = text;
        msg.isLoading = false;
        notifyItemChanged(idx);
    }

    /** Заменяет последнее сообщение (убирает loading, показывает ответ) */
    public void replaceLastWithAnswer(String text, String question) {
        if (messages.isEmpty()) return;
        int last = messages.size() - 1;
        messages.get(last).text = text;
        messages.get(last).question = question;
        messages.get(last).isLoading = false;
        notifyItemChanged(last);
    }

    /** Дописывает текст к последнему сообщению бота */
    public void appendToLastBotMessage(String suffix) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage msg = messages.get(i);
            if (msg.type == ChatMessage.TYPE_BOT && !msg.isLoading && msg.text != null) {
                msg.text = msg.text + suffix;
                notifyItemChanged(i);
                return;
            }
        }
    }

    /** Удаляет последнее сообщение (если запрос упал) */
    public void removeLastMessage() {
        if (messages.isEmpty()) return;
        int last = messages.size() - 1;
        messages.remove(last);
        notifyItemRemoved(last);
    }

    public void clear() {
        int size = messages.size();
        messages.clear();
        notifyItemRangeRemoved(0, size);
    }

    /** Возвращает копию списка сообщений для сохранения состояния */
    public ArrayList<ChatMessage> getMessages() {
        return new ArrayList<>(messages);
    }

    /** Восстанавливает список сообщений после пересоздания Activity */
    public void restoreMessages(List<ChatMessage> saved) {
        messages.clear();
        messages.addAll(saved);
        notifyDataSetChanged();
    }

    /**
     * Парсит markdown-ссылки [текст](url) и голые https://... в SpannableStringBuilder
     * с кликабельными URLSpan, убирая markdown-синтаксис из отображаемого текста.
     */
    private static CharSequence parseLinks(String text) {
        if (text == null) return "";
        SpannableStringBuilder sb = new SpannableStringBuilder();
        Pattern p = Pattern.compile(
                "\\[([^\\]]+)\\]\\((https?://[^)\\s]+)\\)" // [label](url)
                + "|(https?://[^\\s<>\"]+)");               // bare URL
        Matcher m = p.matcher(text);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) sb.append(text, last, m.start());
            if (m.group(1) != null) {
                // markdown link — показываем только label
                int start = sb.length();
                sb.append(m.group(1));
                sb.setSpan(new URLSpan(m.group(2)), start, sb.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                // голый URL
                String url = m.group(3);
                int start = sb.length();
                sb.append(url);
                sb.setSpan(new URLSpan(url), start, sb.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            last = m.end();
        }
        if (last < text.length()) sb.append(text, last, text.length());
        return sb;
    }
}
