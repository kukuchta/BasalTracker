package com.kukuchta.basaltracker.data.db.models;

import androidx.room.Embedded;
import androidx.room.Relation;

import com.kukuchta.basaltracker.data.db.entities.BasalProfileEntity;
import com.kukuchta.basaltracker.data.db.entities.BasalSegmentEntity;

import java.util.List;

public class BasalProfileWithSegments {
    @Embedded public BasalProfileEntity profile;
    @Relation(parentColumn = "id", entityColumn = "profileId")
    public List<BasalSegmentEntity> segments;
}
