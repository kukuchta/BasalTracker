package com.kukuchta.basaltracker.data.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Transaction;
import androidx.room.Update;

import com.kukuchta.basaltracker.data.db.entities.BasalProfileEntity;
import com.kukuchta.basaltracker.data.db.entities.BasalSegmentEntity;
import com.kukuchta.basaltracker.data.db.models.BasalProfileWithSegments;

import java.util.List;

@Dao
public interface BasalProfileDao {

    @Transaction
    @Query("SELECT * FROM basal_profiles ORDER BY createdAt DESC")
    List<BasalProfileWithSegments> getAllProfiles();

    @Transaction
    @Query("SELECT * FROM basal_profiles WHERE id = :id")
    BasalProfileWithSegments getProfile(long id);

    @Insert
    long insertProfile(BasalProfileEntity profile);

    @Update
    void updateProfile(BasalProfileEntity profile);

    @Insert
    void insertSegments(List<BasalSegmentEntity> segments);

    @Query("DELETE FROM basal_segments WHERE profileId = :profileId")
    void deleteSegmentsForProfile(long profileId);

    @Transaction
    default long upsertProfile(BasalProfileEntity profile,
                               List<BasalSegmentEntity> segments) {
        long id = profile.id == 0 ? insertProfile(profile) : profile.id;
        if (profile.id != 0) updateProfile(profile);
        deleteSegmentsForProfile(id);
        for (BasalSegmentEntity e : segments) {
            e.profileId = id;
        }
        insertSegments(segments);
        return id;
    }

    @Query("DELETE FROM basal_profiles WHERE id = :id")
    void deleteProfile(long id);
}
