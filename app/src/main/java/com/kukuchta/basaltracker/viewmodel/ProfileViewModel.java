package com.kukuchta.basaltracker.viewmodel;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.*;
import com.kukuchta.basaltracker.data.repo.BasalProfileRepository;
import com.kukuchta.basaltracker.domain.*;

import java.util.List;

public class ProfileViewModel extends AndroidViewModel {

    private final BasalProfileRepository repo;

    private final MutableLiveData<List<BasalProfile>> profiles = new MutableLiveData<>();
    private final MutableLiveData<BasalProfile> currentProfile = new MutableLiveData<>();
    private final MutableLiveData<BasalProfileDiff> comparisonResult = new MutableLiveData<>();

    private long currentProfileId = 0;

    public ProfileViewModel(@NonNull Application app) {
        super(app);
        repo = new BasalProfileRepository(app);
    }

    public LiveData<List<BasalProfile>> getProfiles() { return profiles; }
    public LiveData<BasalProfile> getCurrentProfile() { return currentProfile; }
    public LiveData<BasalProfileDiff> getComparisonResult() { return comparisonResult; }

    // List
    public void loadAllProfiles() { repo.getAllProfiles(profiles::postValue); }
    public void createEmptyProfile(String name, double accuracy, Runnable onSuccessWithOpen) {
        repo.createEmptyProfile(name, accuracy, id -> { currentProfileId = id; loadProfile(id); if (onSuccessWithOpen != null) onSuccessWithOpen.run(); });
    }
    public void duplicateProfile(long id, String nameSuffix, Runnable onSuccessWithOpen) {
        repo.duplicateProfile(id, nameSuffix, newId -> { currentProfileId = newId; loadProfile(newId); if (onSuccessWithOpen != null) onSuccessWithOpen.run(); });
    }
    public void deleteProfile(long id, Runnable onDone) {
        repo.deleteProfile(id, () -> { loadAllProfiles(); if (onDone != null) onDone.run(); });
    }

    // Editor
    public void loadProfile(long id) { currentProfileId = id; repo.getProfile(id, currentProfile::postValue); }
    public void setCurrentProfile(BasalProfile p) { currentProfileId = p.getId(); currentProfile.postValue(p); }

    public void adjustRateForHour(int hour, boolean increase) {
        BasalProfile p = currentProfile.getValue();
        if (p == null) throw new IllegalStateException("Profil niezaładowany.");
        p.adjustRateForHour(hour, increase);
        currentProfile.postValue(p);
    }

    public void updateSegmentEnd(int startMinutes, int newEndMinutes) {
        BasalProfile p = currentProfile.getValue();
        if (p == null) throw new IllegalStateException("Profil niezaładowany.");
        p.updateSegmentEnd(startMinutes, newEndMinutes);
        currentProfile.postValue(p);
    }

    public void updateSegmentRate(int startMinutes, double newRate) {
        BasalProfile p = currentProfile.getValue();
        if (p == null) throw new IllegalStateException("Profil niezaładowany.");
        p.updateSegmentRate(startMinutes, newRate);
        currentProfile.postValue(p);
    }

    public void saveCurrentProfile() {
        BasalProfile p = currentProfile.getValue();
        if (p == null) throw new IllegalStateException("Brak profilu do zapisu.");
        repo.upsert(p, id -> { currentProfileId = id; loadProfile(id); loadAllProfiles(); });
    }

    public void compareProfiles(long id1, long id2) {
        repo.getProfile(id1, p1 -> repo.getProfile(id2, p2 -> {
            if (p1 != null && p2 != null) comparisonResult.postValue(p1.diff(p2));
        }));
    }
}
