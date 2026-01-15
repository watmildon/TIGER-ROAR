Investigation Report: NAD and Updated TIGER for Road Name Validation
Data Sources Overview
1. National Address Database (NAD) via ESRI
Endpoint: https://services6.arcgis.com/Do88DoK2xjTUCXd1/arcgis/rest/services/USA_NAD_Addresses/FeatureServer/0

Available Fields:

Field	OSM-style Alias
addr_street	addr:street
addr_housenumber	addr:housenumber
addr_city	addr:city
addr_state	addr:state
addr_postcode	addr:postcode
source	source
Query Capabilities:

Bounding box queries with {xmin},{ymin},{xmax},{ymax} parameters
Returns GeoJSON format
Pagination via resultOffset (ESRI limits 2000 features per request)
exceededTransferLimit flag indicates more data available
How MapWithAI uses it: The plugin downloads NAD data and converts it to OSM primitives. The addr:street field is already formatted for direct comparison to OSM road names.

2. Updated TIGER Roadway Data (TIGERweb REST API)
Endpoint: https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/Transportation/MapServer

Layers:

Layer 8: Local Roads (most comprehensive)
Layers 0-2: Primary Roads (interstates)
Layers 3-7: Secondary Roads
Available Road Name Fields:

Field	Type	Description
NAME	String(100)	Full road name
BASENAME	String(100)	Base name without directionals
PREDIR/SUFDIRABRV	String	N/S/E/W directional prefix/suffix
SUFTYPEABRV	String(14)	Type abbreviation (St, Ave, Rd, etc.)
Query Capabilities:

Full spatial queries supported
Identify and Find operations
Returns JSON/GeoJSON
Supports bounding box queries
Proposed Plan
Option A: Direct NAD Querying (New Check)
Implementation: NadAddressCheck.java


1. Query NAD endpoint for addresses in the bounding box of the way + buffer
2. Compare addr_street values against the road's name tag
3. Use case-insensitive matching with normalization (St→Street, N→North, etc.)
4. Return corroboration strength based on number of matches
Pros:

Independent of what the user has downloaded
Guaranteed fresh data from official source
Higher coverage than existing OSM addresses
Cons:

Requires network requests during validation (slow)
Rate limit concerns for large datasets
NAD coverage varies by state/locality
Option B: Direct TIGER Querying (New Check)
Implementation: TigerRoadNameCheck.java


1. Query TIGERweb Transportation layer for roads in the way's bounding box
2. Find TIGER roads that spatially overlap with the OSM way
3. Compare TIGER NAME field against OSM name tag
4. Flag mismatches or corroborate matches
Pros:

Authoritative source for road names
Can detect both correct names AND incorrect names
Updated annually by Census Bureau
Cons:

Requires network requests during validation
TIGER names may differ from local conventions (abbreviations, etc.)
Need to handle spatial matching between ways
Option C: Hybrid/Cached Approach
Implementation:

Create background download task when dataset is loaded
Cache NAD/TIGER data locally for the loaded area
Checks run against local cache (fast)
User preference to enable/disable each data source
Detailed Design: Option C (Recommended)
New Classes

src/main/java/org/openstreetmap/josm/plugins/tigerreview/
├── external/
│   ├── NadClient.java           # NAD API client
│   ├── TigerWebClient.java      # TIGERweb API client
│   ├── ExternalDataCache.java   # Shared caching infrastructure
│   └── NameNormalizer.java      # "N Main St" → "North Main Street"
├── checks/
│   ├── NadAddressCheck.java     # NAD-based name corroboration
│   └── TigerNameCheck.java      # TIGER-based name verification
Data Flow

┌─────────────────────────────────────────────────────────────────┐
│                      On Dataset Load                             │
├─────────────────────────────────────────────────────────────────┤
│  1. Calculate bounding box of downloaded area                    │
│  2. Background thread: fetch NAD addresses for bbox              │
│  3. Background thread: fetch TIGER roads for bbox                │
│  4. Build spatial indexes for both datasets                      │
│  5. Set ready flags                                              │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                      During Validation                           │
├─────────────────────────────────────────────────────────────────┤
│  For each tiger:reviewed=no way:                                 │
│    1. Check existing evidence (connected roads, node versions)   │
│    2. If name not corroborated by OSM data:                      │
│       a. Query NAD cache for nearby addr:street matches          │
│       b. Query TIGER cache for overlapping road with same name   │
│    3. Generate appropriate warning based on evidence             │
└─────────────────────────────────────────────────────────────────┘
Name Normalization Logic
TIGER and OSM use different naming conventions. A normalizer would handle:

TIGER	OSM Variant	Normalized
N Main St	North Main Street	north main street
State Route 42	SR 42 or State Road 42	state route 42
County Road 7	CR 7	county road 7
Dr Martin Luther King Jr Blvd	MLK Boulevard	Fuzzy match needed
New Warning Codes
Code	Constant	Description
19939013	TIGER_NAME_VERIFIED_NAD	Name verified via NAD address data
19939014	TIGER_NAME_VERIFIED_TIGERWEB	Name matches updated TIGER data
19939015	TIGER_NAME_MISMATCH_TIGERWEB	Name differs from updated TIGER (needs review)
Configuration Options

tigerreview.external.nad.enabled=true
tigerreview.external.nad.minMatches=2
tigerreview.external.tigerweb.enabled=true
tigerreview.external.cache.timeout=30  # minutes
tigerreview.external.download.onLoad=false  # manual trigger vs automatic
Critique of the Plan
Critique 1: Network Dependency Creates Poor UX
Problem: If the plugin makes network requests during validation, the validator will be slow and unreliable. Users expect validation to be instant.

Response: This is why Option C uses a background pre-fetch model. The external data is fetched when the dataset loads (or manually triggered), and validation runs against a local cache. If the cache isn't ready, those checks simply skip silently rather than blocking.

Unresolved: What happens if the user loads a very large area? The NAD and TIGER queries could be slow or hit rate limits.

Critique 2: Name Normalization is Hard
Problem: Matching "N Main St" to "North Main Street" requires sophisticated normalization. Edge cases abound:

"Dr Martin Luther King Jr Boulevard" vs "MLK Blvd"
"State Route 42" vs "SR-42" vs "Ohio 42"
"County Road 7" vs "CR 7" vs "Co Rd 7"
Response: Start with simple normalization (expand abbreviations, case-insensitive), then iterate. A Levenshtein distance threshold could catch slight variations. For state routes, regex patterns could match different formats.

Unresolved: Do we need a fuzzy matching library? Should mismatches be warnings or just "unable to verify"?

Critique 3: Spatial Matching Between Ways is Complex
Problem: A TIGER road and OSM road may not align perfectly. How do we determine they're "the same road"?

Response: Options:

Centroid proximity (simple but error-prone at intersections)
Hausdorff distance between linestrings (accurate but complex)
Buffer intersection (roads within X meters that share orientation)
Unresolved: What threshold makes sense? Should we use the road's actual geometry or just endpoint matching?

Critique 4: TIGER Data May Be Wrong
Problem: The Census Bureau updates TIGER, but it's not always correct. If TIGER says "Oak St" and OSM has "Oak Street", we want to corroborate, not flag as mismatch. But if TIGER says "Oak St" and OSM has "Elm St", which is wrong?

Response: The plugin should NOT automatically "correct" OSM to match TIGER. Instead:

Match = corroboration (increase confidence)
Mismatch = flag for human review (not auto-fix)
Threshold = require X% name similarity to count as match
Unresolved: Should TIGER mismatches be warnings or just logged? How do we handle known TIGER errors?

Critique 5: Privacy and Rate Limiting
Problem: Making API calls could reveal user location/activity patterns. Rate limits could cause failures.

Response:

Both APIs are public federal data with no authentication
Cache responses to minimize repeated requests
Implement exponential backoff for rate limits
Make external checks opt-in, not default
Unresolved: What are the actual rate limits for these endpoints? Do we need API keys?

Critique 6: Overlap with Existing Checks
Problem: The plugin already has AddressCheck that looks at OSM addr:street tags. NAD is essentially the same data source (addresses) just from an external endpoint.

Response: The value of NAD is:

Coverage in areas where OSM lacks address data
Independent verification (not circular OSM→OSM validation)
Unresolved: Should NAD replace the existing AddressCheck, or supplement it? Should they have different confidence weights?

Critique 7: JOSM Already Has TIGER Imagery
Problem: You mentioned JOSM has TIGER as an imagery layer. But imagery layers are raster - they can't be queried for attributes.

Response: The JOSM imagery layer is indeed WMS (raster tiles). To get road names, we need the REST API (vector data with attributes). The imagery layer helps humans visually compare alignment; the REST API helps programmatically compare names.

Unresolved: Should the plugin provide guidance on enabling the WMS layer for visual comparison alongside programmatic checks?

Questions for You
Scope of network dependency: Are you comfortable with the plugin making HTTP requests to federal APIs? Should this be opt-in only, or default-enabled for US datasets?

Priority between NAD and TIGER: NAD verifies names via addresses. TIGER verifies names via updated road data. Which is more valuable for your use case? Should we implement both, or start with one?

Mismatch handling: If TIGER has a different name than OSM, should the plugin:

(a) Generate a warning suggesting the user investigate
(b) Stay silent (only use TIGER for positive corroboration)
(c) Offer an auto-fix to change OSM to match TIGER (risky)
Name normalization depth: How sophisticated should matching be?

(a) Exact match only (safest, fewest false positives)
(b) Normalized match (expand abbreviations, ignore case)
(c) Fuzzy match (Levenshtein distance threshold)
Integration with MapWithAI: The MapWithAI plugin already fetches NAD data. Should we depend on MapWithAI as a prerequisite, or implement independent fetching? (Dependency = simpler but adds requirement; Independent = more code but standalone)

Large area handling: What's a reasonable maximum area to fetch external data for? A single county? A state? Should there be a warning when the loaded area is "too large"?

Cache persistence: Should external data be cached across JOSM sessions (faster subsequent loads, stale data risk) or fetched fresh each time (slower, always current)?

Summary
Both NAD and TIGERweb REST APIs are viable data sources for enhancing TIGER review. The recommended approach is:

Background pre-fetch when a dataset is loaded
Local cache for fast validation queries
Name normalization to handle abbreviation differences
Positive corroboration focus - matches increase confidence; mismatches flag for review (not auto-fix)
Opt-in configuration with sensible defaults
The main technical challenges are name normalization and spatial matching. The main UX challenges are network latency and managing user expectations for external data availability.