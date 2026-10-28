# Globe boundary data

`countries.geojson` is a local, simplified Natural Earth boundary asset. The globe
loads this file directly; it does not depend on a third-party map API or CDN.

## Source and license

- [Natural Earth vector repository](https://github.com/nvkelso/natural-earth-vector).
- Main geometry: [Admin 0 countries, 1:50m](https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_50m_admin_0_countries.geojson).
- Gibraltar and Vatican City geometry: [Admin 0 countries, 1:10m](https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_10m_admin_0_countries.geojson).
- Retrieved 28 October 2026.
- [Natural Earth terms of use](https://www.naturalearthdata.com/about/terms-of-use/): public domain, including modified data and commercial use.

## Preparation

The asset contains 243 Polygon/MultiPolygon features, 1,633 closed rings and
41,672 coordinates. Natural Earth attributes were reduced to:

- `iso_a2`: the two-letter ISO country code, or `null` for an unassigned background region.
- `name`: the source's English display name. The app should use the catalog API's Turkish name when available.
- `label_lon`, `label_lat`: source label coordinates for focusing the globe.

Large rings were simplified using Douglas–Peucker with a 0.035-degree tolerance
on continuous/unwrapped longitude coordinates, then rounded to four decimals.
Small rings and features under 0.2 degrees across were not simplified. Vatican
City keeps its 1:10m source coordinates because the 1:50m shape is displaced at
the scale of that microstate. The final file is approximately 800 KiB.

Original clockwise exterior winding and opposite hole winding are retained for
`d3-geo` spherical containment. Antimeridian-split source geometry is preserved.
No missing-country circles, rectangles or invented polygons have been added.

## Coverage and selection

All 201 codes in the current local catalog, including Türkiye, have geometry.
This includes GL, PR, SM, VA and GI. No searchable marker substitute is required.
Selection must still be restricted to codes returned by `/api/countries`:
an available geographic feature does not add it back to the product's catalog.

Six source regions have `iso_a2: null` and remain nonselectable background:
Somaliland, Kosovo, Turkish Republic of Northern Cyprus, Siachen Glacier,
Australian Indian Ocean Territories, and Ashmore and Cartier Islands. No ISO
ownership or visa jurisdiction is inferred for these source features.

## Validation

- No invalid coordinates or open/degenerate rings.
- No spherical feature area larger than a hemisphere (which would indicate inverse winding).
- `geoContains` succeeds at representative interior points for DE, TR, US, JP,
  GL, PR, GI, VA and SM, and rejects their antipodes.
- Every code in the current PostgreSQL country catalog has a matching feature.
