
package com.kukuchta.basaltracker.ui.editor;

import android.app.AlertDialog;
import android.content.Context;
import android.view.*;
import android.widget.NumberPicker;
import android.widget.TextView;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.kukuchta.basaltracker.R;

import java.util.Locale;

public class BasalHoursAdapter extends RecyclerView.Adapter<BasalHoursAdapter.VH> {

    public interface HourEditListener {
        void onChangeRate(int hour, double newRate);
    }

    private final Context context;
    private final double accuracy;
    private double maxDoseU = 100.0;
    private final HourEditListener listener;

    // 24 U/h values, index = hour 0..23
    private final double[] hours = new double[24];

    public BasalHoursAdapter(@NonNull Context ctx, double accuracy, @NonNull HourEditListener listener) {
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

    /** Replace all 24 values; call notifyDataSetChanged for simplicity. */
    public void submitHours(@NonNull double[] newHours) {
        if (newHours.length != 24) throw new IllegalArgumentException("must be 24 values");
        System.arraycopy(newHours, 0, hours, 0, 24);
        notifyDataSetChanged();
    }

    @Override public long getItemId(int position) { return position; }
    @Override public int getItemCount() { return 24; }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_hour, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int hour) {
        double rate = hours[hour];
        h.tvRange.setText(String.format(Locale.getDefault(), "%02d:00–%02d:00", hour, (hour + 1)));
        h.tvRate.setText(String.format(Locale.getDefault(), "Dawka: %.2f U/h", rate));
        h.btnPickRate.setOnClickListener(v -> showDosePickerDialog(hour, rate));
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvRange, tvRate;
        Button btnPickRate;
        VH(@NonNull View itemView) {
            super(itemView);
            tvRange = itemView.findViewById(R.id.tvRange);
            tvRate = itemView.findViewById(R.id.tvRate);
            btnPickRate = itemView.findViewById(R.id.btnPickRate);
        }
    }

    private void showDosePickerDialog(int hour, double currentRateU) {
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
                if (newRate < 0.0) return;
                listener.onChangeRate(hour, newRate);
            })
            .create();
        dlg.show();
    }

    private double unitsToRate(int units) { return units * accuracy; }
    private int toUnits(double rate) { return (int) Math.round(rate / accuracy); }
    private String[] buildDoseLabels(int maxUnits) {
        String[] labels = new String[maxUnits + 1];
        for (int u = 0; u <= maxUnits; u++) labels[u] = String.format(Locale.getDefault(), "%.2f", unitsToRate(u));
        return labels;
    }
}
