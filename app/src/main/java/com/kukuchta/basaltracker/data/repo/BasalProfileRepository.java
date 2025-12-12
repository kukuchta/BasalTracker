package com.kukuchta.basaltracker.data.repo;

import android.app.Application;

import androidx.room.Room;

import com.kukuchta.basaltracker.data.db.AppDatabase;
import com.kukuchta.basaltracker.data.db.BasalProfileDao;
import com.kukuchta.basaltracker.data.db.entities.BasalProfileEntity;
import com.kukuchta.basaltracker.data.db.models.BasalProfileWithSegments;
import com.kukuchta.basaltracker.data.mapper.BasalProfileMapper;
import com.kukuchta.basaltracker.domain.BasalProfile;
import com.kukuchta.basaltracker.domain.BasalSegment;
import com.kukuchta.basaltracker.domain.ProfileOrigin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BasalProfileRepository {
    private final AppDatabase db;
    private final BasalProfileDao dao;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    public BasalProfileRepository(Application app) {
        db = Room.databaseBuilder(app, AppDatabase.class, "basal-db")
                .fallbackToDestructiveMigration() // adjust in prod migrations
                .build();
        dao = db.basalProfileDao();
    }

    public interface ListCallback { void onResult(List<BasalProfile> profiles); }
    public interface ItemCallback { void onResult(BasalProfile profile); }
    public interface IdCallback { void onResult(long id); }
    public interface VoidCallback { void onDone(); }

    public void getAllProfiles(ListCallback cb) {
        io.execute(() -> {
            List<BasalProfileWithSegments> rows = dao.getAllProfiles();
            List<BasalProfile> result = new ArrayList<>();
            for (BasalProfileWithSegments r : rows) {
                result.add(BasalProfileMapper.toDomain(r));
            }
            cb.onResult(result);
        });
    }

    public void getProfile(long id, ItemCallback cb) {
        io.execute(() -> {
            BasalProfileWithSegments row = dao.getProfile(id);
            if (row == null) {
                cb.onResult(null);
            } else {
                cb.onResult(BasalProfileMapper.toDomain(row));
            }
        });
    }

    public void upsert(BasalProfile profile, IdCallback cb) {
        io.execute(() -> {
            BasalProfileEntity pe = BasalProfileMapper.toEntity(profile);
            long id = dao.upsertProfile(pe, BasalProfileMapper.toSegmentEntities(profile, pe.id));
            cb.onResult(id);
        });
    }

    public void deleteProfile(long id, VoidCallback cb) {
        io.execute(() -> {
            dao.deleteSegmentsForProfile(id);
            dao.deleteProfile(id);
            cb.onDone();
        });
    }

    public void createEmptyProfile(String name, double accuracy, IdCallback cb) {
        if (accuracy <= 0.0) throw new IllegalArgumentException("accuracy must be > 0");
        io.execute(() -> {
            BasalProfile profile = new BasalProfile(
                    0,
                    (name == null || name.isEmpty()) ? "Nowy profil" : name,
                    accuracy,
                    ProfileOrigin.USER_MODIFIED,
                    null,
                    new HashMap<>(),
                    List.of(new BasalSegment(0, 0))
            );
            BasalProfileEntity profileEntity = BasalProfileMapper.toEntity(profile);
            long id = dao.upsertProfile(profileEntity, BasalProfileMapper.toSegmentEntities(profile, 0));
            cb.onResult(id);
        });
    }

    public void duplicateProfile(long id, String nameSuffix, IdCallback cb) {
        io.execute(() -> {
            BasalProfileWithSegments row = dao.getProfile(id);
            if (row == null) throw new IllegalArgumentException("Profil nie istnieje, id=" + id);
            BasalProfile original = BasalProfileMapper.toDomain(row);
            String newName = original.getName() + (nameSuffix == null || nameSuffix.isEmpty() ? " (kopia)" : nameSuffix);
            BasalProfile duplicate = new BasalProfile(
                    0,
                    newName,
                    original.getAccuracy(),
                    ProfileOrigin.USER_MODIFIED,
                    original.getId(),
                    original.getMetadata(),
                    original.getSegments());
            BasalProfileEntity profileEntity = BasalProfileMapper.toEntity(duplicate);
            long newId = dao.upsertProfile(profileEntity, BasalProfileMapper.toSegmentEntities(duplicate, 0));
            cb.onResult(newId);
        });
    }
}
