package com.kukuchta.basaltracker.ui.editor;

import android.app.AlertDialog;
import android.content.Context;
import android.view.*;
import android.widget.NumberPicker;
import android.widget.TextView;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.kukuchta.basaltracker.R;
import com.kukuchta.basaltracker.domain.BasalSegment;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class BasalSegmentsAdapter extends RecyclerView.Adapter<BasalSegmentsAdapter.VH> {

    public interface SegmentEditListener {
        void onChangeEnd(int segmentStartMinutes, int newEndMinutes);
        void onChangeRate(int segmentStartMinutes, double newRate);
    }

    private final Context context;
    private final double accuracy;
    private double maxDoseU = 100.0;
    private final SegmentEditListener listener;
    private final List<BasalSegment> segments = new ArrayList<>();

    public BasalSegmentsAdapter(@NonNull Context ctx, double accuracy, @NonNull SegmentEditListener listener) {
        if (accuracy <= 0.0) throw new IllegalArgumentException("accuracy > 0 required");
        this.context = ctx;
        this.accuracy = accuracy;
        this.listener = listener;
        setHasStableIds(true);
    }

    public void setMaxDoseU(double maxDoseU) {
        if (maxDoseU <= 0.0) throw new IllegalArgumentException("maxDoseU > 0 required");
        this.maxDoseU = maxDoseU;
    }

    public void submitList(@NonNull List<BasalSegment> newSegments) {
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new Diff(segments, newSegments));
        segments.clear();
        segments.addAll(newSegments);
        diff.dispatchUpdatesTo(this);
    }

    @Override public long getItemId(int position) { return segments.get(position).getStartMinutes(); }
    @Override public int getItemCount() { return segments.size(); }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_segment, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        BasalSegment seg = segments.get(pos);
        int start = seg.getStartMinutes();
        int end = (pos + 1 < segments.size()) ? segments.get(pos + 1).getStartMinutes() : 1440;
        double rate = seg.getRateUnits() * accuracy;

        h.tvStart.setText(String.format(Locale.getDefault(), "Start: %s", fmtTime(start)));
        h.tvEnd.setText(String.format(Locale.getDefault(), "Koniec: %s", fmtTime(end)));
        h.tvRate.setText(String.format(Locale.getDefault(), "Dawka: %.2f U/h", rate));

        h.btnPickEnd.setOnClickListener(view -> showEndHourPickerDialog(start, end));
        h.btnPickRate.setOnClickListener(view -> showDosePickerDialog(start, rate));
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvStart, tvEnd, tvRate;
        Button btnPickEnd, btnPickRate;
        VH(@NonNull View itemView) {
            super(itemView);
            tvStart = itemView.findViewById(R.id.tvStart);
            tvEnd = itemView.findViewById(R.id.tvEnd);
            tvRate = itemView.findViewById(R.id.tvRate);
            btnPickEnd = itemView.findViewById(R.id.btnPickEnd);
            btnPickRate = itemView.findViewById(R.id.btnPickRate);
        }
    }

    // === Hour-only end picker ===
    private void showEndHourPickerDialog(int startMinutes, int currentEndMinutes) {
        View content = LayoutInflater.from(context).inflate(R.layout.dialog_end_hour_picker, null);
        NumberPicker npHour = content.findViewById(R.id.npHour);

        int startHour = startMinutes / 60;
        // earliest end is startHour+1
        int minHour = startHour + 1;
        int maxHour = 24;

        npHour.setMinValue(minHour);
        npHour.setMaxValue(maxHour);
        npHour.setWrapSelectorWheel(false);

        int currentEndHour = Math.min(currentEndMinutes, 1440) / 60;
        if (currentEndHour < minHour || currentEndHour > maxHour) currentEndHour = minHour;
        npHour.setValue(currentEndHour);

        AlertDialog dlg = new AlertDialog.Builder(context)
                .setTitle("Koniec segmentu (godzina)")
                .setView(content)
                .setNegativeButton("Anuluj", (d, w) -> d.dismiss())
                .setPositiveButton("Zastosuj", (d, w) -> {
                    int endHour = npHour.getValue();
                    int newEnd = endHour * 60;

                    if (newEnd <= startMinutes) {
                        toast("Koniec segmentu musi być późniejszy niż start.");
                        return;
                    }
                    if (newEnd > 1440) {
                        toast("Koniec segmentu nie może przekraczać 24:00.");
                        return;
                    }
                    listener.onChangeEnd(startMinutes, newEnd);
                })
                .create();
        dlg.show();
    }

    // === Dose picker ===
    private void showDosePickerDialog(int segmentStartMinutes, double currentRateU) {
        View content = LayoutInflater.from(context).inflate(R.layout.dialog_dose_picker, null);
        NumberPicker npDose = content.findViewById(R.id.npDose);

        int currentUnits = toUnits(currentRateU);
        int maxUnits = toUnits(maxDoseU);

        String[] labels = buildDoseLabels(maxUnits);
        npDose.setMinValue(0);
        npDose.setMaxValue(maxUnits);
        npDose.setDisplayedValues(labels);
        npDose.setWrapSelectorWheel(false);
        npDose.setValue(currentUnits);

        AlertDialog dlg = new AlertDialog.Builder(context)
                .setTitle("Dawka (U/h)")
                .setView(content)
                .setNegativeButton("Anuluj", (d, w) -> d.dismiss())
                .setPositiveButton("Zastosuj", (d, w) -> {
                    int units = npDose.getValue();
                    double newRate = unitsToRate(units);
                    if (newRate < 0.0) {
                        toast("Dawka nie może być ujemna.");
                        return;
                    }
                    listener.onChangeRate(segmentStartMinutes, newRate);
                })
                .create();
        dlg.show();
    }

    // === Utils & Diff ===
    private void toast(String msg) { android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show(); }

    private String fmtTime(int minutes) {
        int h = minutes / 60;
        int m = minutes % 60;
        return String.format(Locale.getDefault(), "%02d:%02d", h, m);
    }

    private double unitsToRate(int units) { return units * accuracy; }
    private int toUnits(double rate) {
        if (rate < 0.0) throw new IllegalArgumentException("Dawka nie może być ujemna.");
        return (int) Math.round(rate / accuracy);
    }
    private String[] buildDoseLabels(int maxUnits) {
        String[] labels = new String[maxUnits + 1];
        for (int u = 0; u <= maxUnits; u++) labels[u] = String.format(Locale.getDefault(), "%.2f", unitsToRate(u));
        return labels;
    }

    static class Diff extends DiffUtil.Callback {
        private final List<BasalSegment> oldList, newList;
        Diff(List<BasalSegment> o, List<BasalSegment> n) { oldList=o; newList=n; }
        @Override public int getOldListSize() { return oldList.size(); }
        @Override public int getNewListSize() { return newList.size(); }
        @Override public boolean areItemsTheSame(int o, int n) { return oldList.get(o).getStartMinutes() == newList.get(n).getStartMinutes(); }
        @Override public boolean areContentsTheSame(int o, int n) {
            BasalSegment a = oldList.get(o), b = newList.get(n);
            return a.getRateUnits() == b.getRateUnits();
        }
    }
}
