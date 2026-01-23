package com.kukuchta.basaltracker.ui.editor;

import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.Description;
import com.github.mikephil.charting.components.LimitLine;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.google.android.material.button.MaterialButton;
import com.kukuchta.basaltracker.R;
import com.kukuchta.basaltracker.domain.BasalProfile;
import com.kukuchta.basaltracker.viewmodel.ProfileViewModel;

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

    // Compressed list (formerly "segments")
    private androidx.recyclerview.widget.RecyclerView rvSegments;
    private UiSegmentsAdapter uiSegmentsAdapter;

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

        // Compressed list
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
            } else {
                tvError.setText("Brak załadowanego profilu.");
                tvError.setVisibility(View.VISIBLE);
            }
        });

        // Observe compressed UI segments and bind the list
        viewModel.getUiSegments().observe(getViewLifecycleOwner(), segs -> {
            if (uiSegmentsAdapter == null) {
                uiSegmentsAdapter = new UiSegmentsAdapter(
                        requireContext(),
                        (segment, newRateUh, newEndHourExclusive) -> {
                            try {
                                viewModel.applySegmentEdit(segment, newRateUh, newEndHourExclusive);
                            } catch (IllegalArgumentException | IllegalStateException ex) {
                                showError(ex.getMessage());
                            }
                        }
                );
                // Provide accuracy for dose picker labels
                BasalProfile p = viewModel.getCurrentProfile().getValue();
                if (p != null) uiSegmentsAdapter.setAccuracy(p.getAccuracy());
                rvSegments.setAdapter(uiSegmentsAdapter);
            }
            uiSegmentsAdapter.submitList(segs);
        });

        switchMode(EditMode.HOURLY);
    }

    private void setupModeButtons() {
        btnModeHourly.setOnClickListener(v -> switchMode(EditMode.HOURLY));
        btnModeCircadian.setOnClickListener(v -> switchMode(EditMode.CIRCADIAN));
        // Keep the ID/name but treat it as "List" mode (compressed segments)
        btnModeSegments.setOnClickListener(v -> switchMode(EditMode.LIST));
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
        setModeButtonSelected(btnModeSegments, currentMode == EditMode.LIST);
    }

    private void setModeButtonSelected(MaterialButton button, boolean selected) {
        button.setChecked(selected);
        button.setEnabled(!selected);
    }

    private void updatePanels() {
        panelHourly.setVisibility(
                (currentMode == EditMode.HOURLY || currentMode == EditMode.CIRCADIAN)
                        ? View.VISIBLE : View.GONE
        );
        panelSegments.setVisibility(currentMode == EditMode.LIST ? View.VISIBLE : View.GONE);
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
        // If you have a "apply circadian" button, toggle its visibility here
        // btnApplyCircadian.setVisibility(currentMode == EditMode.CIRCADIAN ? View.VISIBLE : View.GONE);
    }

    private void updateModeHeader() {
        TextView tvEditModeHeader = getView().findViewById(R.id.tvEditModeHeader);
        TextView tvEditModeHint = getView().findViewById(R.id.tvEditModeHint);
        if (tvEditModeHeader == null || tvEditModeHint == null) return;
        switch (currentMode) {
            case HOURLY:
                tvEditModeHeader.setText("Edycja godzinowa");
                tvEditModeHint.setText("Zmieniasz pojedyncze godziny");
                break;
            case CIRCADIAN:
                tvEditModeHeader.setText("Kształt dobowy");
                tvEditModeHint.setText("Dopasowujesz cały profil");
                break;
            case LIST:
                tvEditModeHeader.setText("Segmenty (skompresowane)");
                tvEditModeHint.setText("Edytujesz dawkę i koniec bloku");
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

        btnDiscardChanges.setOnClickListener(v ->
                Toast.makeText(requireContext(), "Zmiany odrzucone.", Toast.LENGTH_SHORT).show());

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

    // --- Shared binders ---

    private void bindShared(@NonNull BasalProfile profile) {
        tvProfileName.setText(profile.getName());
        tvAccuracy.setText(String.format(Locale.getDefault(), "Accuracy: %.2f U/h", profile.getAccuracy()));
        tvTotalDailyDose.setText("Całkowita dawka dobowa: " + profile.getTotalDailyDose().toPlainString() + " U");
    }

    // --- Chart ---

    private void setupChart() {
        chart.setNoDataText("Brak danych profilu.");
        chart.setNoDataTextColor(Color.GRAY);

        Description desc = new Description();
        desc.setText("Dawki bazalne (U/h)");
        desc.setTextSize(12f);
        chart.setDescription(desc);

        chart.setDrawGridBackground(false);
        chart.setDrawBorders(false);
        chart.setPinchZoom(true);
        chart.setDoubleTapToZoomEnabled(true);
        chart.setScaleYEnabled(false);
        chart.setDragEnabled(true);
        chart.setExtraTopOffset(8f);
        chart.setExtraBottomOffset(8f);
        chart.setExtraLeftOffset(12f);
        chart.setExtraRightOffset(12f);

        chart.getLegend().setEnabled(false);
        chart.getAxisRight().setEnabled(false);

        YAxis leftAxis = chart.getAxisLeft();
        leftAxis.setAxisMinimum(0f);
        leftAxis.setDrawGridLines(true);
        leftAxis.setGridLineWidth(0.5f);
        leftAxis.setTextSize(11f);
        leftAxis.setGranularityEnabled(true);
        leftAxis.setGranularity(0.1f);

        XAxis xAxis = chart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setAxisMinimum(0f);
        xAxis.setAxisMaximum(24f);
        xAxis.setGranularity(1f);
        xAxis.setGranularityEnabled(true);
        xAxis.setLabelCount(9, true);
        xAxis.setTextSize(11f);
        xAxis.setDrawGridLines(true);
        xAxis.setGridLineWidth(0.5f);

        chart.setHighlightPerTapEnabled(false);
        chart.setHighlightPerDragEnabled(false);
        chart.animateX(0);
    }

    private void renderChart(@NonNull BasalProfile profile) {
        double[] hours = profile.toHourArray();
        ArrayList<Entry> entries = new ArrayList<>(hours.length + 1);
        for (int i = 0; i < hours.length; i++) entries.add(new Entry(i, (float) hours[i]));
        entries.add(new Entry(24f, (float) hours[hours.length - 1]));

        LineDataSet ds = new LineDataSet(entries, null);
        ds.setMode(LineDataSet.Mode.STEPPED);
        ds.setLineWidth(2f);
        ds.setColor(0xFF2196F3);
        ds.setDrawCircles(false);
        ds.setDrawValues(false);
        ds.setHighlightEnabled(false);

        chart.setData(new LineData(ds));
        chart.invalidate();
    }

    private void highlightSelectedHour() {
        XAxis xAxis = chart.getXAxis();
        xAxis.removeAllLimitLines();
        float centerX = selectedHour + 0.5f;
        LimitLine hourBand = new LimitLine(centerX, "");
        hourBand.setLineColor(0x33FFA000);
        hourBand.setLineWidth(20f);
        hourBand.setTextSize(0f);
        hourBand.enableDashedLine(0f, 0f, 0f);
        xAxis.addLimitLine(hourBand);
        chart.invalidate();
    }

    private void updateSelectedHourText() {
        tvSelectedHour.setText(String.format(Locale.getDefault(),
                "Godzina: %02d:00–%02d:00", selectedHour, (selectedHour + 1)));
    }

    private void updateHourInfo(@NonNull BasalProfile profile) {
        double rate = profile.getBasalRateAtHour(selectedHour); // hour-grid accessor
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
    LIST
}
