package com.kukuchta.basaltracker.data.mapper;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import com.kukuchta.basaltracker.data.db.entities.BasalProfileEntity;
import com.kukuchta.basaltracker.domain.BasalProfile;
import com.kukuchta.basaltracker.domain.ProfileOrigin;

import java.lang.reflect.Type;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class BasalProfileMapper {
    private static final Gson gson = new Gson();
    private static final Type MAP_STRING_STRING = new TypeToken<Map<String, String>>() {}.getType();

    private BasalProfileMapper() {}

    public static BasalProfile toDomain(BasalProfileEntity e) {
        Map<String, String> metadata = parseMetadata(e.metadataJson);
        int[] units = new int[] {
                e.units_h00, e.units_h01, e.units_h02, e.units_h03, e.units_h04, e.units_h05,
                e.units_h06, e.units_h07, e.units_h08, e.units_h09, e.units_h10, e.units_h11,
                e.units_h12, e.units_h13, e.units_h14, e.units_h15, e.units_h16, e.units_h17,
                e.units_h18, e.units_h19, e.units_h20, e.units_h21, e.units_h22, e.units_h23
        };

        return new BasalProfile(
                e.id,
                e.name,
                e.accuracy,
                ProfileOrigin.valueOf(e.origin),
                e.baseProfileId,
                metadata,
                units
        );
    }

    public static BasalProfileEntity toEntity(BasalProfile d) {
        BasalProfileEntity e = new BasalProfileEntity();
        e.id = d.getId();
        e.name = d.getName();
        e.accuracy = d.getAccuracy();
        e.origin = d.getOrigin().name();
        e.baseProfileId = d.getBaseProfileId();
        e.metadataJson = gson.toJson(d.getMetadata());
        e.createdAt = System.currentTimeMillis();

        int[] u = d.copyUnitsByHour();
        e.units_h00 = u[0];  e.units_h01 = u[1];  e.units_h02 = u[2];  e.units_h03 = u[3];
        e.units_h04 = u[4];  e.units_h05 = u[5];  e.units_h06 = u[6];  e.units_h07 = u[7];
        e.units_h08 = u[8];  e.units_h09 = u[9];  e.units_h10 = u[10]; e.units_h11 = u[11];
        e.units_h12 = u[12]; e.units_h13 = u[13]; e.units_h14 = u[14]; e.units_h15 = u[15];
        e.units_h16 = u[16]; e.units_h17 = u[17]; e.units_h18 = u[18]; e.units_h19 = u[19];
        e.units_h20 = u[20]; e.units_h21 = u[21]; e.units_h22 = u[22]; e.units_h23 = u[23];

        return e;
    }

    private static Map<String, String> parseMetadata(String json) {
        if (json == null || json.isEmpty()) return new HashMap<>();
        Map<String, String> m = gson.fromJson(json, MAP_STRING_STRING);
        return (m == null) ? new HashMap<>() : m;
    }
}
``
