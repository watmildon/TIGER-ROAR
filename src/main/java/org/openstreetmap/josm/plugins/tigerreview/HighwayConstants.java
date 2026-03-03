// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Shared constants for highway types and surface categories,
 * used across TIGER review and surface analysis.
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

    /**
     * Specific paved surface values (excludes the generic "paved" itself).
     * See https://wiki.openstreetmap.org/wiki/Key:surface#Paved
     */
    public static final Set<String> PAVED_SURFACES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "asphalt", "chipseal", "concrete",
                    "concrete:lanes", "concrete:plates",
                    "paving_stones", "paving_stones:lanes",
                    "grass_paver", "sett",
                    "unhewn_cobblestone", "cobblestone", "cobblestone:flattened",
                    "bricks", "metal", "metal_grid", "wood",
                    "stepping_stones", "rubber", "tiles",
                    "fibre_reinforced_polymer_grate")));

    /**
     * Specific unpaved surface values (excludes the generic "unpaved" itself).
     * See https://wiki.openstreetmap.org/wiki/Key:surface#Unpaved
     */
    public static final Set<String> UNPAVED_SURFACES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "compacted", "fine_gravel", "gravel", "shells",
                    "rock", "pebblestone", "ground", "dirt", "earth",
                    "grass", "mud", "sand", "woodchips",
                    "snow", "ice", "salt")));
}
