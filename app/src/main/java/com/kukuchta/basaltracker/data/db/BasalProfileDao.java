package com.kukuchta.basaltracker.data.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.kukuchta.basaltracker.data.db.entities.BasalProfileEntity;

import java.util.List;

@Dao
public interface BasalProfileDao {

    @Query("SELECT * FROM basal_profiles ORDER BY createdAt DESC")
    List<BasalProfileEntity> getAllProfiles();

    @Query("SELECT * FROM basal_profiles WHERE id = :id")
    BasalProfileEntity getProfile(long id);

    @Insert
    long insertProfile(BasalProfileEntity profile);

    @Update
    void updateProfile(BasalProfileEntity profile);

    @Query("DELETE FROM basal_profiles WHERE id = :id")
    void deleteProfile(long id);
}
