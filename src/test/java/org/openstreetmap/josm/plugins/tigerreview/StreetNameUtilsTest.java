// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StreetNameUtils}.
 */
class StreetNameUtilsTest {

    // -- expand() tests --

    @Test
    void testExpandDirectionalPrefix() {
        assertEquals("South Maryland Avenue", StreetNameUtils.expand("S Maryland Ave"));
        assertEquals("North Main Street", StreetNameUtils.expand("N Main St"));
        assertEquals("West Oak Drive", StreetNameUtils.expand("W Oak Dr"));
        assertEquals("East Elm Lane", StreetNameUtils.expand("E Elm Ln"));
    }

    @Test
    void testExpandInterCardinalPrefix() {
        assertEquals("Northeast 5th Avenue", StreetNameUtils.expand("NE 5th Ave"));
        assertEquals("Southwest Park Boulevard", StreetNameUtils.expand("SW Park Blvd"));
    }

    @Test
    void testExpandDirectionalSuffix() {
        assertEquals("5th Avenue North", StreetNameUtils.expand("5th Ave N"));
        assertEquals("Main Street Southwest", StreetNameUtils.expand("Main St SW"));
    }

    @Test
    void testExpandStreetType() {
        assertEquals("Main Avenue", StreetNameUtils.expand("Main Ave"));
        assertEquals("Oak Boulevard", StreetNameUtils.expand("Oak Blvd"));
        assertEquals("Pine Circle", StreetNameUtils.expand("Pine Cir"));
        assertEquals("Maple Court", StreetNameUtils.expand("Maple Ct"));
        assertEquals("Elm Drive", StreetNameUtils.expand("Elm Dr"));
        assertEquals("Cedar Lane", StreetNameUtils.expand("Cedar Ln"));
        assertEquals("Ridge Road", StreetNameUtils.expand("Ridge Rd"));
        assertEquals("Park Terrace", StreetNameUtils.expand("Park Ter"));
        assertEquals("Forest Trail", StreetNameUtils.expand("Forest Trl"));
        assertEquals("Valley Parkway", StreetNameUtils.expand("Valley Pkwy"));
        assertEquals("Harbor Place", StreetNameUtils.expand("Harbor Pl"));
        assertEquals("Spring Highway", StreetNameUtils.expand("Spring Hwy"));
    }

    @Test
    void testExpandSaintName() {
        assertEquals("Saint Catherine Street", StreetNameUtils.expand("St Catherine St"));
        assertEquals("Saint Johns Avenue", StreetNameUtils.expand("St. Johns Ave"));
        assertEquals("Saint Mary Road", StreetNameUtils.expand("St Mary Rd"));
    }

    @Test
    void testExpandCountyRoad() {
        assertEquals("County Road 5", StreetNameUtils.expand("CR 5"));
        assertEquals("County Road 123", StreetNameUtils.expand("CR 123"));
    }

    @Test
    void testExpandForestServiceRoad() {
        assertEquals("Forest Service Road 42", StreetNameUtils.expand("Fs Road 42"));
    }

    @Test
    void testExpandEStreetGuard() {
        // "E Street" is a real street name — should NOT expand "E" to "East"
        assertEquals("E Street", StreetNameUtils.expand("E St"));
    }

    @Test
    void testExpandNoChange() {
        // Already expanded — no change expected
        assertEquals("South Maryland Avenue", StreetNameUtils.expand("South Maryland Avenue"));
        assertEquals("Main Street", StreetNameUtils.expand("Main Street"));
    }

    @Test
    void testExpandOrdinals() {
        assertEquals("1st Street", StreetNameUtils.expand("First Street"));
        assertEquals("1st Street", StreetNameUtils.expand("First St"));
        assertEquals("2nd Avenue", StreetNameUtils.expand("Second Ave"));
        assertEquals("3rd Boulevard", StreetNameUtils.expand("Third Blvd"));
        assertEquals("10th Street", StreetNameUtils.expand("Tenth St"));
        assertEquals("20th Avenue", StreetNameUtils.expand("Twentieth Ave"));
    }

    @Test
    void testExpandOrdinalsAlreadyNumeric() {
        // Already numeric — no change to the ordinal
        assertEquals("1st Street", StreetNameUtils.expand("1st St"));
        assertEquals("5th Avenue", StreetNameUtils.expand("5th Ave"));
    }

    @Test
    void testExpandSingleWord() {
        // Single-word names should pass through without error
        assertEquals("North", StreetNameUtils.expand("North"));
        assertEquals("South", StreetNameUtils.expand("South"));
        assertEquals("N", StreetNameUtils.expand("N"));
        assertEquals("Main", StreetNameUtils.expand("Main"));
        assertEquals("Broadway", StreetNameUtils.expand("Broadway"));
    }

    @Test
    void testExpandNullAndEmpty() {
        assertNull(StreetNameUtils.expand(null));
        assertEquals("", StreetNameUtils.expand(""));
    }

    @Test
    void testExpandCombined() {
        // Directional prefix + street type
        assertEquals("South Maryland Avenue", StreetNameUtils.expand("S Maryland Ave"));
        // Directional prefix + suffix + street type
        assertEquals("North 5th Street West", StreetNameUtils.expand("N 5th St W"));
    }

    // -- namesMatch() tests --

    @Test
    void testNamesMatchExact() {
        assertTrue(StreetNameUtils.namesMatch("Main Street", "Main Street"));
        assertTrue(StreetNameUtils.namesMatch("Main Street", "main street"));
    }

    @Test
    void testNamesMatchAbbreviated() {
        assertTrue(StreetNameUtils.namesMatch("S Maryland Ave", "South Maryland Avenue"));
        assertTrue(StreetNameUtils.namesMatch("N Main St", "North Main Street"));
        assertTrue(StreetNameUtils.namesMatch("W Oak Dr", "West Oak Drive"));
    }

    @Test
    void testNamesMatchOneExpanded() {
        // One side abbreviated, other already expanded
        assertTrue(StreetNameUtils.namesMatch("Main Ave", "Main Avenue"));
        assertTrue(StreetNameUtils.namesMatch("Oak Blvd", "Oak Boulevard"));
    }

    @Test
    void testNamesMatchBothAbbreviated() {
        // Both sides use same abbreviations
        assertTrue(StreetNameUtils.namesMatch("S Main St", "S Main St"));
    }

    @Test
    void testNamesMatchDifferentNames() {
        assertFalse(StreetNameUtils.namesMatch("Main Street", "Oak Street"));
        assertFalse(StreetNameUtils.namesMatch("S Maryland Ave", "N Maryland Ave"));
    }

    @Test
    void testNamesMatchNull() {
        assertFalse(StreetNameUtils.namesMatch(null, "Main Street"));
        assertFalse(StreetNameUtils.namesMatch("Main Street", null));
        assertFalse(StreetNameUtils.namesMatch(null, null));
    }

    @Test
    void testNamesMatchOrdinals() {
        assertTrue(StreetNameUtils.namesMatch("1st Street", "First Street"));
        assertTrue(StreetNameUtils.namesMatch("First St", "1st Street"));
        assertTrue(StreetNameUtils.namesMatch("2nd Ave", "Second Avenue"));
        assertTrue(StreetNameUtils.namesMatch("N 3rd St", "North Third Street"));
        assertFalse(StreetNameUtils.namesMatch("1st Street", "2nd Street"));
    }

    @Test
    void testNamesMatchSaint() {
        assertTrue(StreetNameUtils.namesMatch("St Catherine St", "Saint Catherine Street"));
        assertTrue(StreetNameUtils.namesMatch("St. Johns Ave", "Saint Johns Avenue"));
    }

    @Test
    void testNamesMatchCountyRoad() {
        assertTrue(StreetNameUtils.namesMatch("CR 5", "County Road 5"));
    }
}
