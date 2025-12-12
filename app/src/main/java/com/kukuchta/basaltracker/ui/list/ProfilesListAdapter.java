package com.kukuchta.basaltracker.ui.list;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.kukuchta.basaltracker.R;
import com.kukuchta.basaltracker.domain.BasalProfile;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ProfilesListAdapter extends RecyclerView.Adapter<ProfilesListAdapter.VH> {

    public interface ActionListener {
        void onEdit(BasalProfile profile);
        void onDuplicate(BasalProfile profile);
        void onDelete(BasalProfile profile);
    }

    private final List<BasalProfile> items = new ArrayList<>();
    private final ActionListener actions;

    public ProfilesListAdapter(ActionListener actions) {
        this.actions = actions;
        setHasStableIds(true);
    }

    public void submitList(List<BasalProfile> newItems) {
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new Diff(items, newItems));
        items.clear();
        items.addAll(newItems);
        diff.dispatchUpdatesTo(this);
    }

    @Override public long getItemId(int position) { return items.get(position).getId(); }
    @Override public int getItemCount() { return items.size(); }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_profile_row, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        BasalProfile p = items.get(pos);
        h.tvId.setText(String.format(Locale.getDefault(), "ID: %d", p.getId()));
        h.tvName.setText(p.getName());
        h.tvTotalDose.setText(String.format(Locale.getDefault(),
                "Całkowita dawka: %s U", p.getTotalDailyDose().toPlainString()));

        h.btnEdit.setOnClickListener(v -> actions.onEdit(p));
        h.btnDuplicate.setOnClickListener(v -> actions.onDuplicate(p));
        h.btnDelete.setOnClickListener(v -> actions.onDelete(p));
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvId, tvName, tvTotalDose;
        MaterialButton btnEdit, btnDuplicate, btnDelete;

        VH(@NonNull View itemView) {
            super(itemView);
            tvId = itemView.findViewById(R.id.tvProfileId);
            tvName = itemView.findViewById(R.id.tvProfileName);
            tvTotalDose = itemView.findViewById(R.id.tvTotalDose);
            btnEdit = itemView.findViewById(R.id.btnEdit);
            btnDuplicate = itemView.findViewById(R.id.btnDuplicate);
            btnDelete = itemView.findViewById(R.id.btnDelete);
        }
    }

    static class Diff extends DiffUtil.Callback {
        private final List<BasalProfile> oldList, newList;
        Diff(List<BasalProfile> o, List<BasalProfile> n) { oldList = o; newList = n; }
        @Override public int getOldListSize() { return oldList.size(); }
        @Override public int getNewListSize() { return newList.size(); }
        @Override public boolean areItemsTheSame(int o, int n) {
            return oldList.get(o).getId() == newList.get(n).getId();
        }
        @Override public boolean areContentsTheSame(int o, int n) {
            BasalProfile a = oldList.get(o), b = newList.get(n);
            // Compare by name + total dose string (cheap)
            return a.getName().equals(b.getName())
                    && a.getTotalDailyDose().compareTo(b.getTotalDailyDose()) == 0;
        }
    }
}
