package com.kukuchta.basaltracker.domain;

public final class BasalProfileDiff {
    private final double totalDifference;
    private final double maxDifference;

    public BasalProfileDiff(double totalDifference, double maxDifference) {
        this.totalDifference = totalDifference;
        this.maxDifference = maxDifference;
    }

    public double getTotalDifference() {
        return totalDifference;
    }

    public double getMaxDifference() {
        return maxDifference;
    }
}
