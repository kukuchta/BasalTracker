
package com.kukuchta.basaltracker.domain;

import java.math.BigDecimal;
import java.util.*;

/**
 * BasalProfile (hour-grid)
 *
 * Model overview:
 * - Stores a fixed-size array of integer "units" for each hour 0..23.
 * - The actual basal rate in U/h is computed as: rate = units * accuracy.
 * - Rates are non-negative and quantized by "accuracy" using Math.round(rate/accuracy).
 *
 * Why hour-grid:
 * - The domain uses an hourly grid exclusively (no sub-hour change points).
 * - A 24-slot array is a canonical, simpler representation than change points.
 *
 * Invariants:
 * - accuracy > 0
 * - unitsByHour.length == 24
 * - unitsByHour[h] >= 0 for all h in 0..23
 *
 * Public behavior:
 * - getBasalRateAtHour(h): returns U/h for hour h (0..23).
 * - toHourArray(): returns 24 U/h samples, one per hour.
 * - getTotalDailyDose(): exact sum over 24 hours using BigDecimal.
 *
 * Mutability:
 * - Edits are in-place (same stance as the original class).
 * - setRateAtHour and adjustRateForHour mutate the internal array.
 */
public final class BasalProfile {
    private final long id;
    private final String name;
    private final double accuracy;             // quantization step in U/h; must be > 0
    private final ProfileOrigin origin;        // default to USER_MODIFIED when not provided
    private final Long baseProfileId;          // optional link to a base profile
    private final Map<String, String> metadata;

    /**
     * Fixed hour-grid storage:
     * - Index is hour 0..23.
     * - Value is integer "units"; the real U/h is units * accuracy.
     */
    private final int[] unitsByHour;

    /**
     * Constructs an hour-grid BasalProfile.
     *
     * @param id            profile identifier
     * @param name          profile name (non-null)
     * @param accuracy      U/h step (> 0)
     * @param origin        profile origin (defaults to USER_MODIFIED if null)
     * @param baseProfileId optional base profile id
     * @param metadata      arbitrary key-value metadata (defensively copied)
     * @param unitsByHour   24-length array of non-negative units (defensively copied)
     */
    public BasalProfile(
            long id,
            String name,
            double accuracy,
            ProfileOrigin origin,
            Long baseProfileId,
            Map<String, String> metadata,
            int[] unitsByHour
    ) {
        this.id = id;
        this.name = Objects.requireNonNull(name, "name");
        if (accuracy <= 0.0) throw new IllegalArgumentException("accuracy must be > 0");
        this.accuracy = accuracy;
        this.origin = (origin == null) ? ProfileOrigin.USER_MODIFIED : origin;
        this.baseProfileId = baseProfileId;
        this.metadata = (metadata != null) ? new HashMap<>(metadata) : new HashMap<>();

        if (unitsByHour == null || unitsByHour.length != 24) {
            throw new IllegalArgumentException("unitsByHour must be non-null and have length 24");
        }
        this.unitsByHour = Arrays.copyOf(unitsByHour, 24);
        for (int u : this.unitsByHour) {
            if (u < 0) throw new IllegalArgumentException("rate units must be >= 0");
        }
    }

    // ---------- Accessors ----------

    public long getId() { return id; }
    public String getName() { return name; }
    public double getAccuracy() { return accuracy; }
    public ProfileOrigin getOrigin() { return origin; }
    public Long getBaseProfileId() { return baseProfileId; }
    public Map<String, String> getMetadata() { return Collections.unmodifiableMap(metadata); }

    /**
     * Basal rate in U/h for hour h.
     * @param hour hour of day (0..23)
     * @return rate in U/h
     */
    public double getBasalRateAtHour(int hour) {
        validateHour(hour);
        return toRate(unitsByHour[hour]);
    }

    /**
     * Chart helper: U/h samples for each hour 0..23.
     * @return array of length 24 with hourly U/h values
     */
    public double[] toHourArray() {
        double[] arr = new double[24];
        for (int h = 0; h < 24; h++) {
            arr[h] = toRate(unitsByHour[h]);
        }
        return arr;
    }

    /**
     * Exact total daily dose (TDD) in units:
     * - Since each bucket is 1 hour, TDD = sum(rate(h) * 1h), h=0..23.
     * - Uses BigDecimal to avoid floating precision drift.
     * @return total daily dose as BigDecimal
     */
    public BigDecimal getTotalDailyDose() {
        BigDecimal total = BigDecimal.ZERO;
        for (int h = 0; h < 24; h++) {
            total = total.add(BigDecimal.valueOf(toRate(unitsByHour[h])));
        }
        return total;
    }

    // ---------- Editing operations (mutate in place) ----------

    /**
     * Sets the rate for a given hour to a desired U/h value.
     * - The value is quantized to integer units via Math.round(newRate / accuracy).
     * - The final units must be non-negative.
     *
     * @param hour    hour of day (0..23)
     * @param newRate desired rate in U/h
     */
    public void setRateAtHour(int hour, double newRate) {
        validateHour(hour);
        long units = Math.round(newRate / accuracy);
        if (units < 0) throw new IllegalArgumentException("Rate cannot be negative");
        unitsByHour[hour] = (int) units;
    }

    /**
     * Adjusts the rate for a given hour by a single quantization step.
     * - increase=true  => +1 unit (i.e., +accuracy U/h)
     * - increase=false => -1 unit (i.e., -accuracy U/h), but not below zero
     *
     * @param hour     hour of day (0..23)
     * @param increase whether to bump up (true) or down (false)
     */
    public void adjustRateForHour(int hour, boolean increase) {
        validateHour(hour);
        int currentUnits = unitsByHour[hour];
        int targetUnits = increase ? currentUnits + 1 : currentUnits - 1;
        if (targetUnits < 0) throw new IllegalArgumentException("Rate would become negative");
        unitsByHour[hour] = targetUnits;
    }

    // ---------- Helpers ----------

    private static void validateHour(int hour) {
        if (hour < 0 || hour > 23) throw new IllegalArgumentException("hour 0..23");
    }

    private double toRate(int units) {
        return units * accuracy;
    }

    /**
     * Returns a defensive copy of the internal hour-units array.
     * Useful for tests or external read-only scenarios.
     */
    public int[] copyUnitsByHour() {
        return Arrays.copyOf(unitsByHour, 24);
    }
}
