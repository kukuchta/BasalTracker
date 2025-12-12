package com.kukuchta.basaltracker.data.db.entities;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "basal_profiles")
public class BasalProfileEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;
    public String name;
    public double accuracy;     // U/h
    public String origin;       // enum name GENERATED, USER_MODIFIED, IMPORTED
    public Long baseProfileId;  // nullable
    public String metadataJson; // serialized map
    public long createdAt;
}
