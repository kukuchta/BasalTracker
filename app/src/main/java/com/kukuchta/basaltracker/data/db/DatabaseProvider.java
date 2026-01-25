package com.kukuchta.basaltracker.data.db;

import android.content.Context;
import androidx.room.Room;

public final class DatabaseProvider {

    private static volatile AppDatabase INSTANCE;

    private DatabaseProvider() {}

    public static AppDatabase getDatabase(final Context context) {
        if (INSTANCE == null) {
            synchronized (DatabaseProvider.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    AppDatabase.class,
                                    "basal-db"
                            )
                            .fallbackToDestructiveMigration(true)
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}
