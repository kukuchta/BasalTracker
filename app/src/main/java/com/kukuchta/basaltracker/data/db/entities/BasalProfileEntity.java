package com.kukuchta.basaltracker.data.db.entities;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "basal_profiles")
public class BasalProfileEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;

    public String name;

    /** Quantization step in U/h (> 0) */
    public double accuracy;

    /** Enum name: GENERATED, USER_MODIFIED, IMPORTED */
    public String origin;

    /** Nullable link to base profile */
    public Long baseProfileId;

    /** Serialized metadata map (e.g., JSON) */
    public String metadataJson;

    /** Creation timestamp (epoch millis) */
    public long createdAt;

    /** Hour-grid storage: units for each hour 0..23 (non-negative integers) */
    public int units_h00;
    public int units_h01;
    public int units_h02;
    public int units_h03;
    public int units_h04;
    public int units_h05;
    public int units_h06;
    public int units_h07;
    public int units_h08;
    public int units_h09;
    public int units_h10;
    public int units_h11;
    public int units_h12;
    public int units_h13;
    public int units_h14;
    public int units_h15;
    public int units_h16;
    public int units_h17;
    public int units_h18;
    public int units_h19;
    public int units_h20;
    public int units_h21;
    public int units_h22;
    public int units_h23;
}
