package com.kukuchta.basaltracker.ui.editor;

import android.graphics.Color;
import android.os.Bundle;
import android.view.*;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.kukuchta.basaltracker.R;
import com.kukuchta.basaltracker.domain.BasalProfile;
import com.kukuchta.basaltracker.viewmodel.ProfileViewModel;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.*;
import com.github.mikephil.charting.data.*;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import java.util.ArrayList;
import java.util.Locale;

public class ProfileCombinedEditorFragment extends Fragment {

    private static final String ARG_PROFILE_ID = "profileId";

    public static ProfileCombinedEditorFragment newInstance(long profileId) {
        ProfileCombinedEditorFragment f = new ProfileCombinedEditorFragment();
        Bundle b = new Bundle();
        b.putLong(ARG_PROFILE_ID, profileId);
        f.setArguments(b);
        return f;
    }

    private ProfileViewModel viewModel;

    // Shared
    private TextView tvProfileName, tvAccuracy, tvTotalDailyDose, tvError;
    private MaterialButton btnSaveProfile, btnDiscardChanges;

    // Toggle & panels
    private EditMode currentMode = EditMode.HOURLY;
    private MaterialButton btnModeHourly, btnModeCircadian, btnModeSegments;
    private View panelHourly, panelSegments;

    // Hourly
    private LineChart chart;
    private TextView tvSelectedHour, tvHourDoseInfo;
    private MaterialButton btnLeft, btnRight, btnMinus, btnPlus;
    private int selectedHour = 0;

    // Segments
    private androidx.recyclerview.widget.RecyclerView rvSegments;
    private BasalSegmentsAdapter segmentsAdapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_profile_combined_editor, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);

        viewModel = new ViewModelProvider(requireActivity()).get(ProfileViewModel.class);

        // Shared
        tvProfileName = v.findViewById(R.id.tvProfileName);
        tvAccuracy = v.findViewById(R.id.tvAccuracy);
        tvTotalDailyDose = v.findViewById(R.id.tvTotalDailyDose);
        tvError = v.findViewById(R.id.tvError);
        btnSaveProfile = v.findViewById(R.id.btnSaveProfile);
        btnDiscardChanges = v.findViewById(R.id.btnDiscardChanges);

        // Toggle & panels
        btnModeHourly = v.findViewById(R.id.btnModeHourly);
        btnModeCircadian = v.findViewById(R.id.btnModeCircadian);
        btnModeSegments = v.findViewById(R.id.btnModeSegments);

        panelHourly = v.findViewById(R.id.panelHourly);
        panelSegments = v.findViewById(R.id.panelSegments);

        // Hourly
        chart = v.findViewById(R.id.chartProfile);
        tvSelectedHour = v.findViewById(R.id.tvSelectedHour);
        tvHourDoseInfo = v.findViewById(R.id.tvHourDoseInfo);
        btnLeft = v.findViewById(R.id.btnLeft);
        btnRight = v.findViewById(R.id.btnRight);
        btnMinus = v.findViewById(R.id.btnMinus);
        btnPlus = v.findViewById(R.id.btnPlus);

        // Segments
        rvSegments = v.findViewById(R.id.rvSegments);
        rvSegments.setLayoutManager(new LinearLayoutManager(requireContext()));

        setupChart();
        setupModeButtons();
        setupActions();

        Bundle args = getArguments();
        if (args != null && args.containsKey(ARG_PROFILE_ID)) {
            long id = args.getLong(ARG_PROFILE_ID, 0);
            if (id > 0) viewModel.loadProfile(id);
        }

        viewModel.getCurrentProfile().observe(getViewLifecycleOwner(), profile -> {
            if (profile != null) {
                tvError.setVisibility(View.GONE);
                bindShared(profile);
                renderChart(profile);
                updateHourInfo(profile);
                highlightSelectedHour();
                bindSegments(profile);
            } else {
                tvError.setText("Brak załadowanego profilu.");
                tvError.setVisibility(View.VISIBLE);
            }
        });

        switchMode(EditMode.HOURLY);
    }

    private void setupModeButtons() {
        btnModeHourly.setOnClickListener(v -> switchMode(EditMode.HOURLY));
        btnModeCircadian.setOnClickListener(v -> switchMode(EditMode.CIRCADIAN));
        btnModeSegments.setOnClickListener(v -> switchMode(EditMode.SEGMENTS));
    }

    private void switchMode(@NonNull EditMode mode) {
        if (currentMode == mode) return;

        currentMode = mode;

        updateModeButtons();
        updatePanels();
        updateChartInteractivity();
        updateControls();
        updateModeHeader();
    }

    private void updateModeButtons() {
        setModeButtonSelected(btnModeHourly, currentMode == EditMode.HOURLY);
        setModeButtonSelected(btnModeCircadian, currentMode == EditMode.CIRCADIAN);
        setModeButtonSelected(btnModeSegments, currentMode == EditMode.SEGMENTS);
    }

    private void setModeButtonSelected(MaterialButton button, boolean selected) {
        button.setChecked(selected); // if using checkable buttons
        button.setEnabled(!selected); // optional: prevents re-click
    }

    private void updatePanels() {
        panelHourly.setVisibility(
                currentMode == EditMode.HOURLY || currentMode == EditMode.CIRCADIAN
                        ? View.VISIBLE
                        : View.GONE
        );

        panelSegments.setVisibility(
                currentMode == EditMode.SEGMENTS
                        ? View.VISIBLE
                        : View.GONE
        );
    }

    private void updateChartInteractivity() {
        boolean interactive = currentMode == EditMode.HOURLY;

        chart.setTouchEnabled(interactive);
        chart.setDragEnabled(interactive);
        chart.setHighlightPerTapEnabled(interactive);
    }

    private void updateControls() {
        boolean hourly = currentMode == EditMode.HOURLY;

        btnPlus.setEnabled(hourly);
        btnMinus.setEnabled(hourly);
        btnLeft.setEnabled(hourly);
        btnRight.setEnabled(hourly);

        btnApplyCircadian.setVisibility(
                currentMode == EditMode.CIRCADIAN ? View.VISIBLE : View.GONE
        );
    }

    private void updateModeHeader() {
        switch (currentMode) {
            case HOURLY:
                tvEditModeHeader.setText("Edycja godzinowa");
                tvEditModeHint.setText("Zmieniasz pojedyncze godziny");
                break;

            case CIRCADIAN:
                tvEditModeHeader.setText("Kształt dobowy");
                tvEditModeHint.setText("Dopasowujesz cały profil");
                break;

            case SEGMENTS:
                tvEditModeHeader.setText("Segmenty");
                tvEditModeHint.setText("Edytujesz bloki czasowe");
                break;
        }
    }

    private void setupActions() {
        btnSaveProfile.setOnClickListener(v -> {
            try {
                viewModel.saveCurrentProfile();
                Toast.makeText(requireContext(), "Profil zapisany.", Toast.LENGTH_SHORT).show();
            } catch (IllegalArgumentException | IllegalStateException ex) {
                showError(ex.getMessage());
            }
        });
        btnDiscardChanges.setOnClickListener(v -> {
            // Strategy depends on your persistence—e.g., reload by id if known
            Toast.makeText(requireContext(), "Zmiany odrzucone.", Toast.LENGTH_SHORT).show();
        });

        btnLeft.setOnClickListener(x -> {
            if (selectedHour > 0) {
                selectedHour--;
                updateSelectedHourText();
                highlightSelectedHour();
                BasalProfile p = viewModel.getCurrentProfile().getValue();
                if (p != null) updateHourInfo(p);
            }
        });

        btnRight.setOnClickListener(x -> {
            if (selectedHour < 23) {
                selectedHour++;
                updateSelectedHourText();
                highlightSelectedHour();
                BasalProfile p = viewModel.getCurrentProfile().getValue();
                if (p != null) updateHourInfo(p);
            }
        });

        btnPlus.setOnClickListener(x -> {
            BasalProfile p = viewModel.getCurrentProfile().getValue();
            if (p == null) { showError("Nie można zwiększyć dawki – brak profilu."); return; }
            try {
                viewModel.adjustRateForHour(selectedHour, true);
            } catch (IllegalArgumentException | IllegalStateException ex) {
                showError(ex.getMessage());
            }
        });

        btnMinus.setOnClickListener(x -> {
            BasalProfile p = viewModel.getCurrentProfile().getValue();
            if (p == null) { showError("Nie można zmniejszyć dawki – brak profilu."); return; }
            try {
                viewModel.adjustRateForHour(selectedHour, false);
            } catch (IllegalArgumentException | IllegalStateException ex) {
                showError(ex.getMessage());
            }
        });

        updateSelectedHourText();
    }

    private void bindShared(@NonNull BasalProfile profile) {
        tvProfileName.setText(profile.getName());
        tvAccuracy.setText(String.format(Locale.getDefault(), "Accuracy: %.2f U/h", profile.getAccuracy()));
        tvTotalDailyDose.setText("Całkowita dawka dobowa: " + profile.getTotalDailyDose().toPlainString() + " U");
    }

    private void bindSegments(@NonNull BasalProfile profile) {
        if (segmentsAdapter == null) {
            segmentsAdapter = new BasalSegmentsAdapter(
                    requireContext(),
                    profile.getAccuracy(),
                    new BasalSegmentsAdapter.SegmentEditListener() {
                        @Override
                        public void onChangeEnd(int segmentStartMinutes, int newEndMinutes) {
                            viewModel.updateSegmentEnd(segmentStartMinutes, newEndMinutes);
                        }
                        @Override
                        public void onChangeRate(int segmentStartMinutes, double newRate) {
                            viewModel.updateSegmentRate(segmentStartMinutes, newRate);
                        }
                    }
            );
            rvSegments.setAdapter(segmentsAdapter);
        }
        segmentsAdapter.submitList(profile.getSegments());
    }

    // === Hourly chart ===

    private void setupChart() {
        // ---- Empty state ----
        chart.setNoDataText("Brak danych profilu.");
        chart.setNoDataTextColor(Color.GRAY);

        // ---- Description ----
        Description desc = new Description();
        desc.setText("Dawki bazalne (U/h)");
        desc.setTextSize(12f);
        chart.setDescription(desc);

        // ---- General chart behavior ----
        chart.setDrawGridBackground(false);
        chart.setDrawBorders(false);

        chart.setPinchZoom(true);
        chart.setDoubleTapToZoomEnabled(true);
        chart.setScaleYEnabled(false); // UX: accidental Y zoom is confusing
        chart.setDragEnabled(true);

        chart.setExtraTopOffset(8f);
        chart.setExtraBottomOffset(8f);
        chart.setExtraLeftOffset(12f);
        chart.setExtraRightOffset(12f);

        // ---- Legend ----
        // Disabled for now; enable later for dual-profile preview if needed
        chart.getLegend().setEnabled(false);

        // ---- Right axis (not needed) ----
        chart.getAxisRight().setEnabled(false);

        // ---- Left axis (dose axis) ----
        YAxis leftAxis = chart.getAxisLeft();
        leftAxis.setAxisMinimum(0f); // Basal dose never negative
        leftAxis.setDrawGridLines(true);
        leftAxis.setGridLineWidth(0.5f);
        leftAxis.setTextSize(11f);
        leftAxis.setGranularityEnabled(true);
        leftAxis.setGranularity(0.1f); // UX-friendly default; actual snapping handled elsewhere

        // ---- X axis (time) ----
        XAxis xAxis = chart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);

        // 0..24 hours
        xAxis.setAxisMinimum(0f);
        xAxis.setAxisMaximum(24f);

        // Hour resolution
        xAxis.setGranularity(1f);
        xAxis.setGranularityEnabled(true);

        // Fewer labels = calmer chart
        xAxis.setLabelCount(9, true); // e.g. 0,3,6,9,12,15,18,21,24
        xAxis.setTextSize(11f);

        // Grid lines only vertical (time guidance)
        xAxis.setDrawGridLines(true);
        xAxis.setGridLineWidth(0.5f);

        // Optional: format as hours (00, 03, …)
        // xAxis.setValueFormatter(new HourAxisFormatter());

        // ---- Interaction polish ----
        chart.setHighlightPerTapEnabled(false);
        chart.setHighlightPerDragEnabled(false);

        // Smooth redraws for live preview
        chart.animateX(0);
    }

    private void renderChart(@NonNull BasalProfile profile) {
        double[] hours = profile.toHourArray();

        // 24 hours + explicit endpoint at 24:00
        ArrayList<Entry> entries = new ArrayList<>(hours.length + 1);

        for (int i = 0; i < hours.length; i++) {
            entries.add(new Entry(i, (float) hours[i]));
        }

        // Important: extend last value to 24:00
        entries.add(new Entry(24f, (float) hours[hours.length - 1]));

        LineDataSet ds = new LineDataSet(entries, null);

        // ---- Stepped basal semantics ----
        ds.setMode(LineDataSet.Mode.STEPPED);

        // ---- Visual clarity ----
        ds.setLineWidth(2f);
        ds.setColor(0xFF2196F3);

        ds.setDrawCircles(false);   // Correct way to disable circles
        ds.setDrawValues(false);    // No numeric clutter

        // ---- Interaction ----
        ds.setHighlightEnabled(false);

        chart.setData(new LineData(ds));
        chart.invalidate();
    }

    private void highlightSelectedHour() {
        XAxis xAxis = chart.getXAxis();
        xAxis.removeAllLimitLines();

        // Center of the selected hour block
        float centerX = selectedHour + 0.5f;

        LimitLine hourBand = new LimitLine(centerX, "");

        // Accent color with low alpha
        hourBand.setLineColor(0x33FFA000); // ~20% alpha amber
        hourBand.setLineWidth(20f);        // Wide enough to read as a band

        // No label, no dashes
        hourBand.setTextSize(0f);
        hourBand.enableDashedLine(0f, 0f, 0f);

        // Draw behind data for proper layering
        // hourBand.setDrawBehindData(true);

        xAxis.addLimitLine(hourBand);
        chart.invalidate();
    }

    private void updateSelectedHourText() {
        tvSelectedHour.setText(String.format(Locale.getDefault(),
                "Godzina: %02d:00–%02d:00", selectedHour, (selectedHour + 1)));
    }

    private void updateHourInfo(@NonNull BasalProfile profile) {
        double rate = profile.getBasalRate(selectedHour * 60);
        tvHourDoseInfo.setText(String.format(Locale.getDefault(), "Dawka: %.2f U/h", rate));
    }

    private void showError(String msg) {
        tvError.setText(msg);
        tvError.setVisibility(View.VISIBLE);
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
    }
}

enum EditMode {
    HOURLY,
    CIRCADIAN,
    SEGMENTS
}