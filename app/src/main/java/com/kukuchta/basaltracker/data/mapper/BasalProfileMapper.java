package com.kukuchta.basaltracker.data.mapper;

import com.kukuchta.basaltracker.data.db.entities.BasalProfileEntity;
import com.kukuchta.basaltracker.data.db.entities.BasalSegmentEntity;
import com.kukuchta.basaltracker.data.db.models.BasalProfileWithSegments;
import com.kukuchta.basaltracker.domain.BasalProfile;
import com.kukuchta.basaltracker.domain.BasalSegment;
import com.kukuchta.basaltracker.domain.ProfileOrigin;
import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class BasalProfileMapper {

    private static final Gson gson = new Gson();

    private BasalProfileMapper() {}

    public static BasalProfile toDomain(BasalProfileWithSegments row) {
        List<BasalSegment> segs = new ArrayList<>();
        for (BasalSegmentEntity e : row.segments) {
            segs.add(new BasalSegment(e.startMinutes, e.rateUnits));
        }
        Map<String, String> metadata = new HashMap<>();
        if (row.profile.metadataJson != null && !row.profile.metadataJson.isEmpty()) {
            metadata = gson.fromJson(row.profile.metadataJson, metadata.getClass());
        }
        return new BasalProfile(
                row.profile.id,
                row.profile.name,
                row.profile.accuracy,
                ProfileOrigin.valueOf(row.profile.origin),
                row.profile.baseProfileId,
                metadata,
                segs
        );
    }

    public static BasalProfileEntity toEntity(BasalProfile domain) {
        BasalProfileEntity pe = new BasalProfileEntity();
        pe.id = domain.getId();
        pe.name = domain.getName();
        pe.accuracy = domain.getAccuracy();
        pe.origin = domain.getOrigin().name();
        pe.baseProfileId = domain.getBaseProfileId();
        pe.metadataJson = gson.toJson(domain.getMetadata());
        pe.createdAt = System.currentTimeMillis();
        return pe;
    }

    public static List<BasalSegmentEntity> toSegmentEntities(BasalProfile domain, long profileId) {
        List<BasalSegmentEntity> list = new ArrayList<>();
        for (BasalSegment s : domain.getSegments()) {
            BasalSegmentEntity e = new BasalSegmentEntity();
            e.profileId = profileId;
            e.startMinutes = s.getStartMinutes();
            e.rateUnits = s.getRateUnits();
            list.add(e);
        }
        return list;
    }
}
