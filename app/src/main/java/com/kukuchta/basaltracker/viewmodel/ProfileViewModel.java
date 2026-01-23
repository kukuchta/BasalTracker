package com.kukuchta.basaltracker.viewmodel;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;

import com.kukuchta.basaltracker.data.repo.BasalProfileRepository;
import com.kukuchta.basaltracker.domain.BasalProfile;
import com.kukuchta.basaltracker.domain.BasalProfileDiff;
import com.kukuchta.basaltracker.domain.ProfileOrigin;
import com.kukuchta.basaltracker.ui.editor.SegmentProjector;
import com.kukuchta.basaltracker.ui.editor.UiSegment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

public class ProfileViewModel extends AndroidViewModel {

    private final BasalProfileRepository repo;

    private final MutableLiveData<List<BasalProfile>> profiles = new MutableLiveData<>();
    private final MutableLiveData<BasalProfile> currentProfile = new MutableLiveData<>();
    private final MutableLiveData<BasalProfileDiff> comparisonResult = new MutableLiveData<>();

    // Compressed, UI-facing representation of contiguous runs of equal hourly units
    private final MediatorLiveData<List<UiSegment>> uiSegments = new MediatorLiveData<>();

    private long currentProfileId = 0;

    public ProfileViewModel(@NonNull Application app) {
        super(app);
        repo = new BasalProfileRepository(app);

        // Recompute segments whenever the profile changes
        uiSegments.addSource(currentProfile, p -> {
            if (p == null) {
                uiSegments.postValue(Collections.emptyList());
            } else {
                int[] units = p.copyUnitsByHour();
                uiSegments.postValue(SegmentProjector.project(units, p.getAccuracy()));
            }
        });
    }

    // --- Exposed LiveData ---
    public LiveData<List<BasalProfile>> getProfiles() { return profiles; }
    public LiveData<BasalProfile> getCurrentProfile() { return currentProfile; }
    public LiveData<BasalProfileDiff> getComparisonResult() { return comparisonResult; }
    public LiveData<List<UiSegment>> getUiSegments() { return uiSegments; }

    // --- List & persistence ---
    public void loadAllProfiles() { repo.getAllProfiles(profiles::postValue); }

    public void createEmptyProfile(String name, double accuracy, Runnable onSuccessWithOpen) {
        repo.createEmptyProfile(name, accuracy, id -> {
            currentProfileId = id;
            loadProfile(id);
            if (onSuccessWithOpen != null) onSuccessWithOpen.run();
        });
    }

    public void duplicateProfile(long id, String nameSuffix, Runnable onSuccessWithOpen) {
        repo.duplicateProfile(id, nameSuffix, newId -> {
            currentProfileId = newId;
            loadProfile(newId);
            if (onSuccessWithOpen != null) onSuccessWithOpen.run();
        });
    }

    public void deleteProfile(long id, Runnable onDone) {
        repo.deleteProfile(id, () -> {
            loadAllProfiles();
            if (onDone != null) onDone.run();
        });
    }

    // --- Editor ---
    public void loadProfile(long id) {
        currentProfileId = id;
        repo.getProfile(id, currentProfile::postValue);
    }

    public void setCurrentProfile(BasalProfile p) {
        currentProfileId = p.getId();
        currentProfile.postValue(p);
    }

    /** Hour-grid bump (+/- one accuracy unit). */
    public void adjustRateForHour(int hour, boolean increase) {
        BasalProfile p = currentProfile.getValue();
        if (p == null) throw new IllegalStateException("Profil niezaładowany.");
        p.adjustRateForHour(hour, increase);
        currentProfile.postValue(p);
    }

    /** Hour-grid setter for a single hour. */
    public void setRateAtHour(int hour, double newRate) {
        BasalProfile p = currentProfile.getValue();
        if (p == null) throw new IllegalStateException("Profil niezaładowany.");
        p.setRateAtHour(hour, newRate);
        currentProfile.postValue(p);
    }

    /**
     * Single atomic edit of a compressed UI segment:
     * - Set the segment's rate (U/h)
     * - Set the segment's end hour (exclusive) on the hour grid
     * The compressed list will auto-split/merge on next projection.
     */
    public void applySegmentEdit(UiSegment segment, double newRateUh, int newEndHourExclusive) {
        BasalProfile p = currentProfile.getValue();
        if (p == null) throw new IllegalStateException("Profil niezaładowany.");

        final int start = segment.startHour;
        final int origEnd = segment.endHourExclusive;
        if (newEndHourExclusive <= start || newEndHourExclusive > 24) {
            throw new IllegalArgumentException("Koniec musi być w zakresie (start, 24].");
        }

        // Snapshot original units for restoration when shortening
        int[] origUnits = p.copyUnitsByHour();

        long targetUnitsL = Math.round(newRateUh / p.getAccuracy());
        if (targetUnitsL < 0) throw new IllegalArgumentException("Dawka nie może być ujemna.");
        final int targetUnits = (int) targetUnitsL;

        // 1) Set [start, newEnd) to targetUnits
        for (int h = start; h < newEndHourExclusive; h++) {
            p.setRateAtHour(h, newRateUh);
        }

        // 2) If shortened, restore [newEnd, origEnd) to what followed originally
        if (newEndHourExclusive < origEnd) {
            final int nextUnitsAfterOrigEnd = (origEnd < 24) ? origUnits[origEnd] : 0;
            final double restoreUh = nextUnitsAfterOrigEnd * p.getAccuracy();
            for (int h = newEndHourExclusive; h < origEnd; h++) {
                p.setRateAtHour(h, restoreUh);
            }
        }

        setCurrentProfile(p);
    }

    /** Optional: apply a full 24-hour shape (e.g., from a circadian algorithm). */
    public void applyHourlyRates(double[] ratesUh) {
        if (ratesUh == null || ratesUh.length != 24)
            throw new IllegalArgumentException("24 hourly rates required");
        BasalProfile p = getCurrentProfile().getValue();
        if (p == null) throw new IllegalStateException("Profil niezaładowany.");
        for (int h = 0; h < 24; h++) p.setRateAtHour(h, ratesUh[h]);
        setCurrentProfile(p);
    }

    public void saveCurrentProfile() {
        BasalProfile p = currentProfile.getValue();
        if (p == null) throw new IllegalStateException("Brak profilu do zapisu.");
        repo.upsert(p, id -> {
            currentProfileId = id;
            loadProfile(id);
            loadAllProfiles();
        });
    }

    public void compareProfiles(long id1, long id2) {
        repo.getProfile(id1, p1 -> repo.getProfile(id2, p2 -> {
            if (p1 != null && p2 != null) comparisonResult.postValue(p1.diff(p2));
        }));
    }
}
