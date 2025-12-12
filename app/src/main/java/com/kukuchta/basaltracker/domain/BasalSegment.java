package com.kukuchta.basaltracker.domain;

public final class BasalSegment {
    private final int startMinutes; // [0..1439], multiple of 30
    private final int rateUnits;    // rate = rateUnits * accuracy

    public BasalSegment(int startMinutes, int rateUnits) {
        this.startMinutes = startMinutes;
        this.rateUnits = rateUnits;
    }

    public int getStartMinutes() { return startMinutes; }
    public int getRateUnits() { return rateUnits; }
}
