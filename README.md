<img width="2087" height="1073" alt="image" src="https://github.com/user-attachments/assets/f82f38bb-896e-448f-9d0e-bd0407c653af" />



# TIGER R.O.A.R. (Review of American Roads)

A [JOSM](https://josm.openstreetmap.de/) plugin that does a lot of the heavy lifting for mappers performing [TIGER review](https://wiki.openstreetmap.org/wiki/TIGER_fixup). It looks for evidence that a road's name and geometry have already been verified by the community, even if nobody remembered to update the `tiger:reviewed` tag - and provides worklists for general road-quality improvements.

Companion tool to [TIGERMap](https://watmildon.github.io/TIGERMap/).

## What it does

**Name verification** - checks whether connected roads, nearby OSM addresses, parallel carriageways, or etymology tags agree on a road's name. ROAR can optionally query the ESRI [National Address Database](https://www.transportation.gov/gis/national-address-database) endpoint to corroborate road names. Enable it in **Preferences → TIGER ROAR**.

**Alignment verification** - checks whether a road's nodes have been moved or edited by humans, not just bots from the original TIGER import.

**Per-road review worklists** - lists every road that needs eyes:
* Roads with `tiger:reviewed=name` (alignment still pending) and unnamed `tiger:reviewed=no` roads
* Roads where NAD or nearby OSM addresses suggest a different name or a directional prefix/suffix
* Any vehicular road missing a `surface=` tag
* Any vehicular road missing a `lanes=` tag

The worklists run independently of `tiger:reviewed` state, so ROAR is useful even after a road's TIGER review is done.

## Installing

Open JOSM, go to **Preferences → Plugins**, click **Download list** if prompted, search for `TIGER-ROAR`, check the box, and click OK. JOSM will download and enable the plugin.


### Manual install (fallback)

Download `TIGER-ROAR.jar` from the [latest release](https://github.com/watmildon/TIGER-ROAR/releases/latest) and drop it into your JOSM plugins folder ([location varies by OS](https://wiki.openstreetmap.org/wiki/JOSM/Plugins#Manually_install_JOSM_plugins)). Restart JOSM.

## Usage

1. Download a US area with TIGER data in JOSM.
2. Open the TIGER ROAR side panel (**Alt+Shift+T**) and click **Analyze**, or run JOSM's validator (**Alt+Shift+V**).
3. Review results in either tab - click any row to select the road on the map, double-click to zoom.
4. Click **Fix** to apply the selected row's correction. Selecting a category node fixes every road under it.

### The two tabs

**Bulk Review** - high-confidence, batch-friendly cleanups: residual `tiger:*` tags, fully verified roads, unnamed-verified roads, name upgrades, invalid `tiger:reviewed` values, and alignment-only roads needing a name. The same set appears as JOSM validator warnings (**Alt+Shift+V**).

**Single Review** - items that need eyes on a specific road, grouped under three top-level sections:
* **TIGER Review** - alignment worklist plus per-road name/directional suggestions from NAD and OSM addresses
* **Missing surface** - vehicular roads with no `surface=` tag
* **Missing lanes** - vehicular roads with no `lanes=` tag

The Single Review tab includes a quick-tag panel with surface (`paved`, `unpaved`, `asphalt`) and lanes presets plus a session MRU. Clicking a preset applies the tag to every selected row and, for TIGER rows, removes `tiger:*` tags in the same edit. If the chosen value would overwrite an incompatible existing tag, a confirmation dialog asks before overwriting and offers to remember the choice for the session.

## Links

* [TIGER fixup](https://wiki.openstreetmap.org/wiki/TIGER_fixup) - background on the TIGER review effort
* [Key:tiger:reviewed](https://wiki.openstreetmap.org/wiki/Key:tiger:reviewed) - tag documentation
* [TIGERMap](https://watmildon.github.io/TIGERMap/) - web map for finding unreviewed TIGER roads
* [NAD ESRI endpoint](https://services6.arcgis.com/Do88DoK2xjTUCXd1/arcgis/rest/services/USA_NAD_Addresses/FeatureServer/0) - National Address Database API
