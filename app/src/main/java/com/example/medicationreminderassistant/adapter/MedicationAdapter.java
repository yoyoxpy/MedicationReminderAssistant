package com.example.medicationreminderassistant.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.medicationreminderassistant.R;
import com.example.medicationreminderassistant.model.Medication;
import com.example.medicationreminderassistant.util.TimeUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 主页药品列表适配器。takenIds 为今天已经打卡的药品 id 集合。
 */
public class MedicationAdapter extends RecyclerView.Adapter<MedicationAdapter.MedicationViewHolder> {

    public interface OnItemClickListener {
        void onClick(long medicationId);
    }

    private final List<Medication> items = new ArrayList<>();
    private final Set<Long> takenIds = new HashSet<>();
    private final OnItemClickListener listener;

    public MedicationAdapter(OnItemClickListener listener) {
        this.listener = listener;
    }

    public void submit(List<Medication> medications, Set<Long> taken) {
        items.clear();
        items.addAll(medications);
        takenIds.clear();
        takenIds.addAll(taken);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public MedicationViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_medication, parent, false);
        return new MedicationViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull MedicationViewHolder holder, int position) {
        Medication m = items.get(position);

        holder.tvName.setText(m.getName());
        String dosage = m.getDosage() != null && !m.getDosage().isEmpty()
                ? m.getDosage() + " · "
                : "";
        holder.tvInfo.setText(dosage + TimeUtils.formatTimes(m.getTimes()));

        String name = m.getName();
        holder.tvAvatar.setText(name != null && !name.isEmpty() ? name.substring(0, 1) : "药");

        if (!m.isEnabled()) {
            holder.tvStatus.setText("已停用");
            holder.tvStatus.setTextColor(holder.itemView.getContext()
                    .getColor(R.color.text_secondary));
        } else if (takenIds.contains(m.getId())) {
            holder.tvStatus.setText("今日已服");
            holder.tvStatus.setTextColor(holder.itemView.getContext()
                    .getColor(R.color.brand_primary));
        } else {
            holder.tvStatus.setText("提醒中");
            holder.tvStatus.setTextColor(holder.itemView.getContext()
                    .getColor(R.color.brand_primary));
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onClick(m.getId());
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class MedicationViewHolder extends RecyclerView.ViewHolder {
        TextView tvAvatar;
        TextView tvName;
        TextView tvInfo;
        TextView tvStatus;

        MedicationViewHolder(@NonNull View itemView) {
            super(itemView);
            tvAvatar = itemView.findViewById(R.id.tvAvatar);
            tvName = itemView.findViewById(R.id.tvName);
            tvInfo = itemView.findViewById(R.id.tvInfo);
            tvStatus = itemView.findViewById(R.id.tvStatus);
        }
    }
}
