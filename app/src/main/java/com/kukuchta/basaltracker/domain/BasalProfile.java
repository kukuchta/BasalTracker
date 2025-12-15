package com.kukuchta.basaltracker.domain;

import java.math.BigDecimal;
import java.util.*;

public final class BasalProfile {
    private final long id;
    private final String name;
    private final double accuracy;   // U/h step
    private final ProfileOrigin origin;
    private final Long baseProfileId;
    private final Map<String, String> metadata;
    private final List<BasalSegment> segments; // sorted change points (startMinutes)

    public BasalProfile(long id,
                        String name,
                        double accuracy,
                        ProfileOrigin origin,
                        Long baseProfileId,
                        Map<String, String> metadata,
                        List<BasalSegment> segments) {
        this.id = id;
        this.name = Objects.requireNonNull(name);
        if (accuracy <= 0.0) throw new IllegalArgumentException("accuracy must be > 0");
        this.accuracy = accuracy;
        this.origin = origin == null ? ProfileOrigin.USER_MODIFIED : origin;
        this.baseProfileId = baseProfileId;
        this.metadata = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
        this.segments = new ArrayList<>(Objects.requireNonNull(segments));
        normalizeAndValidate(); // sort, merge same-rate neighbors, validate hour grid & coverage
    }

    public long getId() { return id; }
    public String getName() { return name; }
    public double getAccuracy() { return accuracy; }
    public ProfileOrigin getOrigin() { return origin; }
    public Long getBaseProfileId() { return baseProfileId; }
    public Map<String, String> getMetadata() { return Collections.unmodifiableMap(metadata); }
    public List<BasalSegment> getSegments() { return Collections.unmodifiableList(segments); }

    private static boolean isHourGrid(int minutes) { return minutes % 60 == 0; }

    private void normalizeAndValidate() {
        segments.sort(Comparator.comparingInt(BasalSegment::getStartMinutes));
        if (segments.isEmpty()) throw new IllegalStateException("Profile must have at least one change point");
        if (segments.get(0).getStartMinutes() != 0) throw new IllegalStateException("First change point must start at 0");
        if (segments.get(segments.size() - 1).getStartMinutes() >= 1440)
            throw new IllegalStateException("Last change point start must be < 1440");

        List<BasalSegment> merged = new ArrayList<>();
        Integer lastStart = null;
        Integer lastUnits = null;

        for (BasalSegment s : segments) {
            int start = s.getStartMinutes();
            int units = s.getRateUnits();
            if (!isHourGrid(start)) throw new IllegalStateException("startMinutes must align to 60-minute grid");
            if (units < 0) throw new IllegalStateException("rateUnits must be >= 0");

            // Drop exact duplicates by start; latest wins
            if (lastStart != null && start == lastStart) {
                merged.set(merged.size() - 1, new BasalSegment(start, units));
                lastUnits = units;
                continue;
            }
            // Merge consecutive same-units (no redundant change point)
            if (lastUnits != null && units == lastUnits) {
                continue;
            }
            merged.add(new BasalSegment(start, units));
            lastStart = start;
            lastUnits = units;
        }

        segments.clear();
        segments.addAll(merged);
    }

    private double toRate(int units) { return units * accuracy; }

    /** Binary search units at time (use current segments). */
    private int unitsAt(int minutes) {
        int idx = Collections.binarySearch(
                segments,
                new BasalSegment(minutes, 0),
                Comparator.comparingInt(BasalSegment::getStartMinutes));
        if (idx >= 0) return segments.get(idx).getRateUnits();
        int ip = -idx - 1;
        int pos = ip - 1;
        if (pos < 0) throw new IllegalStateException("Profile malformed: no segment for time");
        return segments.get(pos).getRateUnits();
    }

    public double getBasalRate(int minutesSinceMidnight) {
        if (minutesSinceMidnight < 0 || minutesSinceMidnight >= 1440)
            throw new IllegalArgumentException("minutesSinceMidnight must be in [0,1440)");
        return toRate(unitsAt(minutesSinceMidnight));
    }

    /** Chart helper: 24 hourly buckets, sample at hour starts. */
    public double[] toHourArray() {
        double[] arr = new double[24];
        for (int h = 0; h < 24; h++) arr[h] = getBasalRate(h * 60);
        return arr;
    }

    /** Precise total daily dose: sum(rate * hours) across change points. */
    public java.math.BigDecimal getTotalDailyDose() {
        java.math.BigDecimal total = java.math.BigDecimal.ZERO;
        for (int i = 0; i < segments.size(); i++) {
            BasalSegment s = segments.get(i);
            int start = s.getStartMinutes();
            int end = (i + 1 < segments.size()) ? segments.get(i + 1).getStartMinutes() : 1440;
            int minutes = end - start;
            if (minutes <= 0) throw new IllegalStateException("Non-positive segment duration");
            java.math.BigDecimal hours = java.math.BigDecimal.valueOf(minutes)
                    .divide(java.math.BigDecimal.valueOf(60), 9, java.math.RoundingMode.UNNECESSARY);
            java.math.BigDecimal rate = java.math.BigDecimal.valueOf(toRate(s.getRateUnits()));
            total = total.add(rate.multiply(hours));
        }
        return total;
    }

    /** Diff on 24 hourly values. */
    public BasalProfileDiff diff(BasalProfile other) {
        double[] a = toHourArray(), b = other.toHourArray();
        double total = 0, max = 0;
        for (int i = 0; i < 24; i++) {
            double d = Math.abs(a[i] - b[i]);
            total += d;
            if (d > max) max = d;
        }
        return new BasalProfileDiff(total, max);
    }

    // === Editing operations ===

    /** Update a segment's dose (rounds to accuracy units, merges neighbors if result equal). */
    public void updateSegmentRate(int segmentStartMinutes, double newRate) {
        int idx = indexOfStart(segmentStartMinutes);
        if (idx < 0) throw new IllegalArgumentException("Segment not found");
        long units = Math.round(newRate / accuracy);
        if (units < 0) throw new IllegalArgumentException("Rate cannot be negative");
        segments.set(idx, new BasalSegment(segmentStartMinutes, (int) units));
        normalizeAndValidate(); // merges adjacent equal doses
    }

    /** Update segment end time (only hour grid), adjust next or insert filler 0; merges as needed. */
    public void updateSegmentEnd(int segmentStartMinutes, int newEndMinutes) {
        if (!isHourGrid(newEndMinutes)) throw new IllegalArgumentException("newEnd must be multiple of 60");
        if (newEndMinutes <= segmentStartMinutes || newEndMinutes > 1440)
            throw new IllegalArgumentException("newEnd must be within (start, 1440]");

        int idx = indexOfStart(segmentStartMinutes);
        if (idx < 0) throw new IllegalArgumentException("Segment not found");

        int oldEnd = (idx + 1 < segments.size()) ? segments.get(idx + 1).getStartMinutes() : 1440;

        if (newEndMinutes < oldEnd) {
            setNextStart(idx, newEndMinutes);
        } else if (newEndMinutes > oldEnd) {
            // Insert filler 0 at oldEnd to bridge oldEnd..newEnd (then shift next start)
            insertOrReplaceChangePoint(oldEnd, 0);
            setNextStart(idx, newEndMinutes);
        }
        normalizeAndValidate();
    }

    /** Hourly adjust logic:
     *  - Compute max/min units within [hour, hour+1)
     *  - First click (+): equalize to maxUnits; first click (−): equalize to minUnits
     *  - Subsequent clicks: if already equal, move by ±1 unit (accuracy step), clipped at >=0
     */
    public void adjustRateForHour(int hour, boolean increase) {
        if (hour < 0 || hour > 23) throw new IllegalArgumentException("hour 0..23");
        int start = hour * 60;
        int end = start + 60;

        // With a 60-minute grid, the rate is constant for the entire hour.
        // We can just sample the rate at the beginning of the hour.
        int currentUnits = unitsAt(start);

        int targetUnits = increase ? currentUnits + 1 : currentUnits - 1;

        if (targetUnits < 0) {
            // As per requirements, we should not allow the rate to become negative.
            // We can throw or simply do nothing. Let's throw to make the caller aware.
            throw new IllegalArgumentException("Rate would become negative");
        }

        rewriteRangeToUnits60(start, end, targetUnits);
        normalizeAndValidate();
    }

    // --- Internal helpers (hour grid) ---

    private int indexOfStart(int startMinutes) {
        for (int i = 0; i < segments.size(); i++) {
            if (segments.get(i).getStartMinutes() == startMinutes) return i;
        }
        return -1;
    }

    private void setNextStart(int idx, int newStart) {
        if (idx + 1 < segments.size()) {
            int nextUnits = segments.get(idx + 1).getRateUnits();
            segments.set(idx + 1, new BasalSegment(newStart, nextUnits));
        } else {
            if (newStart != 1440) segments.add(new BasalSegment(newStart, 0)); // filler to 24:00
        }
    }

    private void insertOrReplaceChangePoint(int startMinutes, int rateUnits) {
        int pos = indexOfStart(startMinutes);
        if (pos >= 0) segments.set(pos, new BasalSegment(startMinutes, rateUnits));
        else {
            segments.add(new BasalSegment(startMinutes, rateUnits));
            segments.sort(Comparator.comparingInt(BasalSegment::getStartMinutes));
        }
    }

    private int unitsAtOriginal(List<BasalSegment> original, int minutes) {
        int idx = Collections.binarySearch(original, new BasalSegment(minutes, 0),
                Comparator.comparingInt(BasalSegment::getStartMinutes));
        if (idx >= 0) return original.get(idx).getRateUnits();
        int ip = -idx - 1;
        int pos = ip - 1;
        if (pos < 0) throw new IllegalStateException("Profile malformed");
        return original.get(pos).getRateUnits();
    }

    private int firstIndexAtOrAfter(List<BasalSegment> original, int boundary) {
        int idx = Collections.binarySearch(original, new BasalSegment(boundary, 0),
                Comparator.comparingInt(BasalSegment::getStartMinutes));
        return (idx >= 0) ? idx : (-idx - 1);
    }

    /** Append change point safely (hour grid), avoid duplicates & redundant points, never at 24:00. */
    private void appendChangePointUnique(List<BasalSegment> out, int start, int units) {
        if (start >= 1440) return; // never create change point at 24:00
        if (!isHourGrid(start)) throw new IllegalArgumentException("start must align to 60-minute grid");
        if (units < 0) throw new IllegalArgumentException("units must be >= 0");

        if (out.isEmpty()) {
            if (start != 0) throw new IllegalStateException("first change point must be at 00:00");
            out.add(new BasalSegment(start, units));
            return;
        }
        BasalSegment last = out.get(out.size() - 1);
        if (start == last.getStartMinutes()) {
            out.set(out.size() - 1, new BasalSegment(start, units));
            return;
        }
        if (last.getRateUnits() == units) return; // redundant
        out.add(new BasalSegment(start, units));
    }

    /** Hour-grid rewrite [start,end) to targetUnits without duplicate change points. */
    private void rewriteRangeToUnits60(int start, int end, int targetUnits) {
        if (!isHourGrid(start) || !isHourGrid(end))
            throw new IllegalArgumentException("range must align to 60-minute grid");
        if (start < 0 || start >= 1440 || end <= start || end > 1440)
            throw new IllegalArgumentException("invalid range");

        List<BasalSegment> original = new ArrayList<>(segments);
        original.sort(Comparator.comparingInt(BasalSegment::getStartMinutes));

        Integer unitsAtEnd = (end < 1440) ? unitsAtOriginal(original, end) : null;

        List<BasalSegment> rebuilt = new ArrayList<>();

        // copy head (< start)
        for (BasalSegment s : original) {
            if (s.getStartMinutes() < start) {
                if (rebuilt.isEmpty() && s.getStartMinutes() != 0)
                    throw new IllegalStateException("first change point must be at 0");
                appendChangePointUnique(rebuilt, s.getStartMinutes(), s.getRateUnits());
            } else break;
        }

        // set target at start (always)
        appendChangePointUnique(rebuilt, start, targetUnits);

        // skip original points in [start,end)
        int idxEnd = firstIndexAtOrAfter(original, end);

        // add boundary at end only if needed (and end<1440)
        if (end < 1440) {
            boolean hasOriginalAtEnd = (idxEnd < original.size()
                    && original.get(idxEnd).getStartMinutes() == end);
            if (!hasOriginalAtEnd && unitsAtEnd != null && unitsAtEnd != targetUnits) {
                appendChangePointUnique(rebuilt, end, unitsAtEnd);
            }
        }

        // copy tail (>= end)
        for (int i = idxEnd; i < original.size(); i++) {
            BasalSegment s = original.get(i);
            appendChangePointUnique(rebuilt, s.getStartMinutes(), s.getRateUnits());
        }

        segments.clear();
        segments.addAll(rebuilt);
    }
}
