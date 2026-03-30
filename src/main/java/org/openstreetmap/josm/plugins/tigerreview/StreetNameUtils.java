// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Street name expansion and matching utilities for US addresses.
 *
 * <p>Expands common abbreviations found in TIGER/NAD data:
 * directional prefixes/suffixes (N→North), street types (Ave→Avenue),
 * and special patterns (St→Saint, CR→County Road).
 *
 * <p>Abbreviation tables ported from OSM-address-parser project.
 */
public final class StreetNameUtils {

    private StreetNameUtils() {
        // utility class
    }

    // -- Directional abbreviations --

    private static final Map<String, String> DIRECTIONAL_EXPAND = new HashMap<>();
    static {
        DIRECTIONAL_EXPAND.put("n", "North");
        DIRECTIONAL_EXPAND.put("s", "South");
        DIRECTIONAL_EXPAND.put("e", "East");
        DIRECTIONAL_EXPAND.put("w", "West");
        DIRECTIONAL_EXPAND.put("ne", "Northeast");
        DIRECTIONAL_EXPAND.put("nw", "Northwest");
        DIRECTIONAL_EXPAND.put("se", "Southeast");
        DIRECTIONAL_EXPAND.put("sw", "Southwest");
    }

    // -- Street type abbreviations (USPS canonical) --

    private static final Map<String, String> STREET_TYPE_EXPAND = new HashMap<>();
    static {
        STREET_TYPE_EXPAND.put("aly", "Alley");
        STREET_TYPE_EXPAND.put("anx", "Annex");
        STREET_TYPE_EXPAND.put("arc", "Arcade");
        STREET_TYPE_EXPAND.put("ave", "Avenue");
        STREET_TYPE_EXPAND.put("byu", "Bayou");
        STREET_TYPE_EXPAND.put("bch", "Beach");
        STREET_TYPE_EXPAND.put("bnd", "Bend");
        STREET_TYPE_EXPAND.put("blf", "Bluff");
        STREET_TYPE_EXPAND.put("blfs", "Bluffs");
        STREET_TYPE_EXPAND.put("btm", "Bottom");
        STREET_TYPE_EXPAND.put("blvd", "Boulevard");
        STREET_TYPE_EXPAND.put("br", "Branch");
        STREET_TYPE_EXPAND.put("brg", "Bridge");
        STREET_TYPE_EXPAND.put("brk", "Brook");
        STREET_TYPE_EXPAND.put("brks", "Brooks");
        STREET_TYPE_EXPAND.put("bg", "Burg");
        STREET_TYPE_EXPAND.put("bgs", "Burgs");
        STREET_TYPE_EXPAND.put("byp", "Bypass");
        STREET_TYPE_EXPAND.put("cp", "Camp");
        STREET_TYPE_EXPAND.put("cyn", "Canyon");
        STREET_TYPE_EXPAND.put("cpe", "Cape");
        STREET_TYPE_EXPAND.put("cswy", "Causeway");
        STREET_TYPE_EXPAND.put("ctr", "Center");
        STREET_TYPE_EXPAND.put("ctrs", "Centers");
        STREET_TYPE_EXPAND.put("cir", "Circle");
        STREET_TYPE_EXPAND.put("cirs", "Circles");
        STREET_TYPE_EXPAND.put("clf", "Cliff");
        STREET_TYPE_EXPAND.put("clfs", "Cliffs");
        STREET_TYPE_EXPAND.put("clb", "Club");
        STREET_TYPE_EXPAND.put("cmn", "Common");
        STREET_TYPE_EXPAND.put("cmns", "Commons");
        STREET_TYPE_EXPAND.put("cor", "Corner");
        STREET_TYPE_EXPAND.put("cors", "Corners");
        STREET_TYPE_EXPAND.put("crse", "Course");
        STREET_TYPE_EXPAND.put("ct", "Court");
        STREET_TYPE_EXPAND.put("cts", "Courts");
        STREET_TYPE_EXPAND.put("cv", "Cove");
        STREET_TYPE_EXPAND.put("cvs", "Coves");
        STREET_TYPE_EXPAND.put("crk", "Creek");
        STREET_TYPE_EXPAND.put("cres", "Crescent");
        STREET_TYPE_EXPAND.put("crst", "Crest");
        STREET_TYPE_EXPAND.put("xing", "Crossing");
        STREET_TYPE_EXPAND.put("xrd", "Crossroad");
        STREET_TYPE_EXPAND.put("xrds", "Crossroads");
        STREET_TYPE_EXPAND.put("curv", "Curve");
        STREET_TYPE_EXPAND.put("dl", "Dale");
        STREET_TYPE_EXPAND.put("dm", "Dam");
        STREET_TYPE_EXPAND.put("dv", "Divide");
        STREET_TYPE_EXPAND.put("dr", "Drive");
        STREET_TYPE_EXPAND.put("drs", "Drives");
        STREET_TYPE_EXPAND.put("est", "Estate");
        STREET_TYPE_EXPAND.put("ests", "Estates");
        STREET_TYPE_EXPAND.put("expy", "Expressway");
        STREET_TYPE_EXPAND.put("ext", "Extension");
        STREET_TYPE_EXPAND.put("exts", "Extensions");
        STREET_TYPE_EXPAND.put("fall", "Fall");
        STREET_TYPE_EXPAND.put("fls", "Falls");
        STREET_TYPE_EXPAND.put("fry", "Ferry");
        STREET_TYPE_EXPAND.put("fld", "Field");
        STREET_TYPE_EXPAND.put("flds", "Fields");
        STREET_TYPE_EXPAND.put("flt", "Flat");
        STREET_TYPE_EXPAND.put("flts", "Flats");
        STREET_TYPE_EXPAND.put("frd", "Ford");
        STREET_TYPE_EXPAND.put("frds", "Fords");
        STREET_TYPE_EXPAND.put("frst", "Forest");
        STREET_TYPE_EXPAND.put("frg", "Forge");
        STREET_TYPE_EXPAND.put("frgs", "Forges");
        STREET_TYPE_EXPAND.put("frk", "Fork");
        STREET_TYPE_EXPAND.put("frks", "Forks");
        STREET_TYPE_EXPAND.put("ft", "Fort");
        STREET_TYPE_EXPAND.put("fwy", "Freeway");
        STREET_TYPE_EXPAND.put("gdn", "Garden");
        STREET_TYPE_EXPAND.put("gdns", "Gardens");
        STREET_TYPE_EXPAND.put("gtwy", "Gateway");
        STREET_TYPE_EXPAND.put("gln", "Glen");
        STREET_TYPE_EXPAND.put("glns", "Glens");
        STREET_TYPE_EXPAND.put("grn", "Green");
        STREET_TYPE_EXPAND.put("grns", "Greens");
        STREET_TYPE_EXPAND.put("grv", "Grove");
        STREET_TYPE_EXPAND.put("grvs", "Groves");
        STREET_TYPE_EXPAND.put("hbr", "Harbor");
        STREET_TYPE_EXPAND.put("hbrs", "Harbors");
        STREET_TYPE_EXPAND.put("hvn", "Haven");
        STREET_TYPE_EXPAND.put("hts", "Heights");
        STREET_TYPE_EXPAND.put("hwy", "Highway");
        STREET_TYPE_EXPAND.put("hl", "Hill");
        STREET_TYPE_EXPAND.put("hls", "Hills");
        STREET_TYPE_EXPAND.put("holw", "Hollow");
        STREET_TYPE_EXPAND.put("inlt", "Inlet");
        STREET_TYPE_EXPAND.put("is", "Island");
        STREET_TYPE_EXPAND.put("iss", "Islands");
        STREET_TYPE_EXPAND.put("isle", "Isle");
        STREET_TYPE_EXPAND.put("jct", "Junction");
        STREET_TYPE_EXPAND.put("jcts", "Junctions");
        STREET_TYPE_EXPAND.put("ky", "Key");
        STREET_TYPE_EXPAND.put("kys", "Keys");
        STREET_TYPE_EXPAND.put("knl", "Knoll");
        STREET_TYPE_EXPAND.put("knls", "Knolls");
        STREET_TYPE_EXPAND.put("lk", "Lake");
        STREET_TYPE_EXPAND.put("lks", "Lakes");
        STREET_TYPE_EXPAND.put("land", "Land");
        STREET_TYPE_EXPAND.put("lndg", "Landing");
        STREET_TYPE_EXPAND.put("ln", "Lane");
        STREET_TYPE_EXPAND.put("lgt", "Light");
        STREET_TYPE_EXPAND.put("lgts", "Lights");
        STREET_TYPE_EXPAND.put("lf", "Loaf");
        STREET_TYPE_EXPAND.put("lck", "Lock");
        STREET_TYPE_EXPAND.put("lcks", "Locks");
        STREET_TYPE_EXPAND.put("ldg", "Lodge");
        STREET_TYPE_EXPAND.put("loop", "Loop");
        STREET_TYPE_EXPAND.put("mall", "Mall");
        STREET_TYPE_EXPAND.put("mnr", "Manor");
        STREET_TYPE_EXPAND.put("mnrs", "Manors");
        STREET_TYPE_EXPAND.put("mdw", "Meadow");
        STREET_TYPE_EXPAND.put("mdws", "Meadows");
        STREET_TYPE_EXPAND.put("mews", "Mews");
        STREET_TYPE_EXPAND.put("ml", "Mill");
        STREET_TYPE_EXPAND.put("mls", "Mills");
        STREET_TYPE_EXPAND.put("msn", "Mission");
        STREET_TYPE_EXPAND.put("mtwy", "Motorway");
        STREET_TYPE_EXPAND.put("mt", "Mount");
        STREET_TYPE_EXPAND.put("mtn", "Mountain");
        STREET_TYPE_EXPAND.put("mtns", "Mountains");
        STREET_TYPE_EXPAND.put("nck", "Neck");
        STREET_TYPE_EXPAND.put("orch", "Orchard");
        STREET_TYPE_EXPAND.put("oval", "Oval");
        STREET_TYPE_EXPAND.put("opas", "Overpass");
        STREET_TYPE_EXPAND.put("park", "Park");
        STREET_TYPE_EXPAND.put("pkwy", "Parkway");
        STREET_TYPE_EXPAND.put("pass", "Pass");
        STREET_TYPE_EXPAND.put("psge", "Passage");
        STREET_TYPE_EXPAND.put("path", "Path");
        STREET_TYPE_EXPAND.put("pike", "Pike");
        STREET_TYPE_EXPAND.put("pne", "Pine");
        STREET_TYPE_EXPAND.put("pnes", "Pines");
        STREET_TYPE_EXPAND.put("pl", "Place");
        STREET_TYPE_EXPAND.put("pln", "Plain");
        STREET_TYPE_EXPAND.put("plns", "Plains");
        STREET_TYPE_EXPAND.put("plz", "Plaza");
        STREET_TYPE_EXPAND.put("pt", "Point");
        STREET_TYPE_EXPAND.put("pts", "Points");
        STREET_TYPE_EXPAND.put("prt", "Port");
        STREET_TYPE_EXPAND.put("prts", "Ports");
        STREET_TYPE_EXPAND.put("pr", "Prairie");
        STREET_TYPE_EXPAND.put("radl", "Radial");
        STREET_TYPE_EXPAND.put("rnch", "Ranch");
        STREET_TYPE_EXPAND.put("rpd", "Rapid");
        STREET_TYPE_EXPAND.put("rpds", "Rapids");
        STREET_TYPE_EXPAND.put("rst", "Rest");
        STREET_TYPE_EXPAND.put("rdg", "Ridge");
        STREET_TYPE_EXPAND.put("rdgs", "Ridges");
        STREET_TYPE_EXPAND.put("riv", "River");
        STREET_TYPE_EXPAND.put("rd", "Road");
        STREET_TYPE_EXPAND.put("rds", "Roads");
        STREET_TYPE_EXPAND.put("rte", "Route");
        STREET_TYPE_EXPAND.put("row", "Row");
        STREET_TYPE_EXPAND.put("rue", "Rue");
        STREET_TYPE_EXPAND.put("run", "Run");
        STREET_TYPE_EXPAND.put("shl", "Shoal");
        STREET_TYPE_EXPAND.put("shls", "Shoals");
        STREET_TYPE_EXPAND.put("shr", "Shore");
        STREET_TYPE_EXPAND.put("shrs", "Shores");
        STREET_TYPE_EXPAND.put("skwy", "Skyway");
        STREET_TYPE_EXPAND.put("spg", "Spring");
        STREET_TYPE_EXPAND.put("spgs", "Springs");
        STREET_TYPE_EXPAND.put("spur", "Spur");
        STREET_TYPE_EXPAND.put("sq", "Square");
        STREET_TYPE_EXPAND.put("sqs", "Squares");
        STREET_TYPE_EXPAND.put("sta", "Station");
        STREET_TYPE_EXPAND.put("stra", "Stravenue");
        STREET_TYPE_EXPAND.put("strm", "Stream");
        STREET_TYPE_EXPAND.put("st", "Street");
        STREET_TYPE_EXPAND.put("sts", "Streets");
        STREET_TYPE_EXPAND.put("smt", "Summit");
        STREET_TYPE_EXPAND.put("ter", "Terrace");
        STREET_TYPE_EXPAND.put("trwy", "Throughway");
        STREET_TYPE_EXPAND.put("tpke", "Turnpike");
        STREET_TYPE_EXPAND.put("trak", "Track");
        STREET_TYPE_EXPAND.put("trce", "Trace");
        STREET_TYPE_EXPAND.put("trfy", "Trafficway");
        STREET_TYPE_EXPAND.put("trl", "Trail");
        STREET_TYPE_EXPAND.put("tunl", "Tunnel");
        STREET_TYPE_EXPAND.put("un", "Union");
        STREET_TYPE_EXPAND.put("uns", "Unions");
        STREET_TYPE_EXPAND.put("upas", "Underpass");
        STREET_TYPE_EXPAND.put("vly", "Valley");
        STREET_TYPE_EXPAND.put("vlys", "Valleys");
        STREET_TYPE_EXPAND.put("via", "Viaduct");
        STREET_TYPE_EXPAND.put("vw", "View");
        STREET_TYPE_EXPAND.put("vws", "Views");
        STREET_TYPE_EXPAND.put("vlg", "Village");
        STREET_TYPE_EXPAND.put("vlgs", "Villages");
        STREET_TYPE_EXPAND.put("vl", "Ville");
        STREET_TYPE_EXPAND.put("vis", "Vista");
        STREET_TYPE_EXPAND.put("walk", "Walk");
        STREET_TYPE_EXPAND.put("wall", "Wall");
        STREET_TYPE_EXPAND.put("way", "Way");
        STREET_TYPE_EXPAND.put("wl", "Well");
        STREET_TYPE_EXPAND.put("wls", "Wells");
    }

    // Reverse maps: full word → abbreviation (for matching expanded against abbreviated)
    private static final Map<String, String> DIRECTIONAL_CONTRACT = new HashMap<>();
    private static final Map<String, String> STREET_TYPE_CONTRACT = new HashMap<>();
    static {
        for (Map.Entry<String, String> e : DIRECTIONAL_EXPAND.entrySet()) {
            DIRECTIONAL_CONTRACT.put(e.getValue().toLowerCase(), e.getKey());
        }
        for (Map.Entry<String, String> e : STREET_TYPE_EXPAND.entrySet()) {
            STREET_TYPE_CONTRACT.put(e.getValue().toLowerCase(), e.getKey());
        }
    }

    // -- Saint name expansion --

    private static final Set<String> SAINT_NAMES = Set.of(
            "andrew", "andrews", "anne", "ann", "anthony", "augustine", "bernard",
            "catherine", "catherines", "charles", "christopher", "claire", "clare", "clair",
            "cloud", "david", "edward", "edwards", "elmo", "francis", "george",
            "helena", "helens", "james", "john", "johns", "joseph", "lawrence",
            "louis", "luke", "margaret", "mark", "marks", "martin", "mary", "marys",
            "michael", "nicholas", "olaf", "patrick", "paul", "peter", "rose",
            "simon", "stephen", "thomas", "vincent"
    );

    /** Matches "St" or "St." followed by a space and a name at the start of a string. */
    private static final Pattern SAINT_PATTERN = Pattern.compile(
            "^St\\.?\\s+(\\S+)", Pattern.CASE_INSENSITIVE);

    /** Matches "CR" followed by space and digits. */
    private static final Pattern COUNTY_ROAD_PATTERN = Pattern.compile(
            "^CR(?=\\s+\\d)", Pattern.CASE_INSENSITIVE);

    /** Matches "Fs Road". */
    private static final Pattern FS_ROAD_PATTERN = Pattern.compile(
            "^Fs Road\\b", Pattern.CASE_INSENSITIVE);

    // -- Ordinal number normalization (written-out → numeric) --

    private static final Map<String, String> ORDINAL_WORDS = new HashMap<>();
    static {
        ORDINAL_WORDS.put("first", "1st");
        ORDINAL_WORDS.put("second", "2nd");
        ORDINAL_WORDS.put("third", "3rd");
        ORDINAL_WORDS.put("fourth", "4th");
        ORDINAL_WORDS.put("fifth", "5th");
        ORDINAL_WORDS.put("sixth", "6th");
        ORDINAL_WORDS.put("seventh", "7th");
        ORDINAL_WORDS.put("eighth", "8th");
        ORDINAL_WORDS.put("ninth", "9th");
        ORDINAL_WORDS.put("tenth", "10th");
        ORDINAL_WORDS.put("eleventh", "11th");
        ORDINAL_WORDS.put("twelfth", "12th");
        ORDINAL_WORDS.put("thirteenth", "13th");
        ORDINAL_WORDS.put("fourteenth", "14th");
        ORDINAL_WORDS.put("fifteenth", "15th");
        ORDINAL_WORDS.put("sixteenth", "16th");
        ORDINAL_WORDS.put("seventeenth", "17th");
        ORDINAL_WORDS.put("eighteenth", "18th");
        ORDINAL_WORDS.put("nineteenth", "19th");
        ORDINAL_WORDS.put("twentieth", "20th");
    }

    // -- Directional full words (for detecting directional-only differences) --

    private static final Set<String> DIRECTIONAL_WORDS = Set.of(
            "north", "south", "east", "west",
            "northeast", "northwest", "southeast", "southwest");

    /**
     * Foreign language articles and prepositions that should be lowercased in
     * street names during expansion. NAD and address data commonly capitalizes
     * these (e.g., "De Las" instead of "de las").
     *
     * <p>Sourced from JOSM validator rules (NameTagCapitalization.validator.mapcss).
     */
    private static final Set<String> LOWERCASE_ARTICLES = Set.of(
            // Spanish/Portuguese/French/Italian
            "del", "de", "di", "du", "la", "las", "los", "el",
            // English prepositions and conjunctions
            "of", "on", "for", "a", "an", "the", "at", "to", "in", "via", "by",
            "or", "and", "but");

    /**
     * Expand all abbreviations in a street name.
     *
     * <p>Handles directional prefixes/suffixes, street types, saint names,
     * county roads, and forest service roads.
     *
     * @param name The street name (e.g., "S Maryland Ave")
     * @return The expanded name (e.g., "South Maryland Avenue"), or the original if no expansions apply
     */
    public static String expand(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }

        String[] words = name.split("\\s+");
        if (words.length == 0) {
            return name;
        }

        // Expand directional prefix (first word)
        String firstLower = words[0].toLowerCase();
        if (words.length > 1 && DIRECTIONAL_EXPAND.containsKey(firstLower)) {
            // Guard against "E Street" — don't expand "E" when the rest is just a street type
            boolean isEStreet = "e".equals(firstLower) && words.length == 2
                    && STREET_TYPE_EXPAND.containsKey(words[1].toLowerCase());
            if (!isEStreet) {
                words[0] = DIRECTIONAL_EXPAND.get(firstLower);
            }
        }

        // Expand directional suffix (last word)
        String lastLower = words[words.length - 1].toLowerCase();
        if (words.length > 1 && DIRECTIONAL_EXPAND.containsKey(lastLower)) {
            words[words.length - 1] = DIRECTIONAL_EXPAND.get(lastLower);
        }

        // Expand street type (last word, or second-to-last if last is a directional)
        int typeIndex = words.length - 1;
        if (words.length > 2 && DIRECTIONAL_EXPAND.containsKey(words[typeIndex].toLowerCase())
                || DIRECTIONAL_CONTRACT.containsKey(words[typeIndex].toLowerCase())) {
            // Last word is a directional suffix; type is second-to-last
            typeIndex = words.length - 2;
        }
        String typeLower = words[typeIndex].toLowerCase();
        // Remove trailing period from abbreviations like "Ave."
        String typeClean = typeLower.endsWith(".") ? typeLower.substring(0, typeLower.length() - 1) : typeLower;
        if (STREET_TYPE_EXPAND.containsKey(typeClean)) {
            words[typeIndex] = STREET_TYPE_EXPAND.get(typeClean);
        }

        // Normalize written-out ordinals to numeric form (e.g., "First" → "1st").
        // NAD/address data sometimes uses written-out ordinals while OSM uses numeric.
        for (int i = 0; i < words.length; i++) {
            String ordinal = ORDINAL_WORDS.get(words[i].toLowerCase());
            if (ordinal != null) {
                words[i] = ordinal;
            }
        }

        // Normalize foreign articles/prepositions to lowercase (interior words only).
        // NAD data commonly capitalizes these: "Casa Del Mar" → "Casa del Mar".
        // Skip the first word (it's the start of the name and may legitimately be capitalized,
        // e.g., "La Jolla Boulevard" should keep "La" capitalized).
        for (int i = 1; i < words.length; i++) {
            if (LOWERCASE_ARTICLES.contains(words[i].toLowerCase())) {
                words[i] = words[i].toLowerCase();
            }
        }

        String result = String.join(" ", words);

        // Expand saint names: "St Catherine" → "Saint Catherine"
        Matcher saintMatcher = SAINT_PATTERN.matcher(result);
        if (saintMatcher.find() && SAINT_NAMES.contains(saintMatcher.group(1).toLowerCase())) {
            result = result.substring(0, saintMatcher.start())
                    + "Saint " + saintMatcher.group(1)
                    + result.substring(saintMatcher.end());
        }

        // Expand "CR 123" → "County Road 123"
        result = COUNTY_ROAD_PATTERN.matcher(result).replaceFirst("County Road");

        // Expand "Fs Road" → "Forest Service Road"
        result = FS_ROAD_PATTERN.matcher(result).replaceFirst("Forest Service Road");

        return result;
    }

    /**
     * Strip apostrophes from a string. Used to normalize names for comparison,
     * since NAD/address data commonly strips possessive apostrophes
     * (e.g., "Mary's Place" stored as "Marys Place").
     *
     * @param name The name to strip
     * @return The name with all apostrophes and right single quotation marks removed
     */
    public static String stripApostrophes(String name) {
        if (name == null) {
            return null;
        }
        // Strip ASCII apostrophe and Unicode right single quotation mark (')
        return name.replace("'", "").replace("\u2019", "");
    }

    /**
     * Check if two expanded street names match, allowing for apostrophe differences.
     *
     * <p>First tries exact case-insensitive match, then tries again after stripping
     * apostrophes from both sides. This handles NAD/address data that commonly
     * strips possessive apostrophes (e.g., "Mary's Place" vs "Marys Place").
     *
     * @param expandedA First expanded street name
     * @param expandedB Second expanded street name
     * @return true if the names match (with or without apostrophes)
     */
    public static boolean expandedNamesMatch(String expandedA, String expandedB) {
        if (expandedA == null || expandedB == null) {
            return false;
        }
        if (expandedA.equalsIgnoreCase(expandedB)) {
            return true;
        }
        // Try again with apostrophes stripped
        return stripApostrophes(expandedA).equalsIgnoreCase(stripApostrophes(expandedB));
    }

    /**
     * Check if two street names match, considering abbreviation expansion.
     *
     * <p>Returns true if either the original or expanded forms of the names match
     * (case-insensitive). Also tolerates apostrophe differences (e.g.,
     * "Mary's Place" matches "Marys Place"). This handles cases like:
     * <ul>
     *   <li>"S Maryland Ave" matching "South Maryland Avenue"</li>
     *   <li>"St. Catherine St" matching "Saint Catherine Street"</li>
     *   <li>"CR 5" matching "County Road 5"</li>
     *   <li>"Mary's Place" matching "Marys Place"</li>
     * </ul>
     *
     * @param a First street name
     * @param b Second street name
     * @return true if the names match exactly or after expansion
     */
    public static boolean namesMatch(String a, String b) {
        if (a == null || b == null) {
            return false;
        }

        // Fast path: exact case-insensitive match
        if (a.equalsIgnoreCase(b)) {
            return true;
        }

        // Expand both and compare (with apostrophe tolerance)
        return expandedNamesMatch(expand(a), expand(b));
    }

    /**
     * Check if the only difference between two expanded street names is a directional
     * prefix or suffix (North, South, East, West, etc.). Used to categorize name
     * suggestions as "directional upgrades" for easier review.
     *
     * <p>Examples:
     * <ul>
     *   <li>"Mike Avenue" vs "North Mike Avenue" → true (prefix added)</li>
     *   <li>"Stan Street" vs "Stan Street South" → true (suffix added)</li>
     *   <li>"Main Street" vs "North Main Avenue" → false (street type also differs)</li>
     * </ul>
     *
     * @param expandedOsmName  The expanded current OSM name
     * @param expandedSuggested The expanded suggested name
     * @return true if the only difference is a directional prefix and/or suffix
     */
    public static boolean isDirectionalUpgrade(String expandedOsmName, String expandedSuggested) {
        if (expandedOsmName == null || expandedSuggested == null) {
            return false;
        }

        String[] osmWords = expandedOsmName.split("\\s+");
        String[] sugWords = expandedSuggested.split("\\s+");

        // Suggested name must have more words (the directional was added)
        if (sugWords.length <= osmWords.length) {
            return false;
        }

        // Strip directional prefix from suggested if present
        int sugStart = 0;
        if (DIRECTIONAL_WORDS.contains(sugWords[0].toLowerCase())) {
            sugStart = 1;
        }

        // Strip directional suffix from suggested if present
        int sugEnd = sugWords.length;
        if (DIRECTIONAL_WORDS.contains(sugWords[sugEnd - 1].toLowerCase())) {
            sugEnd--;
        }

        // Must have stripped at least one directional
        if (sugStart == 0 && sugEnd == sugWords.length) {
            return false;
        }

        // The remaining core of the suggested name must match the OSM name exactly
        int sugCoreLen = sugEnd - sugStart;
        if (sugCoreLen != osmWords.length) {
            return false;
        }

        for (int i = 0; i < osmWords.length; i++) {
            if (!osmWords[i].equalsIgnoreCase(sugWords[sugStart + i])) {
                return false;
            }
        }

        return true;
    }
}
