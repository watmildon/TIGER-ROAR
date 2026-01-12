package org.openstreetmap.josm.plugins.tigerreview;

import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.validation.Severity;
import org.openstreetmap.josm.data.validation.Test;
import org.openstreetmap.josm.data.validation.TestError;

public class HighwayTest extends Test.TagTest {
    private static final int HIGHWAY_WARNING = 3100;

    public HighwayTest() {
        super("TIGER Review", "Flags objects with highway tags for review");
    }

    @Override
    public void check(OsmPrimitive p) {
        if (p.hasKey("highway")) {
            errors.add(TestError.builder(this, Severity.WARNING, HIGHWAY_WARNING)
                    .message("Highway object for TIGER review")
                    .primitives(p)
                    .build());
        }
    }
}
