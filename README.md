<img width="1671" height="1041" alt="image" src="https://github.com/user-attachments/assets/95c7aa10-622a-4dcd-bca2-c862889fd926" />


# TIGER R.O.A.R. (Review of American Roads)

A [JOSM](https://josm.openstreetmap.de/) plugin that does a lot of the heavy lifting for mappers perfroming [TIGER review](https://wiki.openstreetmap.org/wiki/TIGER_fixup). It looks for evidence that a road's name and geometry have already been verified by the community, even if nobody remembered to update the `tiger:reviewed` tag.

Companion tool to [TIGERMap](https://watmildon.github.io/TIGERMap/).

## What it does

**Name verification** - checks if connected roads or nearby addresses agree on the road name. TIGER ROAR can optionally query the ESRI [National Address Database](https://www.transportation.gov/gis/national-address-database) endpoint to corroborate road names. Enable it in **Preferences → TIGER ROAR** *before* downloading data, as the fetch triggers on layer load.


**Alignment verification** - checks if the road's nodes have been moved or edited by humans (not just bots from the original import).


**Surface inference** - if roads on both ends of an untagged road share the same `surface` value, ROAR suggests adding it.


## Installing

Download the latest jar from [Releases](https://github.com/watmildon/TIGER-ROAR/releases) and [manually install](https://wiki.openstreetmap.org/wiki/JOSM/Plugins#Manually_install_JOSM_plugins) it into your JOSM plugins folder.

## Usage

1. Download a US area with TIGER data in JOSM
2. Open the TIGER ROAR side panel (**Alt+Shift+T**) and click **Analyze**, or run the validator (**Alt+Shift+V**)
3. Review the results — click any item to select the road on the map
4. Use **Fix** or **Fix All** to apply corrections


## Links

* [TIGER fixup](https://wiki.openstreetmap.org/wiki/TIGER_fixup) - background on the TIGER review effort
* [Key:tiger:reviewed](https://wiki.openstreetmap.org/wiki/Key:tiger:reviewed) - tag documentation
* [TIGERMap](https://watmildon.github.io/TIGERMap/) - web map for finding unreviewed TIGER roads
* [NAD ESRI endpoint](https://services6.arcgis.com/Do88DoK2xjTUCXd1/arcgis/rest/services/USA_NAD_Addresses/FeatureServer/0) - National Address Database API
