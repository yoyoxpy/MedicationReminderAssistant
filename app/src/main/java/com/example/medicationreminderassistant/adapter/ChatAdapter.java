package com.example.medicationreminderassistant.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.medicationreminderassistant.R;

import java.util.ArrayList;
import java.util.List;

/** 聊天消息列表：role=user 渲染右侧绿色气泡，assistant 渲染左侧浅绿气泡 */
public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.VH> {

    public static class Msg {
        public final String role;
        public String content;

        public Msg(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    private final List<Msg> items = new ArrayList<>();

    public List<Msg> getItems() {
        return items;
    }

    public void add(Msg msg) {
        items.add(msg);
        notifyItemInserted(items.size() - 1);
    }

    public void refreshLast() {
        notifyItemChanged(items.size() - 1);
    }

    @Override
    public int getItemViewType(int position) {
        return "user".equals(items.get(position).role) ? 1 : 0;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layout = viewType == 1 ? R.layout.item_chat_user : R.layout.item_chat_ai;
        return new VH(LayoutInflater.from(parent.getContext())
                .inflate(layout, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        holder.bubble.setText(items.get(position).content);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView bubble;

        VH(@NonNull View itemView) {
            super(itemView);
            bubble = itemView.findViewById(R.id.tvBubble);
        }
    }
}
