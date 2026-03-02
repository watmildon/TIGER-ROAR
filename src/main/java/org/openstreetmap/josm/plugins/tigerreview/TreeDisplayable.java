// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview;

import java.util.function.Supplier;

import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.data.osm.Way;

/**
 * Common interface for analysis results displayed in the side panel tree.
 * Implemented by both {@link TIGERReviewAnalyzer.ReviewResult} and
 * {@link SurfaceAnalyzer.SurfaceSuggestion}.
 */
interface TreeDisplayable {

    Way getWay();

    int getCode();

    String getMessage();

    String getGroupMessage();

    Supplier<Command> getFixSupplier();
}
