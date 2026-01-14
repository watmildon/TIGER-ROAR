# CLAUDE.md - TIGERReview JOSM Plugin

## Project Overview

**Mascot:** ROAR the tiger (Review Of American Roads)

A JOSM plugin to help mappers review TIGER-imported roadways in the United States. The plugin creates validator warnings for roads with `tiger:reviewed=no` and provides one-click fixes based on corroborating evidence.

## Build Commands

```bash
# Compile only
./gradlew compileJava --no-daemon

# Full build
./gradlew build --no-daemon

# Run JOSM with plugin loaded (for testing)
./gradlew runJosm --no-daemon

# Build and install to local JOSM (macOS)
./gradlew build --no-daemon && cp build/dist/TIGERReview.jar ~/Library/JOSM/plugins/

# Build and install to local JOSM (Windows - matth's machine)
./gradlew build --no-daemon && cp build/dist/TIGERReview.jar /c/Users/matth/AppData/Roaming/JOSM/plugins/
```

Note: If you get gradle errors about missing files, run `rm -rf .gradle build` first.

## Architecture

```
src/main/java/org/openstreetmap/josm/plugins/tigerreview/
├── TIGERReviewPlugin.java          # Entry point, registers validator test
├── TIGERReviewTest.java            # Main validator logic
├── TIGERReviewPreferences.java     # Settings UI panel
└── checks/
    ├── ConnectedRoadCheck.java     # Name corroboration via connected roads
    ├── NodeVersionCheck.java       # Alignment verification via node versions
    ├── AddressCheck.java           # Name corroboration via nearby addresses
    └── SurfaceCheck.java           # Surface inference from connected roads
```

## Core Logic

### Evidence Types

**Name Corroboration** (proves the road name is correct):
1. A connected road (sharing a node) has the same name AND is NOT `tiger:reviewed=no`
2. A nearby address (within 50m default) has `addr:street` matching the road name

**Alignment Verification** (proves the road geometry has been checked):
1. Road has `tiger:reviewed=position`, `tiger:reviewed=alignment`, or `tiger:reviewed=yes`
2. Every node in the way has version > 1 (all nodes have been edited)
3. High percentage (default 80%) of nodes have version > 1
4. Average version of nodes in the way > 1.5 (nodes have been edited)

**Surface Inference** (suggests a surface tag):
1. Connected roads at both ends have the same surface tag (high confidence)
2. Connected road at one end has a surface tag (lower confidence)

### Decision Matrix

| Road State | Evidence | Action |
|------------|----------|--------|
| `tiger:reviewed=no` + has name | Name + Alignment | Remove tag (auto-fix) |
| `tiger:reviewed=no` + has name | Name only | Set `tiger:reviewed=name` (auto-fix) |
| `tiger:reviewed=no` + has name | Alignment only | Set `tiger:reviewed=alignment` (auto-fix) |
| `tiger:reviewed=no` + has name | None | No warning (user can find these on their own) |
| `tiger:reviewed=no` + no name | Alignment | Remove tag (auto-fix) |
| `tiger:reviewed=no` + no name | None | No warning (user can find these on their own) |
| `tiger:reviewed=name` | Alignment | Remove tag (auto-fix) |
| Any road without `surface` tag | Connected roads have same surface | Suggest surface (auto-fix) |

## Warning Codes

Using Wikidata TIGER ID (Q19939) as prefix:

| Code | Constant | Description |
|------|----------|-------------|
| 19939001 | TIGER_FULLY_VERIFIED | Name + alignment verified (legacy, see specific codes below) |
| 19939002 | TIGER_NAME_VERIFIED | Name only verified (legacy, see specific codes below) |
| 19939003 | TIGER_NAME_NOT_CORROBORATED | Alignment OK, name not verified |
| 19939005 | TIGER_UNNAMED_VERIFIED | Unnamed road, alignment verified |
| 19939006 | TIGER_NAME_UPGRADE | Was name-only, now fully verified |
| 19939008 | TIGER_NAME_VERIFIED_BOTH_ENDS | Name verified via connected roads at both ends |
| 19939009 | TIGER_NAME_VERIFIED_ONE_END | Name verified via connected road at one end |
| 19939010 | TIGER_NAME_VERIFIED_ADDRESS | Name verified via nearby addr:street |
| 19939011 | TIGER_SURFACE_SUGGESTED_BOTH_ENDS | Surface inferred from connected roads at both ends |
| 19939012 | TIGER_SURFACE_SUGGESTED_ONE_END | Surface inferred from connected road at one end |

Note: Codes 19939004 (TIGER_NEEDS_REVIEW) and 19939007 (TIGER_UNNAMED_NEEDS_REVIEW) were removed - the plugin no longer warns on roads without evidence.

## Configuration

User preferences (JOSM Preferences → TIGERReview):

| Key | Default | Description |
|-----|---------|-------------|
| `tigerreview.address.maxDistance` | 50.0 | Max distance (meters) for address matching |
| `tigerreview.node.minAvgVersion` | 1.5 | Min average node version for alignment verification |
| `tigerreview.node.minPercentageEdited` | 0.8 | Min percentage of nodes with version > 1 (0.0-1.0) |
| `tigerreview.check.connectedRoad` | true | Enable/disable connected road name check |
| `tigerreview.check.address` | true | Enable/disable address name check |
| `tigerreview.check.nodeVersion` | true | Enable/disable node version alignment check |
| `tigerreview.check.surface` | true | Enable/disable surface suggestion check |

## Key Design Decisions

1. **Only trust reviewed roads for name corroboration** - Two `tiger:reviewed=no` roads agreeing on a name isn't real corroboration

2. **Grid-based spatial index for addresses** - Adapted from MapWithAI's StreetAddressTest for efficient nearby address lookups

3. **Node version heuristic** - Average version > 1.5, all nodes having version > 1, or high percentage (80%+) of nodes having version > 1 indicates nodes have likely been moved/edited, suggesting alignment was checked

4. **Separate warning types for unnamed roads** - Unnamed roads only need alignment review, not name verification

5. **Only warn on actionable evidence** - Roads with no evidence don't generate warnings; users can find those via JOSM's search

6. **Surface suggestions as warnings** - Surface inferences use Severity.WARNING for visibility

7. **Granular name verification codes** - Different codes for both-ends, one-end, and address-based corroboration to help users understand confidence levels

8. **Only check road endpoints for name corroboration** - Interior node connections (roads crossing over/under) don't corroborate road names

## Testing

### With Test Data File

A comprehensive test file is provided at `test-data/tiger-review-test.osm` with 15 scenarios covering all checks.

**Testing Note:** Node versions are normally reset when loading OSM files. To enable testing, nodes use the `__TEST_VERSION` tag which the plugin reads instead of the actual OSM version. This allows testing version-based alignment checks with local test data.

1. Open JOSM
2. File → Open → select `test-data/tiger-review-test.osm`
3. Open Validator panel (Alt+Shift+V)
4. Click Validate
5. Verify warnings match expected results for each scenario

### With Live Data

1. Run `./gradlew runJosm --no-daemon`
2. Download a US area with TIGER data (e.g., search for a small town)
3. Open Validator panel (Alt+Shift+V)
4. Click Validate
5. Look for warnings starting with "TIGERReview -"

## Dependencies

All provided by gradle-josm-plugin:
- JOSM validation framework (`org.openstreetmap.josm.data.validation.*`)
- OSM data model (`org.openstreetmap.josm.data.osm.*`)
- Command framework (`org.openstreetmap.josm.command.*`)

## References

- [MapWithAI StreetAddressTest](https://github.com/JOSM/MapWithAI/blob/f3659fc3edb4e07679560bce389f260b3042ebbd/src/main/java/org/openstreetmap/josm/plugins/mapwithai/data/validation/tests/StreetAddressTest.java) - Reference for spatial indexing
- [Key:tiger:reviewed](https://wiki.openstreetmap.org/wiki/Key:tiger:reviewed) - Tag documentation
- [TIGER fixup](https://wiki.openstreetmap.org/wiki/TIGER_fixup) - Background on TIGER review process
