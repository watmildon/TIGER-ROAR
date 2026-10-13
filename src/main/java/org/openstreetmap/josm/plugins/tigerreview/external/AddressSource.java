// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview.external;

import java.io.IOException;
import java.util.Locale;

import org.openstreetmap.josm.plugins.tigerreview.external.NadClient.NadQueryResult;

/**
 * A remote source of address points for the NAD check, queried one
 * {@link NadTileKey} cell at a time.
 *
 * <p>Two kinds of source are supported, chosen by URL:
 * <ul>
 *   <li>A PMTiles archive of address point vector tiles (URL path ends in {@code .pmtiles},
 *       optionally followed by {@code #layer}) — read by {@link PmtilesClient}</li>
 *   <li>Anything else is treated as an ESRI ArcGIS REST FeatureServer layer with the NAD
 *       schema ({@code addr_street}, ...) — read by {@link NadClient}</li>
 * </ul>
 */
public interface AddressSource {

    /**
     * Fetch the addresses inside one cell.
     *
     * @param tileKey the cell to fetch
     * @return the addresses, or an error result
     */
    NadQueryResult queryTile(NadTileKey tileKey);

    /**
     * An identifier of the data currently behind the URL (such as an HTTP ETag), so cached
     * cells from an older release can be discarded. Null when the source cannot tell.
     *
     * @throws IOException if the source could not be reached
     */
    default String dataVersion() throws IOException {
        return null;
    }

    /**
     * Create the source for a URL.
     */
    static AddressSource forUrl(String url) {
        if (isPmtilesUrl(url)) {
            return new PmtilesClient(url);
        }
        return new NadClient(url);
    }

    /**
     * Whether the URL names a PMTiles archive: its path ends in {@code .pmtiles}, ignoring any
     * query string (such as a signed URL's) and {@code #layer} fragment.
     */
    static boolean isPmtilesUrl(String url) {
        if (url == null) {
            return false;
        }
        String path = url.trim();
        int hash = path.indexOf('#');
        if (hash >= 0) {
            path = path.substring(0, hash);
        }
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        return path.toLowerCase(Locale.ROOT).endsWith(".pmtiles");
    }
}
