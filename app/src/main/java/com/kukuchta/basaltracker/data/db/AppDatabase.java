package com.kukuchta.basaltracker.data.db;

import androidx.room.Database;
import androidx.room.RoomDatabase;

import com.kukuchta.basaltracker.data.db.entities.BasalProfileEntity;
import com.kukuchta.basaltracker.data.db.entities.BasalSegmentEntity;

@Database(
        entities = {BasalProfileEntity.class, BasalSegmentEntity.class},
        version = 1,
        exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {
    public abstract BasalProfileDao basalProfileDao();
}