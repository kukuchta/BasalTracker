package com.kukuchta.basaltracker.ui.editor;

import android.app.AlertDialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.NumberPicker;
import android.widget.TextView;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.kukuchta.basaltracker.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * RecyclerView adapter for compressed UI segments (runs of equal hourly units).
 * Each row supports a single atomic edit: choose a rate (U/h) and a new end hour.
 */
public class UiSegmentsAdapter extends RecyclerView.Adapter<UiSegmentsAdapter.VH> {

    public interface UiSegmentEditListener {
        void onApplyEdit(UiSegment segment, double newRateUh, int newEndHourExclusive);
    }

    private final Context context;
    private final UiSegmentEditListener listener;

    private final List<UiSegment> segments = new ArrayList<>();
    private double accuracy = 0.1;  // default; should be set from domain
    private double maxDoseU = 100.0;

    public UiSegmentsAdapter(@NonNull Context ctx, @NonNull UiSegmentEditListener listener) {
        this.context = ctx;
        this.listener = listener;
        setHasStableIds(true);
    }

    public void setAccuracy(double accuracy) {
        if (accuracy <= 0.0) throw new IllegalArgumentException("accuracy > 0 required");
        this.accuracy = accuracy;
    }

    public void setMaxDoseU(double maxDoseU) {
        if (maxDoseU <= 0.0) throw new IllegalArgumentException("maxDoseU > 0 required");
        this.maxDoseU = maxDoseU;
    }

    public void submitList(@NonNull List<UiSegment> newSegments) {
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new Diff(segments, newSegments));
        segments.clear();
        segments.addAll(newSegments);
        diff.dispatchUpdatesTo(this);
    }

    @Override public long getItemId(int position) {
        // A stable-ish key: startHour*100 + endHour; ok because there are <=24 runs.
        UiSegment s = segments.get(position);
        return s.startHour * 100L + s.endHourExclusive;
    }

    @Override public int getItemCount() { return segments.size(); }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_ui_segment, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        UiSegment seg = segments.get(pos);
        h.tvRange.setText(String.format(Locale.getDefault(), "%02d:00–%02d:00",
                seg.startHour, seg.endHourExclusive));
        h.tvRate.setText(String.format(Locale.getDefault(), "Dawka: %.2f U/h", seg.rateUh));
        h.btnEdit.setOnClickListener(v -> showEditDialog(seg));
    }

    private void showEditDialog(@NonNull UiSegment seg) {
        View content = LayoutInflater.from(context).inflate(R.layout.dialog_segment_edit, null);
        NumberPicker npDose = content.findViewById(R.id.npDose);
        NumberPicker npEnd = content.findViewById(R.id.npEndHour);

        // Dose picker configured by accuracy
        int currentUnits = toUnits(seg.rateUh);
        int maxUnits = toUnits(maxDoseU);
        String[] labels = buildDoseLabels(maxUnits);
        npDose.setMinValue(0);
        npDose.setMaxValue(maxUnits);
        npDose.setDisplayedValues(labels);
        npDose.setWrapSelectorWheel(false);
        npDose.setValue(Math.max(0, Math.min(currentUnits, maxUnits)));

        // End-hour picker: (start+1) .. 24
        int minHour = seg.startHour + 1;
        npEnd.setMinValue(minHour);
        npEnd.setMaxValue(24);
        npEnd.setWrapSelectorWheel(false);
        npEnd.setValue(seg.endHourExclusive);

        AlertDialog dlg = new AlertDialog.Builder(context)
                .setTitle("Edytuj segment")
                .setView(content)
                .setNegativeButton("Anuluj", (d, w) -> d.dismiss())
                .setPositiveButton("Zastosuj", (d, w) -> {
                    int units = npDose.getValue();
                    double newRateUh = unitsToRate(units);
                    int newEnd = npEnd.getValue(); // exclusive
                    if (newEnd <= seg.startHour || newEnd > 24) return;
                    listener.onApplyEdit(seg, newRateUh, newEnd);
                })
                .create();
        dlg.show();
    }

    private double unitsToRate(int units) { return units * accuracy; }
    private int toUnits(double rateUh) { return (int) Math.round(rateUh / accuracy); }
    private String[] buildDoseLabels(int maxUnits) {
        String[] labels = new String[maxUnits + 1];
        for (int u = 0; u <= maxUnits; u++) labels[u] = String.format(Locale.getDefault(), "%.2f", unitsToRate(u));
        return labels;
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvRange, tvRate;
        Button btnEdit;
        VH(@NonNull View itemView) {
            super(itemView);
            tvRange = itemView.findViewById(R.id.tvRange);
            tvRate = itemView.findViewById(R.id.tvRate);
            btnEdit = itemView.findViewById(R.id.btnEdit);
        }
    }

    static class Diff extends DiffUtil.Callback {
        private final List<UiSegment> oldList, newList;
        Diff(List<UiSegment> o, List<UiSegment> n) { oldList = o; newList = n; }
        @Override public int getOldListSize() { return oldList.size(); }
        @Override public int getNewListSize() { return newList.size(); }
        @Override public boolean areItemsTheSame(int o, int n) {
            UiSegment a = oldList.get(o), b = newList.get(n);
            return a.startHour == b.startHour && a.endHourExclusive == b.endHourExclusive;
        }
        @Override public boolean areContentsTheSame(int o, int n) {
            UiSegment a = oldList.get(o), b = newList.get(n);
            return a.units == b.units;
        }
    }
}
