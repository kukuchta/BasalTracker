package com.kukuchta.basaltracker.data.db.entities;

import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "basal_segments",
        foreignKeys = @ForeignKey(
                entity = BasalProfileEntity.class,
                parentColumns = "id",
                childColumns = "profileId",
                onDelete = ForeignKey.CASCADE
        ),
        indices = {
                @Index("profileId"),
                @Index(value = {"profileId", "startMinutes"}, unique = true)
        }
)
public class BasalSegmentEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;
    public long profileId;
    public int startMinutes; // change point [0..1439], multiple of 60, first = 0
    public int rateUnits;    // rate = units * accuracy
}
