// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview;

/**
 * String distance utilities for fuzzy name matching.
 */
public final class StringDistance {

    private StringDistance() {
        // utility class
    }

    /**
     * Calculate the Levenshtein edit distance between two strings (case-insensitive).
     *
     * @param a First string
     * @param b Second string
     * @return The minimum number of single-character edits (insertions, deletions, substitutions)
     *         needed to change one string into the other
     */
    public static int levenshtein(String a, String b) {
        if (a == null || b == null) {
            return a == null && b == null ? 0 : (a == null ? b.length() : a.length());
        }

        String s = a.toLowerCase();
        String t = b.toLowerCase();

        int m = s.length();
        int n = t.length();

        // Use single-row optimization: only need previous and current rows
        int[] prev = new int[n + 1];
        int[] curr = new int[n + 1];

        for (int j = 0; j <= n; j++) {
            prev[j] = j;
        }

        for (int i = 1; i <= m; i++) {
            curr[0] = i;
            for (int j = 1; j <= n; j++) {
                int cost = s.charAt(i - 1) == t.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(
                        curr[j - 1] + 1,       // insertion
                        prev[j] + 1),           // deletion
                        prev[j - 1] + cost);    // substitution
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }

        return prev[n];
    }

    /**
     * Check if two strings are a fuzzy match: not an exact case-insensitive match,
     * but within a small Levenshtein distance relative to their length.
     *
     * <p>The threshold scales with string length:
     * <ul>
     *   <li>Strings up to 5 characters: max distance 1</li>
     *   <li>Strings 6-12 characters: max distance 2</li>
     *   <li>Longer strings: max distance 3</li>
     * </ul>
     *
     * @param a First string
     * @param b Second string
     * @return true if the strings are similar but not identical (case-insensitive)
     */
    public static boolean isFuzzyMatch(String a, String b) {
        if (a == null || b == null) {
            return false;
        }

        // Exact case-insensitive match is not a "fuzzy" match
        if (a.equalsIgnoreCase(b)) {
            return false;
        }

        int maxLen = Math.max(a.length(), b.length());
        int maxDistance;
        if (maxLen <= 5) {
            maxDistance = 1;
        } else if (maxLen <= 12) {
            maxDistance = 2;
        } else {
            maxDistance = 3;
        }

        return levenshtein(a, b) <= maxDistance;
    }
}
