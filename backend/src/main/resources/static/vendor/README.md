# Vendored front-end assets

Three files belong in this directory, and all three are committed. They are vendored rather than
pulled from a CDN at page load so the dashboard renders on a host with no outbound network, and so
a CDN outage cannot take the styling and the chart with it.

Re-fetch them only to change version — the instructions below are kept for that.

| File | Used by | Source |
|---|---|---|
| `bootstrap.min.css` | all five pages | Bootstrap 5.3.x, `dist/css/bootstrap.min.css` |
| `bootstrap.bundle.min.js` | all five pages | Bootstrap 5.3.x, `dist/js/bootstrap.bundle.min.js` |
| `chart.umd.min.js` | `product.html` only | Chart.js 4.4.x, `dist/chart.umd.min.js` |

Filenames are referenced literally by the `<link>` and `<script>` tags in each page, so keep them
exactly as above rather than renaming to include a version.

## Fetching them

PowerShell:

```powershell
$v = 'vendor'
iwr https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css       -OutFile "$v/bootstrap.min.css"
iwr https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/js/bootstrap.bundle.min.js  -OutFile "$v/bootstrap.bundle.min.js"
iwr https://cdn.jsdelivr.net/npm/chart.js@4.4.1/dist/chart.umd.min.js             -OutFile "$v/chart.umd.min.js"
```

`curl -o` against the same three URLs does the same job. Pin whatever current patch release of
5.3.x / 4.4.x you land on; nothing here depends on a specific one.

## Why the bundle, and why no date adapter

`bootstrap.bundle.min.js` rather than `bootstrap.min.js` because it includes Popper, which the
navbar collapse toggle and the dismissible alerts in `showMessage()` rely on.

Chart.js needs **no** date adapter. `renderChart()` in `js/product.js` passes `data: { labels,
datasets }` with string labels on the default category scale — it never sets `type: 'time'`, which
is the only thing that would pull in `chartjs-adapter-date-fns`. If the x axis is ever switched to
a time scale, this list grows by one file.

## What breaks without them

Not much, and not silently in the case that matters. `js/product.js` guards its chart behind
`typeof Chart === 'undefined'` and prints a note in place of the canvas, so the product page still
renders its stat cards, reorder panel and SHAP panel. Missing Bootstrap leaves every page unstyled
but functionally intact — the JS never depends on Bootstrap classes for behaviour, only the
`data-bs-dismiss` on alert close buttons and the navbar toggle stop working.

Served under `permitAll` (`SecurityConfig`, `GET /vendor/**`) alongside `/css/**` and `/js/**`.
