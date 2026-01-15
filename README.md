# TIGERReview JOSM Plugin

**Mascot:** ROAR the tiger (Review Of American Roads)

A JOSM plugin to help mappers review TIGER-imported roadways in the United States. The plugin creates validator warnings for roads with `tiger:reviewed=no` and provides one-click fixes based on corroborating evidence.

## Features

- **Automatic name verification** via connected roads and nearby addresses
- **Alignment verification** using node version heuristics
- **Surface inference** from connected roads
- **NAD integration** (opt-in) for external address corroboration
- **One-click fixes** for roads with sufficient evidence

## Requirements

### Build Environment

| Component | Version | Notes |
|-----------|---------|-------|
| **JDK** | 17+ | Required for JOSM compatibility |
| **Gradle** | 8.5 | Included via wrapper |
| **JOSM** | 19439+ | Minimum compatible version |

### Installing JDK

**Windows:**
- Download from [Adoptium](https://adoptium.net/) (recommended) or [Oracle JDK](https://www.oracle.com/java/technologies/downloads/)
- Or use a package manager: `winget install EclipseAdoptium.Temurin.17.JDK`

**macOS:**
```bash
brew install openjdk@17
```

**Linux (Debian/Ubuntu):**
```bash
sudo apt install openjdk-17-jdk
```

Verify installation:
```bash
java -version
# Should show version 17 or higher
```

## Building

```bash
# Compile only
./gradlew compileJava --no-daemon

# Full build (creates TIGERReview.jar)
./gradlew build --no-daemon

# Run JOSM with plugin loaded (for testing)
./gradlew runJosm --no-daemon
```

### Installing the Plugin

**macOS:**
```bash
./gradlew build --no-daemon && cp build/dist/TIGERReview.jar ~/Library/JOSM/plugins/
```

**Windows:**
```bash
./gradlew build --no-daemon && cp build/dist/TIGERReview.jar $APPDATA/JOSM/plugins/
```

**Linux:**
```bash
./gradlew build --no-daemon && cp build/dist/TIGERReview.jar ~/.local/share/JOSM/plugins/
```

### Troubleshooting

If you encounter gradle errors about missing files:
```bash
rm -rf .gradle build
./gradlew build --no-daemon
```

## Usage

1. Download a US area with TIGER data in JOSM
2. Open the Validator panel (Alt+Shift+V)
3. Click **Validate**
4. Look for warnings starting with "TIGERReview -"
5. Use the **Fix** button to apply suggested corrections

## Configuration

Access settings via: **JOSM Preferences → Validator → TIGERReview**

| Setting | Default | Description |
|---------|---------|-------------|
| Address max distance | 50m | Max distance for address matching |
| Min average node version | 1.5 | Threshold for alignment verification |
| Min % nodes edited | 80% | Alternative alignment threshold |
| Connected road check | Enabled | Verify names via connected roads |
| Address check | Enabled | Verify names via addr:street |
| Node version check | Enabled | Verify alignment via node versions |
| Surface check | Enabled | Suggest surface tags |
| NAD check | **Disabled** | External API for address lookup |

### NAD (National Address Database)

The NAD check queries an external ESRI API for address corroboration. It is disabled by default and must be enabled **before** downloading data, as the fetch triggers on layer load.

## How It Works

### Evidence Types

**Name Corroboration:**
- Connected reviewed road has matching name
- Nearby OSM address has matching `addr:street`
- Nearby NAD address has matching street name (opt-in)

**Alignment Verification:**
- Road has `tiger:reviewed=position/alignment/yes`
- All nodes have version > 1
- 80%+ of nodes have version > 1
- Average node version > 1.5

### Decision Logic

| Condition | Evidence Found | Action |
|-----------|----------------|--------|
| `tiger:reviewed=no` + named | Name + Alignment | Remove tag |
| `tiger:reviewed=no` + named | Name only | Set `tiger:reviewed=name` |
| `tiger:reviewed=no` + named | Alignment only | Set `tiger:reviewed=alignment` |
| `tiger:reviewed=no` + unnamed | Alignment | Remove tag |
| `tiger:reviewed=name` | Alignment | Remove tag |
| Any road | Connected roads share surface | Suggest surface tag |

## Testing

A test file is provided at `test-data/tiger-review-test.osm`:

1. Open JOSM
2. File → Open → `test-data/tiger-review-test.osm`
3. Open Validator panel (Alt+Shift+V)
4. Click Validate
5. Verify warnings match expected scenarios

## Project Structure

```
src/main/java/org/openstreetmap/josm/plugins/tigerreview/
├── TIGERReviewPlugin.java          # Entry point
├── TIGERReviewTest.java            # Main validator logic
├── TIGERReviewPreferences.java     # Settings UI
├── checks/
│   ├── ConnectedRoadCheck.java     # Name via connected roads
│   ├── NodeVersionCheck.java       # Alignment via node versions
│   ├── AddressCheck.java           # Name via OSM addresses
│   ├── NadAddressCheck.java        # Name via NAD API
│   └── SurfaceCheck.java           # Surface inference
└── external/
    ├── NadClient.java              # NAD API client
    ├── NadDataCache.java           # Spatial cache
    └── NadDataLoader.java          # Background loader
```

## References

- [Key:tiger:reviewed](https://wiki.openstreetmap.org/wiki/Key:tiger:reviewed) - Tag documentation
- [TIGER fixup](https://wiki.openstreetmap.org/wiki/TIGER_fixup) - Background on TIGER review
- [NAD ESRI Endpoint](https://services6.arcgis.com/Do88DoK2xjTUCXd1/arcgis/rest/services/USA_NAD_Addresses/FeatureServer/0) - National Address Database API

## License

This plugin is developed for the OpenStreetMap community.

## Contributing

Maintainer: [@watmildon](https://en.osm.town/@watmildon)
