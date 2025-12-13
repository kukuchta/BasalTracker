package com.kukuchta.basaltracker.ui.editor;

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
    private MaterialButtonToggleGroup toggleMode;
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
        toggleMode = v.findViewById(R.id.toggleMode);
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
        setupToggle();
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

        switchToHourly(true);
    }

    private void setupToggle() {
        toggleMode.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            switchToHourly(checkedId == R.id.btnModeHourly);
        });
    }

    private void switchToHourly(boolean hourly) {
        panelHourly.setVisibility(hourly ? View.VISIBLE : View.GONE);
        panelSegments.setVisibility(hourly ? View.GONE : View.VISIBLE);
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
        chart.setNoDataText("Brak danych profilu.");
        Description desc = new Description();
        desc.setText("Dawki bazalne (co 1 h)");
        chart.setDescription(desc);
        chart.getAxisRight().setEnabled(false);
        XAxis xAxis = chart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setGranularity(1f); // 0..23
        xAxis.setLabelCount(12, true);
        chart.getLegend().setEnabled(false);
        chart.setPinchZoom(true);
        chart.setDoubleTapToZoomEnabled(true);
    }

    private void renderChart(@NonNull BasalProfile profile) {
        double[] hours = profile.toHourArray();
        ArrayList<Entry> entries = new ArrayList<>(24);
        for (int i = 0; i < hours.length; i++) entries.add(new Entry(i, (float) hours[i]));
        LineDataSet ds = new LineDataSet(entries, "Profil");
        ds.setLineWidth(2f);
        ds.setColor(0xFF2196F3);
        ds.setCircleRadius(0f);
        ds.setDrawValues(false);
        chart.setData(new LineData(ds));
        chart.invalidate();
    }

    private void highlightSelectedHour() {
        XAxis xAxis = chart.getXAxis();
        xAxis.removeAllLimitLines();
        LimitLine llStart = new LimitLine(selectedHour, "");
        llStart.setLineColor(0xFFFFA000);
        llStart.setLineWidth(1.5f);
        LimitLine llEnd = new LimitLine(selectedHour + 1, "");
        llEnd.setLineColor(0xFFFFA000);
        llEnd.setLineWidth(1.5f);
        xAxis.addLimitLine(llStart);
        xAxis.addLimitLine(llEnd);
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
