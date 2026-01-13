# TIGERReview JOSM Plugin - Implementation Plan

## Overview

A JOSM plugin to help mappers review TIGER-imported roadways in the United States. The plugin creates validator warnings for roads with `tiger:reviewed=no` and provides one-click fixes based on corroborating evidence from connected roads and nearby address data.

---

## Core Concepts

### Evidence Types

**Name Corroboration** - Evidence that a road's `name=` tag is correct:
1. **Connected road match**: A directly connected road (sharing a node) has the same `name=` AND does NOT have `tiger:reviewed=no`
2. **Address match**: A nearby feature (within configurable distance, default 50m) has `addr:street=` matching the road's `name=`

**Alignment Evidence** - Evidence that a road's geometry has been verified:
1. **Explicit review tag**: Road has `tiger:reviewed=position` or `tiger:reviewed=alignment`
2. **Node version heuristic**: Average version of nodes comprising the way is > 1.5 (indicates nodes have been moved/edited)

### Decision Matrix

| Road State | Evidence Found | Fix Action |
|------------|----------------|------------|
| `tiger:reviewed=no` + has `name=` | Name corroborated + Alignment evidence | Remove `tiger:reviewed` tag |
| `tiger:reviewed=no` + has `name=` | Name corroborated only | Change to `tiger:reviewed=name` |
| `tiger:reviewed=no` + has `name=` | Alignment evidence only | Flag: "Name not corroborated" |
| `tiger:reviewed=no` + has `name=` | No evidence | Flag: "Needs full review" |
| `tiger:reviewed=no` + NO `name=` | Alignment evidence | Remove `tiger:reviewed` tag |
| `tiger:reviewed=no` + NO `name=` | No alignment evidence | Flag: "Needs alignment review" (separate warning type) |
| `tiger:reviewed=name` | Alignment evidence | Remove `tiger:reviewed` tag |
| `tiger:reviewed=name` | No alignment evidence | No warning (already partially reviewed) |

---

## Warning Types & Error Codes

Using Wikidata ID for TIGER (Q19939) as prefix:

| Code | Warning Type | Description | Auto-Fix Available |
|------|--------------|-------------|-------------------|
| 19939001 | `TIGER_FULLY_VERIFIED` | Name + alignment evidence found | Yes: Remove `tiger:reviewed` |
| 19939002 | `TIGER_NAME_VERIFIED` | Name corroborated, no alignment evidence | Yes: Set `tiger:reviewed=name` |
| 19939003 | `TIGER_NAME_NOT_CORROBORATED` | Has name, alignment OK, but name not verified | No |
| 19939004 | `TIGER_NEEDS_REVIEW` | Has name, no evidence at all | No |
| 19939005 | `TIGER_ALIGNMENT_ONLY` | No name tag, needs alignment review only | Conditional (see below) |
| 19939006 | `TIGER_NAME_UPGRADE` | Has `tiger:reviewed=name`, alignment now verified | Yes: Remove `tiger:reviewed` |

---

## Configuration Options

User-configurable via JOSM preferences:

| Preference Key | Type | Default | Description |
|----------------|------|---------|-------------|
| `tigerreview.address.maxDistance` | double | 50.0 | Maximum distance (meters) to search for matching `addr:street` |
| `tigerreview.node.minAvgVersion` | double | 1.5 | Minimum average node version to consider alignment verified |

---

## Architecture

### Class Structure

```
org.openstreetmap.josm.plugins.tigerreview/
├── TIGERReviewPlugin.java          # Plugin entry point, registers tests
├── TIGERReviewTest.java            # Main validator test (replaces HighwayTest)
├── evidence/
│   ├── EvidenceCollector.java      # Coordinates evidence gathering
│   ├── NameEvidence.java           # Result object for name corroboration
│   └── AlignmentEvidence.java      # Result object for alignment checks
├── checks/
│   ├── ConnectedRoadCheck.java     # Checks connected roads for name match
│   ├── AddressCheck.java           # Checks nearby addresses (grid-based)
│   └── NodeVersionCheck.java       # Calculates average node version
├── fixes/
│   └── TIGERFixCommand.java        # Command to apply tag changes
└── TIGERReviewPreferences.java     # Preference panel for settings
```

### Data Flow

```
1. TIGERReviewTest.visit(Way w)
   │
   ├─► Filter: Only process ways with highway=* AND tiger:reviewed=no|name
   │
   ├─► EvidenceCollector.collect(way)
   │   ├─► ConnectedRoadCheck.check(way) → NameEvidence
   │   ├─► AddressCheck.check(way) → NameEvidence
   │   └─► NodeVersionCheck.check(way) → AlignmentEvidence
   │
   ├─► Determine warning type based on evidence matrix
   │
   └─► Create TestError with appropriate fix command (if applicable)
```

---

## Implementation Details

### 1. Connected Road Check

```java
// Pseudocode
for each node in way.getNodes():
    for each referrer in node.getReferrers():
        if referrer is Way AND referrer != way:
            if referrer.hasKey("highway"):
                if referrer.get("name") equals way.get("name"):
                    if NOT referrer.hasTag("tiger:reviewed", "no"):
                        return NameEvidence.CORROBORATED
return NameEvidence.NOT_FOUND
```

**Key points:**
- Only check ways that share a node (directly connected)
- Ignore roads that also have `tiger:reviewed=no` (two unverified roads agreeing isn't corroboration)
- Match is case-sensitive (OSM convention)

### 2. Address Check (Grid-Based)

Adapted from MapWithAI's `StreetAddressTest`:

```java
// During startTest(): Build spatial index of addresses
for each primitive in dataset:
    if primitive.hasKey("addr:street"):
        EastNorth en = getCenter(primitive)
        long cellX = floor(en.getX() * gridDetail)
        long cellY = floor(en.getY() * gridDetail)
        addressGrid.put(Point(cellX, cellY), primitive)

// During check(): Query nearby addresses
for each segment in way:
    for each cell in ValUtil.getSegmentCells(n1, n2, gridDetail):
        for each address in getAddressesNearCell(cell, maxDistance):
            if address.get("addr:street") equals way.get("name"):
                return NameEvidence.CORROBORATED
return NameEvidence.NOT_FOUND
```

**Key points:**
- Use JOSM's `ValUtil.getSegmentCells()` for grid population
- Expand search from cell outward up to `maxDistance` (default 50m)
- Convert grid distance to approximate meters for comparison

### 3. Node Version Check

```java
double sumVersions = 0;
int nodeCount = 0;
for each node in way.getNodes():
    sumVersions += node.getVersion();
    nodeCount++;
double avgVersion = sumVersions / nodeCount;
return avgVersion > minAvgVersion ? AlignmentEvidence.VERIFIED : AlignmentEvidence.NOT_VERIFIED;
```

**Key points:**
- Simple arithmetic mean of all node versions
- Default threshold: 1.5 (configurable)
- Also check for explicit `tiger:reviewed=position|alignment` tags

### 4. Fix Commands

```java
public class TIGERFixCommand extends SequenceCommand {

    public static Command createRemoveReviewedTag(Way way) {
        return new ChangePropertyCommand(way, "tiger:reviewed", null);
    }

    public static Command createSetReviewedName(Way way) {
        return new ChangePropertyCommand(way, "tiger:reviewed", "name");
    }
}
```

**Key points:**
- Use JOSM's `ChangePropertyCommand` for undo support
- Setting value to `null` removes the tag
- Commands execute when user clicks "Fix" in validator panel

---

## Implementation Phases

### Phase 1: Core Infrastructure
- [ ] Rename `HighwayTest.java` to `TIGERReviewTest.java`
- [ ] Update plugin to register new test class
- [ ] Add filtering for `tiger:reviewed=no` ways only
- [ ] Define error code constants
- [ ] Create basic preference infrastructure

### Phase 2: Connected Road Check
- [ ] Implement `ConnectedRoadCheck` class
- [ ] Add logic to find connected ways via shared nodes
- [ ] Filter out other `tiger:reviewed=no` roads
- [ ] Create name matching logic
- [ ] Add unit tests

### Phase 3: Node Version Check
- [ ] Implement `NodeVersionCheck` class
- [ ] Calculate average node version
- [ ] Check for explicit `tiger:reviewed=position|alignment` tags
- [ ] Add unit tests

### Phase 4: Basic Warnings & Fixes
- [ ] Implement decision matrix logic
- [ ] Create `TIGERFixCommand` for tag modifications
- [ ] Wire up fixes to TestError builder
- [ ] Test fix application in JOSM

### Phase 5: Address Check
- [ ] Implement grid-based spatial index (adapt from MapWithAI)
- [ ] Build index during `startTest()`
- [ ] Query index during way processing
- [ ] Add distance threshold configuration
- [ ] Add unit tests

### Phase 6: Preferences UI
- [ ] Create `TIGERReviewPreferences` panel
- [ ] Add max distance slider/input
- [ ] Add node version threshold input
- [ ] Register with JOSM preferences system

### Phase 7: Polish & Edge Cases
- [ ] Handle `tiger:reviewed=name` → full removal upgrade
- [ ] Add separate warning type for unnamed roads
- [ ] Performance optimization for large datasets
- [ ] Add user-facing documentation

---

## Testing Strategy

### Unit Tests
- `ConnectedRoadCheckTest`: Mock ways with various connection scenarios
- `NodeVersionCheckTest`: Test version averaging and threshold
- `AddressCheckTest`: Test spatial matching with known geometries

### Integration Tests
- Load sample .osm file with TIGER data
- Run validator
- Verify correct warnings generated
- Apply fixes and verify tag changes

### Manual Testing
```bash
./gradlew runJosm
# Load US area with TIGER data
# Open Validator panel
# Run validation
# Verify warnings appear correctly
# Test one-click fixes
```

---

## Dependencies

Already available via `gradle-josm-plugin`:
- `org.openstreetmap.josm.data.validation.*` - Validator framework
- `org.openstreetmap.josm.data.osm.*` - OSM data model
- `org.openstreetmap.josm.command.*` - Undo/redo commands
- `org.openstreetmap.josm.data.validation.util.ValUtil` - Grid cell utilities

No external dependencies required.

---

## Open Questions (Resolved)

| Question | Resolution |
|----------|------------|
| How to define "nearby" for addresses? | Grid-based spatial index with configurable max distance (default 50m) |
| Node version threshold? | Average > 1.5 indicates likely alignment verification |
| Trust other `tiger:reviewed=no` roads? | No, only trust reviewed roads for name corroboration |
| Error code range? | Use Wikidata TIGER ID (19939) as prefix |
| Separate warning for unnamed roads? | Yes, "TIGER roadway only needs alignment review" |

---

## References

- [MapWithAI StreetAddressTest](https://github.com/JOSM/MapWithAI/blob/f3659fc3edb4e07679560bce389f260b3042ebbd/src/main/java/org/openstreetmap/josm/plugins/mapwithai/data/validation/tests/StreetAddressTest.java) - Reference for spatial indexing
- [JOSM Validator Framework](https://josm.openstreetmap.de/doc/org/openstreetmap/josm/data/validation/Test.html)
- [Key:tiger:reviewed](https://wiki.openstreetmap.org/wiki/Key:tiger:reviewed) - Tag documentation
- [TIGER fixup](https://wiki.openstreetmap.org/wiki/TIGER_fixup) - Background on TIGER review process
