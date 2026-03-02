// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Shared highway type constants used across TIGER review and surface analysis.
 */
public final class HighwayConstants {

    private HighwayConstants() {
        // utility class
    }

    /**
     * Highway types for TIGER review analysis.
     * Includes path/footway/cycleway/pedestrian since these can have tiger:reviewed tags.
     */
    public static final Set<String> TIGER_HIGHWAYS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "motorway", "motorway_link",
                    "trunk", "trunk_link",
                    "primary", "primary_link",
                    "secondary", "secondary_link",
                    "tertiary", "tertiary_link",
                    "unclassified", "residential",
                    "living_street", "service", "road",
                    "track",
                    "path", "footway", "cycleway", "pedestrian")));

    /**
     * Highway types for surface analysis (vehicular roads only).
     * Excludes path, footway, cycleway, pedestrian since surface inference
     * from connected roads is less meaningful for non-vehicular ways.
     */
    public static final Set<String> SURFACE_HIGHWAYS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "motorway", "motorway_link",
                    "trunk", "trunk_link",
                    "primary", "primary_link",
                    "secondary", "secondary_link",
                    "tertiary", "tertiary_link",
                    "unclassified", "residential",
                    "living_street", "service", "road",
                    "track")));
}
