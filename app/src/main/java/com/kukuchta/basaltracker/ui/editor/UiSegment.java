package com.kukuchta.basaltracker.ui.editor;

/** UI-facing compressed segment: contiguous run of equal hourly units. */
public final class UiSegment {
    public final int startHour;           // inclusive, 0..23
    public final int endHourExclusive;    // exclusive, 1..24, > startHour
    public final int units;               // integer units for the whole run
    public final double rateUh;           // derived = units * accuracy

    public UiSegment(int startHour, int endHourExclusive, int units, double rateUh) {
        this.startHour = startHour;
        this.endHourExclusive = endHourExclusive;
        this.units = units;
        this.rateUh = rateUh;
    }
}
