package com.kukuchta.basaltracker.ui.editor;

import android.app.AlertDialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
    private final double accuracy; // U/h
    private double maxDoseU = 100.0; // configurable
    private final SegmentEditListener listener;
    private final List<BasalSegment> segments = new ArrayList<>();

    public BasalSegmentsAdapter(@NonNull Context ctx, double accuracy, @NonNull SegmentEditListener listener) {
        this.context = ctx;
        if (accuracy <= 0.0) throw new IllegalArgumentException("accuracy musi być > 0");
        this.accuracy = accuracy;
        this.listener = listener;
        setHasStableIds(true);
    }

    public void setMaxDoseU(double maxDoseU) {
        if (maxDoseU <= 0.0) throw new IllegalArgumentException("maxDoseU musi być > 0");
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

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_segment, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        BasalSegment seg = segments.get(pos);
        int start = seg.getStartMinutes();
        int end = (pos + 1 < segments.size()) ? segments.get(pos + 1).getStartMinutes() : 1440;
        double rate = unitsToRate(seg.getRateUnits());

        h.tvStart.setText(String.format(Locale.getDefault(), "Start: %s", fmtTime(start)));
        h.tvEnd.setText(String.format(Locale.getDefault(), "Koniec: %s", fmtTime(end)));
        h.tvRate.setText(String.format(Locale.getDefault(), "Dawka: %.2f U/h", rate));

        h.btnPickEnd.setOnClickListener(view -> showEndPickerDialog(start, end));
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

    private void showEndPickerDialog(int startMinutes, int currentEndMinutes) {
        LayoutInflater inflater = LayoutInflater.from(context);
        View content = inflater.inflate(R.layout.dialog_end_time_picker, null);
        NumberPicker npHour = content.findViewById(R.id.npHour);
        NumberPicker npMinute = content.findViewById(R.id.npMinute);

        final int startHour = startMinutes / 60;
        final int startMinute = startMinutes % 60;

        final int earliestEnd = (startMinute == 0)
                ? startHour * 60 + 30
                : (startHour + 1) * 60;

        final int earliestHour = earliestEnd / 60;
        final int earliestMin = earliestEnd % 60;

        npHour.setMinValue(earliestHour);
        npHour.setMaxValue(24);
        npHour.setWrapSelectorWheel(false);

        applyMinuteConstraints(npMinute, npHour.getValue(), earliestHour, earliestMin);

        int proposedEnd = Math.min(currentEndMinutes, 1440);
        if (proposedEnd < earliestEnd || proposedEnd % 30 != 0) {
            proposedEnd = earliestEnd;
        }
        int initHour = proposedEnd / 60;
        int initMinute = proposedEnd % 60;

        npHour.setValue(initHour);
        applyMinuteConstraints(npMinute, initHour, earliestHour, earliestMin);
        if (npMinute.getMaxValue() == 0) {
            String label = npMinute.getDisplayedValues()[0];
            int enforcedMinute = label.equals("30") ? 30 : 0;
            npMinute.setValue(0);
            initMinute = enforcedMinute;
        } else {
            npMinute.setValue(initMinute == 30 ? 1 : 0);
        }

        npHour.setOnValueChangedListener((picker, oldVal, newVal) -> {
            applyMinuteConstraints(npMinute, newVal, earliestHour, earliestMin);
        });

        AlertDialog dlg = new AlertDialog.Builder(context)
                .setTitle("Koniec segmentu")
                .setView(content)
                .setNegativeButton("Anuluj", (d, w) -> d.dismiss())
                .setPositiveButton("Zastosuj", (d, w) -> {
                    int selHour = npHour.getValue();
                    int selMinute;

                    if (npMinute.getMaxValue() == 0) {
                        String label = npMinute.getDisplayedValues()[npMinute.getValue()];
                        selMinute = label.equals("30") ? 30 : 0;
                    } else {
                        selMinute = (npMinute.getValue() == 0) ? 0 : 30;
                    }

                    int newEnd = selHour * 60 + selMinute;

                    if (newEnd <= startMinutes) {
                        toast("Koniec segmentu musi być późniejszy niż start.");
                        return;
                    }
                    if (newEnd > 1440) {
                        toast("Koniec segmentu nie może przekraczać 24:00.");
                        return;
                    }
                    if (newEnd % 30 != 0) {
                        toast("Koniec musi leżeć na siatce 30 minut.");
                        return;
                    }
                    if (selHour == earliestHour && selMinute < earliestMin) {
                        toast(String.format(Locale.getDefault(),
                                "Najwcześniejszy koniec to %s.", fmtTime(earliestEnd)));
                        return;
                    }

                    try {
                        listener.onChangeEnd(startMinutes, newEnd);
                    } catch (IllegalArgumentException | IllegalStateException ex) {
                        toast(ex.getMessage());
                    }
                })
                .create();

        dlg.show();
    }

    private void applyMinuteConstraints(NumberPicker npMinute,
                                        int selectedHour,
                                        int earliestHour,
                                        int earliestMin) {
        npMinute.setDisplayedValues(null);
        if (selectedHour == 24) {
            npMinute.setMinValue(0);
            npMinute.setMaxValue(0);
            npMinute.setDisplayedValues(new String[]{"00"});
            npMinute.setValue(0);
            npMinute.setEnabled(false);
        } else if (selectedHour == earliestHour && earliestMin == 30) {
            npMinute.setMinValue(0);
            npMinute.setMaxValue(0);
            npMinute.setDisplayedValues(new String[]{"30"});
            npMinute.setValue(0);
            npMinute.setEnabled(false);
        } else {
            npMinute.setMinValue(0);
            npMinute.setMaxValue(1);
            npMinute.setDisplayedValues(new String[]{"00", "30"});
            npMinute.setEnabled(true);
            if (npMinute.getValue() > 1) npMinute.setValue(0);
        }
    }

    private void showDosePickerDialog(int segmentStartMinutes, double currentRateU) {
        LayoutInflater inflater = LayoutInflater.from(context);
        View content = inflater.inflate(R.layout.dialog_dose_picker, null);
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
                    try {
                        listener.onChangeRate(segmentStartMinutes, newRate);
                    } catch (IllegalArgumentException | IllegalStateException ex) {
                        toast(ex.getMessage());
                    }
                })
                .create();
        dlg.show();
    }

    private void toast(String msg) { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show(); }

    private String fmtTime(int minutes) {
        int h = minutes / 60;
        int m = minutes % 60;
        return String.format(Locale.getDefault(), "%02d:%02d", h, m);
    }

    private double unitsToRate(int units) { return units * accuracy; }
    private int toUnits(double rate) {
        if (rate < 0.0) throw new IllegalArgumentException("Dawka nie może być ujemna.");
        long u = Math.round(rate / accuracy);
        return (int) u;
    }

    private String[] buildDoseLabels(int maxUnits) {
        String[] labels = new String[maxUnits + 1];
        for (int u = 0; u <= maxUnits; u++) {
            double r = unitsToRate(u);
            labels[u] = String.format(Locale.getDefault(), "%.2f", r);
        }
        return labels;
    }

    static class Diff extends DiffUtil.Callback {
        private final List<BasalSegment> oldList, newList;
        Diff(List<BasalSegment> o, List<BasalSegment> n) { oldList = o; newList = n; }
        @Override public int getOldListSize() { return oldList.size(); }
        @Override public int getNewListSize() { return newList.size(); }
        @Override public boolean areItemsTheSame(int o, int n) {
            return oldList.get(o).getStartMinutes() == newList.get(n).getStartMinutes();
        }
        @Override public boolean areContentsTheSame(int o, int n) {
            BasalSegment a = oldList.get(o), b = newList.get(n);
            return a.getRateUnits() == b.getRateUnits();
        }
    }
}
