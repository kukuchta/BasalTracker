package com.kukuchta.basaltracker.data.db;

import androidx.room.Database;
import androidx.room.RoomDatabase;

import com.kukuchta.basaltracker.data.db.entities.BasalProfileEntity;

@Database(
        entities = { BasalProfileEntity.class },
        version = 1,
        exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {
    public abstract BasalProfileDao basalProfileDao();
}
