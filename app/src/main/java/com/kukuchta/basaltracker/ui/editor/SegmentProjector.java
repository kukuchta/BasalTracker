package com.kukuchta.basaltracker.ui.editor;

import java.util.ArrayList;
import java.util.List;

/** Pure projector: 24 hourly units -> compressed list of UiSegments (runs). */
public final class SegmentProjector {
    private SegmentProjector() {}

    public static List<UiSegment> project(int[] unitsByHour, double accuracy) {
        if (unitsByHour == null || unitsByHour.length != 24)
            throw new IllegalArgumentException("24 hours required");

        List<UiSegment> out = new ArrayList<>();
        int start = 0;
        int currentUnits = unitsByHour[0];

        for (int h = 1; h < 24; h++) {
            if (unitsByHour[h] != currentUnits) {
                out.add(new UiSegment(start, h, currentUnits, currentUnits * accuracy));
                start = h;
                currentUnits = unitsByHour[h];
            }
        }
        out.add(new UiSegment(start, 24, currentUnits, currentUnits * accuracy));
        return out;
    }
}
