package com.kukuchta.basaltracker.domain;

import static java.util.Collections.unmodifiableList;
import static java.util.Collections.unmodifiableMap;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class BasalProfile {
    private final long id;
    private final String name;
    private final double accuracy;   // U/h step
    private final ProfileOrigin origin;
    private final Long baseProfileId; // nullable
    private final java.util.Map<String, String> metadata;
    private final List<BasalSegment> segments; // sorted change points

    public BasalProfile(long id,
                        String name,
                        double accuracy,
                        ProfileOrigin origin,
                        Long baseProfileId,
                        java.util.Map<String, String> metadata,
                        List<BasalSegment> segments) {
        this.id = id;
        this.name = Objects.requireNonNull(name);
        if (accuracy <= 0.0) throw new IllegalArgumentException("accuracy must be > 0");
        this.accuracy = accuracy;
        this.origin = origin == null ? ProfileOrigin.USER_MODIFIED : origin;
        this.baseProfileId = baseProfileId;
        this.metadata = metadata != null ? new java.util.HashMap<>(metadata) : new java.util.HashMap<>();
        this.segments = new ArrayList<>(Objects.requireNonNull(segments));
        normalizeAndValidate();
    }

    public long getId() { return id; }
    public String getName() { return name; }
    public double getAccuracy() { return accuracy; }
    public ProfileOrigin getOrigin() { return origin; }
    public Long getBaseProfileId() { return baseProfileId; }
    public java.util.Map<String, String> getMetadata() { return unmodifiableMap(metadata); }
    public List<BasalSegment> getSegments() { return unmodifiableList(segments); }

    private static boolean isMultipleOf30(int minutes) { return minutes % 30 == 0; }

    private void normalizeAndValidate() {
        segments.sort(Comparator.comparingInt(BasalSegment::getStartMinutes));
        if (segments.isEmpty()) throw new IllegalStateException("Profile must have at least one segment");
        if (segments.get(0).getStartMinutes() != 0)
            throw new IllegalStateException("First segment must start at 0");

        if (segments.get(segments.size() - 1).getStartMinutes() >= 1440)
            throw new IllegalStateException("Last segment start must be < 1440");

        // merge consecutive with same rateUnits
        
        List<BasalSegment> merged = new ArrayList<>();
        Integer lastStart = null;
        Integer lastUnits = null;

        for (BasalSegment s : segments) {
            int start = s.getStartMinutes();
            int units = s.getRateUnits();
            if (!isMultipleOf30(start)) throw new IllegalStateException("startMinutes must be multiple of 30");
            if (units < 0) throw new IllegalStateException("rateUnits must be >= 0");

            // Drop exact duplicates by start
            if (lastStart != null && start == lastStart) {
                // Replace last with current (latest wins), then merge same-units below
                merged.set(merged.size() - 1, new BasalSegment(start, units));
                lastUnits = units;
                continue;
            }

            // Merge consecutive same-units
            if (lastUnits != null && units == lastUnits) {
                // Redundant change point; skip
                continue;
            }

            merged.add(new BasalSegment(start, units));
            lastStart = start;
            lastUnits = units;
        }

        segments.clear();
        segments.addAll(merged);

    }

    private int toUnits(double rate) {
        long units = Math.round(rate / accuracy);
        if (units < 0) throw new IllegalArgumentException("rate cannot be negative");
        return (int) units;
    }

    private double toRate(int units) { return units * accuracy; }

    public double getBasalRate(int minutesSinceMidnight) {
        if (minutesSinceMidnight < 0 || minutesSinceMidnight >= 1440)
            throw new IllegalArgumentException("minutesSinceMidnight must be in [0,1440)");
        int idx = Collections.binarySearch(
                segments,
                new BasalSegment(minutesSinceMidnight, 0),
                Comparator.comparingInt(BasalSegment::getStartMinutes));
        if (idx >= 0) {
            return toRate(segments.get(idx).getRateUnits());
        } else {
            int insertionPoint = -idx - 1;
            int pos = insertionPoint - 1;
            if (pos < 0) throw new IllegalStateException("Profile malformed (no segment for time)");
            return toRate(segments.get(pos).getRateUnits());
        }
    }

    public double[] toHalfHourArray() {
        double[] arr = new double[48];
        for (int i = 0; i < 48; i++) {
            arr[i] = getBasalRate(i * 30);
        }
        return arr;
    }

    public BasalProfileDiff diff(BasalProfile other) {
        double[] a1 = this.toHalfHourArray();
        double[] a2 = other.toHalfHourArray();
        double totalDiff = 0, maxDiff = 0;
        for (int i = 0; i < 48; i++) {
            double d = Math.abs(a1[i] - a2[i]);
            totalDiff += d;
            if (d > maxDiff) maxDiff = d;
        }
        return new BasalProfileDiff(totalDiff, maxDiff);
    }

    public BigDecimal getTotalDailyDose() {
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < segments.size(); i++) {
            BasalSegment s = segments.get(i);
            int start = s.getStartMinutes();
            int end = (i + 1 < segments.size()) ? segments.get(i + 1).getStartMinutes() : 1440;
            int minutes = end - start;
            if (minutes <= 0) throw new IllegalStateException("non-positive segment duration");
            BigDecimal hours = BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), 9, java.math.RoundingMode.UNNECESSARY);
            BigDecimal rate = BigDecimal.valueOf(toRate(s.getRateUnits()));
            total = total.add(rate.multiply(hours));
        }
        return total;
    }

    // === Editing operations ===

    /** Hourly adjust: first click equalizes to max/min; subsequent clicks move by ±accuracy (units). */
    public void adjustRateForHour(int hour, boolean increase) {
        if (hour < 0 || hour > 23) throw new IllegalArgumentException("hour 0..23");
        int start = hour * 60;
        int end = start + 60;

        final int[] maxUnits = {Integer.MIN_VALUE};
        final int[] minUnits = { Integer.MAX_VALUE };
        forEachSubsegment(start, end, (s, e, units) -> {
            if (units > maxUnits[0]) maxUnits[0] = units;
            if (units < minUnits[0]) minUnits[0] = units;
        });

        boolean allEqual = (maxUnits[0] == minUnits[0]);
        int targetUnits = allEqual ? (increase ? maxUnits[0] + 1 : maxUnits[0] - 1) : (increase ? maxUnits[0] : minUnits[0]);
        if (targetUnits < 0) throw new IllegalArgumentException("rate would become negative");

        rewriteRangeToUnits(start, end, targetUnits);
        normalizeAndValidate();
    }

    /** Update segment end time; adjusts next or inserts filler 0 (strict multiple of 30, > start, ≤ 1440). */
    public void updateSegmentEnd(int segmentStartMinutes, int newEndMinutes) {
        if (!isMultipleOf30(newEndMinutes)) throw new IllegalArgumentException("newEnd must be multiple of 30");
        if (newEndMinutes <= segmentStartMinutes || newEndMinutes > 1440)
            throw new IllegalArgumentException("newEnd must be within (start, 1440]");

        int idx = indexOfStart(segmentStartMinutes);
        if (idx < 0) throw new IllegalArgumentException("segment not found");

        int oldEnd = (idx + 1 < segments.size()) ? segments.get(idx + 1).getStartMinutes() : 1440;

        if (newEndMinutes < oldEnd) {
            setNextStart(idx, newEndMinutes);
        } else if (newEndMinutes > oldEnd) {
            insertSegmentIfMissing(oldEnd, 0); // filler to bridge oldEnd..newEnd at 0U/h
            setNextStart(idx, newEndMinutes);
        }
        normalizeAndValidate();
    }

    /** Update segment rate to a new accurate value (rounds to units). */
    public void updateSegmentRate(int segmentStartMinutes, double newRate) {
        int idx = indexOfStart(segmentStartMinutes);
        if (idx < 0) throw new IllegalArgumentException("segment not found");
        int units = toUnits(newRate);
        if (units < 0) throw new IllegalArgumentException("rate cannot be negative");
        BasalSegment old = segments.get(idx);
        segments.set(idx, new BasalSegment(old.getStartMinutes(), units));
        normalizeAndValidate();
    }

    // --- internal helpers ---

    private int indexOfStart(int startMinutes) {
        for (int i = 0; i < segments.size(); i++) {
            if (segments.get(i).getStartMinutes() == startMinutes) return i;
        }
        return -1;
    }

    private void setNextStart(int idx, int newStart) {
        if (idx + 1 < segments.size()) {
            BasalSegment next = segments.get(idx + 1);
            segments.set(idx + 1, new BasalSegment(newStart, next.getRateUnits()));
        } else {
            // last segment; ensure terminal coverage to 1440
            if (newStart != 1440) {
                segments.add(new BasalSegment(newStart, 0)); // filler to 1440
            }
        }
    }

    private void insertSegmentIfMissing(int startMinutes, int rateUnits) {
        int pos = indexOfStart(startMinutes);
        if (pos >= 0) {
            segments.set(pos, new BasalSegment(startMinutes, rateUnits));
        } else {
            segments.add(new BasalSegment(startMinutes, rateUnits));
            segments.sort(Comparator.comparingInt(BasalSegment::getStartMinutes));
        }
    }

    private int unitsAt(int minutes) {
        int idx = Collections.binarySearch(
                segments,
                new BasalSegment(minutes, 0),
                Comparator.comparingInt(BasalSegment::getStartMinutes));
        if (idx >= 0) {
            return segments.get(idx).getRateUnits();
        } else {
            int insertionPoint = -idx - 1;
            int pos = insertionPoint - 1;
            if (pos < 0) throw new IllegalStateException("Profile malformed");
            return segments.get(pos).getRateUnits();
        }
    }

    private interface SubsegmentVisitor { void visit(int s, int e, int units); }

    private void forEachSubsegment(int start, int end, SubsegmentVisitor v) {
        int i = Collections.binarySearch(
                segments,
                new BasalSegment(start, 0),
                Comparator.comparingInt(BasalSegment::getStartMinutes));
        int idx = (i >= 0) ? i : (-i - 2);
        if (idx < 0) idx = 0;

        while (idx < segments.size()) {
            int s = Math.max(start, segments.get(idx).getStartMinutes());
            int nextStart = (idx + 1 < segments.size()) ? segments.get(idx + 1).getStartMinutes() : 1440;
            int e = Math.min(end, nextStart);
            if (s >= e) break;
            v.visit(s, e, segments.get(idx).getRateUnits());
            if (nextStart >= end) break;
            idx++;
        }
    }

    private void rewriteRangeToUnits(int start, int end, int targetUnits) {
        if (!isMultipleOf30(start) || !isMultipleOf30(end))
            throw new IllegalArgumentException("hour range must align to 30-minute grid");

        insertSegmentIfMissing(start, unitsAt(start));
        insertSegmentIfMissing(end, unitsAt(end));

        List<BasalSegment> kept = new ArrayList<>();
        for (BasalSegment s : segments) {
            int sm = s.getStartMinutes();
            if (sm <= start || sm >= end) {
                kept.add(s);
            }
        }
        kept.add(new BasalSegment(start, targetUnits));
        kept.add(new BasalSegment(end, unitsAt(end)));
        kept.sort(Comparator.comparingInt(BasalSegment::getStartMinutes));
        segments.clear();
        segments.addAll(kept);
    }

    private void appendChangePointUnique(List<BasalSegment> out, int start, int units) {
        if (start >= 1440) return; // never create a change point at 24:00
        if (!isMultipleOf30(start)) throw new IllegalArgumentException("start must align to 30-minute grid");
        if (units < 0) throw new IllegalArgumentException("units must be >= 0");

        if (out.isEmpty()) {
            // First point in the day must be at 0
            if (start != 0) throw new IllegalStateException("first change point must be at 00:00");
            out.add(new BasalSegment(start, units));
            return;
        }

        BasalSegment last = out.get(out.size() - 1);
        int lastStart = last.getStartMinutes();
        int lastUnits = last.getRateUnits();

        if (start == lastStart) {
            // Replace the last change point with new units
            out.set(out.size() - 1, new BasalSegment(start, units));
            return;
        }

        if (lastUnits == units) {
            // No change at this time (redundant), skip
            return;
        }

        out.add(new BasalSegment(start, units));
    }

    private int unitsAtOriginal(List<BasalSegment> original, int minutes) {
        int idx = Collections.binarySearch(
                original,
                new BasalSegment(minutes, 0),
                Comparator.comparingInt(BasalSegment::getStartMinutes));
        if (idx >= 0) return original.get(idx).getRateUnits();
        int insertionPoint = -idx - 1;
        int pos = insertionPoint - 1;
        if (pos < 0) throw new IllegalStateException("Profile malformed (no segment for time)");
        return original.get(pos).getRateUnits();
    }


    private int firstIndexAtOrAfter(List<BasalSegment> original, int boundary) {
        int idx = Collections.binarySearch(
                original, new BasalSegment(boundary, 0),
                Comparator.comparingInt(BasalSegment::getStartMinutes));
        return (idx >= 0) ? idx : (-idx - 1);
    }

    private void rewriteRangeToUnits(int start, int end, int targetUnits) {
        if (!isMultipleOf30(start) || !isMultipleOf30(end))
            throw new IllegalArgumentException("hour range must align to 30-minute grid");
        if (start < 0 || start >= 1440 || end <= start || end > 1440)
            throw new IllegalArgumentException("invalid range");

        // Work off a snapshot of the ORIGINAL segments
        List<BasalSegment> original = new ArrayList<>(segments);
        original.sort(Comparator.comparingInt(BasalSegment::getStartMinutes));

        // Compute boundary units from ORIGINAL
        int unitsAtStart = unitsAtOriginal(original, start);
        Integer unitsAtEnd = (end < 1440) ? unitsAtOriginal(original, end) : null;

        // Build the new list
        List<BasalSegment> rebuilt = new ArrayList<>();

        // 1) Copy all change points STRICTLY before 'start'
        for (BasalSegment s : original) {
            if (s.getStartMinutes() < start) {
                if (rebuilt.isEmpty() && s.getStartMinutes() != 0)
                    throw new IllegalStateException("first change point must start at 0");
                // Avoid redundant change points while copying
                appendChangePointUnique(rebuilt, s.getStartMinutes(), s.getRateUnits());
            } else {
                break;
            }
        }

        // 2) At 'start', we ALWAYS set the targetUnits so the segment inside hour is defined
        // If start==0, this guarantees the first change point exists
        appendChangePointUnique(rebuilt, start, targetUnits);

        // 3) Skip all original points in [start, end)
        int idxEnd = firstIndexAtOrAfter(original, end);

        // 4) At 'end', decide whether an explicit change point is needed
        if (end < 1440) {
            boolean thereIsOriginalPointAtEnd = (idxEnd < original.size()
                    && original.get(idxEnd).getStartMinutes() == end);

            if (!thereIsOriginalPointAtEnd) {
                // No original change point at 'end'; add boundary only if dose after end differs
                if (unitsAtEnd != null && unitsAtEnd != targetUnits) {
                    appendChangePointUnique(rebuilt, end, unitsAtEnd);
                }
            } else {
                // There is an original point at 'end': DO NOT add our own (avoid duplicates).
                // The tail copy below will include it and switch to unitsAtEnd as needed.
            }
        }
        // If end == 1440: never add a change point.

        // 5) Copy tail (all change points at or after 'end')
        for (int i = idxEnd; i < original.size(); i++) {
            BasalSegment s = original.get(i);
            // If the first tail point happens to be 'end' and we've already added a boundary, avoid duplication
            appendChangePointUnique(rebuilt, s.getStartMinutes(), s.getRateUnits());
        }

        // 6) Commit
        segments.clear();
        segments.addAll(rebuilt);
    }
}
