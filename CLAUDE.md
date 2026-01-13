# CLAUDE.md - TIGERReview JOSM Plugin

## Project Overview

A JOSM plugin to help mappers review TIGER-imported roadways in the United States. The plugin creates validator warnings for roads with `tiger:reviewed=no` and provides one-click fixes based on corroborating evidence.

## Build Commands

```bash
# Compile only
./gradlew compileJava --no-daemon

# Full build
./gradlew build --no-daemon

# Run JOSM with plugin loaded (for testing)
./gradlew runJosm --no-daemon

# Build and install to local JOSM
./gradlew build --no-daemon && cp build/dist/TIGERReview.jar ~/Library/JOSM/plugins/
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
    └── AddressCheck.java           # Name corroboration via nearby addresses
```

## Core Logic

### Evidence Types

**Name Corroboration** (proves the road name is correct):
1. A connected road (sharing a node) has the same name AND is NOT `tiger:reviewed=no`
2. A nearby address (within 50m default) has `addr:street` matching the road name

**Alignment Verification** (proves the road geometry has been checked):
1. Road has `tiger:reviewed=position` or `tiger:reviewed=alignment`
2. Average version of nodes in the way > 1.5 (nodes have been edited)

### Decision Matrix

| Road State | Evidence | Action |
|------------|----------|--------|
| `tiger:reviewed=no` + has name | Name + Alignment | Remove tag (auto-fix) |
| `tiger:reviewed=no` + has name | Name only | Set `tiger:reviewed=name` (auto-fix) |
| `tiger:reviewed=no` + has name | Alignment only | Warning: name not corroborated |
| `tiger:reviewed=no` + has name | None | Warning: needs full review |
| `tiger:reviewed=no` + no name | Alignment | Remove tag (auto-fix) |
| `tiger:reviewed=no` + no name | None | Warning: needs alignment review |
| `tiger:reviewed=name` | Alignment | Remove tag (auto-fix) |

## Warning Codes

Using Wikidata TIGER ID (Q19939) as prefix:

| Code | Constant | Description |
|------|----------|-------------|
| 19939001 | TIGER_FULLY_VERIFIED | Name + alignment verified |
| 19939002 | TIGER_NAME_VERIFIED | Name only verified |
| 19939003 | TIGER_NAME_NOT_CORROBORATED | Alignment OK, name not verified |
| 19939004 | TIGER_NEEDS_REVIEW | No evidence found |
| 19939005 | TIGER_UNNAMED_VERIFIED | Unnamed road, alignment verified |
| 19939006 | TIGER_NAME_UPGRADE | Was name-only, now fully verified |
| 19939007 | TIGER_UNNAMED_NEEDS_REVIEW | Unnamed road needs alignment review |

## Configuration

User preferences (JOSM Preferences → TIGERReview):

| Key | Default | Description |
|-----|---------|-------------|
| `tigerreview.address.maxDistance` | 50.0 | Max distance (meters) for address matching |
| `tigerreview.node.minAvgVersion` | 1.5 | Min average node version for alignment verification |

## Key Design Decisions

1. **Only trust reviewed roads for name corroboration** - Two `tiger:reviewed=no` roads agreeing on a name isn't real corroboration

2. **Grid-based spatial index for addresses** - Adapted from MapWithAI's StreetAddressTest for efficient nearby address lookups

3. **Node version heuristic** - Average version > 1.5 indicates nodes have likely been moved/edited, suggesting alignment was checked

4. **Separate warning types for unnamed roads** - Unnamed roads only need alignment review, not name verification

## Testing

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
