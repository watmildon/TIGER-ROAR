// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview.external;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.plugins.tigerreview.external.NadClient.NadAddress;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.Logging;

/**
 * Persistent on-disk storage for NAD address tiles.
 *
 * Each tile is stored as an NDJSON file (one JSON object per line) in the
 * JOSM cache directory under {@code tiger-roar/nad/}. The first line is
 * metadata (fetch timestamp); subsequent lines are address records.
 *
 * <p>Writes are atomic (write to temp file, then rename) to prevent corruption.
 * Eviction removes the oldest tiles when total cache size exceeds 100 MB.
 */
public class NadTileStore {

    /** Maximum cache size in bytes (100 MB) */
    private static final long MAX_CACHE_BYTES = 100L * 1024 * 1024;

    /** Eviction chunk size in bytes (~10 MB) */
    private static final long EVICTION_CHUNK_BYTES = 10L * 1024 * 1024;

    /** Tile time-to-live in milliseconds (30 days) */
    private static final long TILE_TTL_MS = 30L * 24 * 60 * 60 * 1000;

    /** Singleton instance */
    private static NadTileStore instance;

    /** Cache directory path */
    private final Path cacheDir;

    private NadTileStore(Path cacheDir) {
        this.cacheDir = cacheDir;
    }

    /**
     * Get the singleton instance, creating the cache directory if needed.
     */
    public static synchronized NadTileStore getInstance() {
        if (instance == null) {
            File josmCache = Config.getDirs().getCacheDirectory(true);
            Path dir = josmCache.toPath().resolve("tiger-roar").resolve("nad");
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                Logging.warn("Failed to create NAD tile cache directory: " + e.getMessage());
            }
            instance = new NadTileStore(dir);
        }
        return instance;
    }

    /**
     * Check if a tile exists on disk and is not expired.
     */
    public boolean hasFreshTile(NadTileKey tileKey) {
        Path file = cacheDir.resolve(tileKey.toFilename());
        if (!Files.exists(file)) {
            return false;
        }
        try {
            long lastModified = Files.getLastModifiedTime(file).toMillis();
            return (System.currentTimeMillis() - lastModified) < TILE_TTL_MS;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Read addresses from a cached tile file.
     *
     * @return list of addresses, or null if the file doesn't exist or is corrupt
     */
    public List<NadAddress> readTile(NadTileKey tileKey) {
        Path file = cacheDir.resolve(tileKey.toFilename());
        if (!Files.exists(file)) {
            return null;
        }

        List<NadAddress> addresses = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            // First line is metadata — skip it
            String metaLine = reader.readLine();
            if (metaLine == null) {
                return null;
            }

            String line;
            while ((line = reader.readLine()) != null) {
                NadAddress addr = parseAddressLine(line);
                if (addr != null) {
                    addresses.add(addr);
                }
            }
            return addresses;
        } catch (IOException e) {
            Logging.warn("Failed to read NAD tile " + tileKey.toFilename() + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Write addresses to a tile file atomically.
     */
    public void writeTile(NadTileKey tileKey, List<NadAddress> addresses) {
        Path target = cacheDir.resolve(tileKey.toFilename());
        Path tmp;
        try {
            tmp = Files.createTempFile(cacheDir, "tile_", ".tmp");
        } catch (IOException e) {
            Logging.warn("Failed to create temp file for NAD tile: " + e.getMessage());
            return;
        }

        try (BufferedWriter writer = Files.newBufferedWriter(tmp)) {
            // Metadata line
            writer.write("{\"fetchTime\":" + System.currentTimeMillis()
                    + ",\"tileKey\":\"" + tileKey.toFilename() + "\"}");
            writer.newLine();

            // Address lines
            for (NadAddress addr : addresses) {
                writer.write(formatAddressLine(addr));
                writer.newLine();
            }
        } catch (IOException e) {
            Logging.warn("Failed to write NAD tile " + tileKey.toFilename() + ": " + e.getMessage());
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best effort cleanup
            }
            return;
        }

        // Atomic rename
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Fall back to non-atomic move (Windows sometimes doesn't support atomic across volumes)
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e2) {
                Logging.warn("Failed to move NAD tile " + tileKey.toFilename() + ": " + e2.getMessage());
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // best effort cleanup
                }
            }
        } catch (IOException e) {
            Logging.warn("Failed to move NAD tile " + tileKey.toFilename() + ": " + e.getMessage());
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best effort cleanup
            }
        }
    }

    /**
     * Evict oldest tiles if total cache size exceeds the limit.
     * Removes tiles in ~10 MB chunks until under the limit.
     */
    public void evictIfNeeded() {
        try {
            long totalSize = 0;
            List<TileFileInfo> tileFiles = new ArrayList<>();

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(cacheDir, "tile_*.ndjson")) {
                for (Path path : stream) {
                    long size = Files.size(path);
                    long lastModified = Files.getLastModifiedTime(path).toMillis();
                    totalSize += size;
                    tileFiles.add(new TileFileInfo(path, size, lastModified));
                }
            }

            if (totalSize <= MAX_CACHE_BYTES) {
                return;
            }

            Logging.info("NAD tile cache size " + (totalSize / 1024 / 1024) + " MB exceeds limit, evicting oldest tiles");

            // Sort oldest first
            tileFiles.sort(Comparator.comparingLong(TileFileInfo::lastModified));

            long bytesFreed = 0;
            long targetFree = totalSize - MAX_CACHE_BYTES + EVICTION_CHUNK_BYTES;

            for (TileFileInfo info : tileFiles) {
                if (bytesFreed >= targetFree) {
                    break;
                }
                try {
                    Files.deleteIfExists(info.path());
                    bytesFreed += info.size();
                    Logging.debug("Evicted NAD tile: " + info.path().getFileName());
                } catch (IOException e) {
                    Logging.warn("Failed to evict NAD tile: " + e.getMessage());
                }
            }

            Logging.info("NAD tile cache eviction freed " + (bytesFreed / 1024) + " KB");

        } catch (IOException e) {
            Logging.warn("Failed to check NAD tile cache size: " + e.getMessage());
        }
    }

    /**
     * Delete a specific tile from disk.
     */
    public void deleteTile(NadTileKey tileKey) {
        try {
            Files.deleteIfExists(cacheDir.resolve(tileKey.toFilename()));
        } catch (IOException e) {
            Logging.warn("Failed to delete NAD tile " + tileKey.toFilename() + ": " + e.getMessage());
        }
    }

    /**
     * Format an address as a single NDJSON line.
     * Only stores street, lat, lon — the fields actually used by analysis.
     */
    private String formatAddressLine(NadAddress addr) {
        // Escape the street name for JSON (handle quotes and backslashes)
        String escaped = addr.street()
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
        return "{\"s\":\"" + escaped + "\""
                + ",\"lat\":" + addr.location().lat()
                + ",\"lon\":" + addr.location().lon() + "}";
    }

    /**
     * Parse an address from an NDJSON line.
     */
    private NadAddress parseAddressLine(String line) {
        if (line == null || line.isEmpty() || !line.startsWith("{")) {
            return null;
        }
        try {
            String street = extractJsonString(line, "s");
            if (street == null || street.isEmpty()) {
                return null;
            }

            Double lat = extractJsonNumber(line, "lat");
            Double lon = extractJsonNumber(line, "lon");
            if (lat == null || lon == null) {
                return null;
            }

            return new NadAddress(street, null, null, null, null, new LatLon(lat, lon));
        } catch (Exception e) {
            Logging.debug("Failed to parse NAD tile line: " + e.getMessage());
            return null;
        }
    }

    /**
     * Extract a string value from a JSON line.
     */
    private static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start == -1) {
            return null;
        }
        start += search.length();
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                sb.append(json.charAt(i + 1));
                i++;
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        return null; // unterminated string
    }

    /**
     * Extract a number value from a JSON line.
     */
    private static Double extractJsonNumber(String json, String key) {
        String search = "\"" + key + "\":";
        int start = json.indexOf(search);
        if (start == -1) {
            return null;
        }
        start += search.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end))
                || json.charAt(end) == '.' || json.charAt(end) == '-' || json.charAt(end) == 'E'
                || json.charAt(end) == 'e' || json.charAt(end) == '+')) {
            end++;
        }
        if (end == start) {
            return null;
        }
        try {
            return Double.parseDouble(json.substring(start, end));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record TileFileInfo(Path path, long size, long lastModified) {
    }
}
