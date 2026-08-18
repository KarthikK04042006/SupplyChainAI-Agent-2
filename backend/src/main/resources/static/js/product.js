/**
 * Product detail: one chart, three panels, four independent requests.
 *
 * Each panel fetches on its own and renders its own failure. SHAP in particular fails routinely —
 * only the tree model supports it — and a page that awaited everything together would lose the
 * chart to an explanation it never needed.
 */

import {
  api, requireSession, renderNav, escapeHtml, showMessage, clearMessage,
  formatNumber, formatCurrency, severityKey, severityClass, ApiError,
} from './auth.js';

if (!requireSession()) {
  throw new Error('redirecting to login');
}

renderNav('product.html');

const PICKER_SIZE = 500;

const COLOR = {
  history: '#0d6efd',
  forecast: '#198754',
  band: 'rgba(25, 135, 84, 0.16)',
};

const SEVERITY_HEX = {
  CRITICAL: '#dc3545',
  HIGH: '#fd7e14',
  MEDIUM: '#0dcaf0',
  LOW: '#6c757d',
};

/** Kept out of the legend: two entries for one band reads as two series. #chartLegendNote says so. */
const HIDDEN_SERIES = new Set(['Lower bound', 'Upper bound']);

const state = { sku: null, chart: null };

function describe(error, fallback) {
  return error instanceof ApiError ? error.message : fallback;
}

function el(id) {
  return document.getElementById(id);
}

/* Picker ------------------------------------------------------------------- */

/**
 * Fills the product select from the filtered catalogue.
 *
 * Asks for one large page rather than paging: this is a picker, and a planner looking for a SKU
 * wants to type part of it, not click through pages. The search box narrows server-side, so the
 * cap only bites on an unfiltered catalogue of over 500 items.
 */
async function loadPicker(preferred) {
  const select = el('product');
  try {
    const result = await api.products({
      q: el('search').value.trim(),
      category: el('category').value,
      size: PICKER_SIZE,
    });

    fillCategories(result.categories);

    if (!result.products.length) {
      select.innerHTML = '<option value="">No products match</option>';
      el('subtitle').textContent = 'No products match this filter.';
      return null;
    }

    select.innerHTML = result.products
      .map((p) => `<option value="${escapeHtml(p.sku)}">`
        + `${escapeHtml(p.sku)} — ${escapeHtml(p.name)}</option>`)
      .join('');

    // Keep the caller's SKU selected when it survived the filter; otherwise fall to the first
    // row, so the page always has something to show rather than an empty chart.
    const wanted = result.products.some((p) => p.sku === preferred)
      ? preferred
      : result.products[0].sku;
    select.value = wanted;

    if (result.totalMatching > result.products.length) {
      el('subtitle').textContent =
        `Showing ${formatNumber(result.products.length)} of `
        + `${formatNumber(result.totalMatching)} products — narrow the search to see the rest.`;
    }
    return wanted;
  } catch (error) {
    select.innerHTML = '<option value="">Unavailable</option>';
    showMessage('message', 'danger', describe(error, 'Could not load the product list.'));
    return null;
  }
}

/** Rebuilds the category options, preserving the current choice if it still exists. */
function fillCategories(categories) {
  const select = el('category');
  const current = select.value;
  select.innerHTML = '<option value="">All categories</option>'
    + (categories || [])
      .map((c) => `<option value="${escapeHtml(c)}">${escapeHtml(c)}</option>`)
      .join('');
  if (current) {
    select.value = current;
  }
}

/* Header and stat cards ---------------------------------------------------- */

async function loadProduct(sku) {
  try {
    const product = await api.product(sku);

    el('title').textContent = product.name || product.sku;
    el('subtitle').innerHTML = `<span class="font-monospace">${escapeHtml(product.sku)}</span>`
      + (product.category ? ` · ${escapeHtml(product.category)}` : '');

    el('statOnHand').textContent = formatNumber(product.currentStock);
    el('statOnHandNote').textContent = product.onOrder > 0
      ? `${formatNumber(product.onOrder)} on order`
      : 'nothing on order';

    el('statPosition').textContent = formatNumber(product.inventoryPosition);
    // Reorder point is compared against position, not on-hand, so the note says which.
    el('statPositionNote').innerHTML = product.reorderPoint === null
      ? 'no reorder point set'
      : product.belowReorderPoint
        ? `<span class="text-danger">below reorder point `
          + `${formatNumber(product.reorderPoint)}</span>`
        : `above reorder point ${formatNumber(product.reorderPoint)}`;

    el('statLeadTime').textContent = `${formatNumber(product.leadTimeDays)}d`;
    el('statLeadTimeNote').textContent = product.safetyStock === null
      ? 'no safety stock recorded'
      : `safety stock ${formatNumber(product.safetyStock)}`;

    // Null unit cost is "not priced", not zero — the same convention the value KPI uses.
    el('statValue').textContent = formatCurrency(product.stockValue);
    el('statValueNote').textContent = product.unitCost === null
      ? 'no unit cost recorded'
      : `at ${formatCurrency(product.unitCost)} per unit`;
  } catch (error) {
    showMessage('message', 'danger', describe(error, 'Could not load this product.'));
  }
}

/* Chart -------------------------------------------------------------------- */

/**
 * Draws recorded demand and the forecast on one axis.
 *
 * Both requests are settled rather than awaited together: recorded history comes from this
 * database and the forecast from the ML service, so an ML outage should still leave the operator
 * looking at their own sales figures instead of an empty card.
 */
async function loadChart(sku) {
  el('banners').innerHTML = '';
  el('chartNote').textContent = 'Loading…';

  const [historyResult, forecastResult] = await Promise.allSettled([
    api.demandHistory(sku),
    api.forecast(sku, { horizonDays: el('horizon').value }),
  ]);

  const history = historyResult.status === 'fulfilled' ? historyResult.value : null;
  const forecast = forecastResult.status === 'fulfilled' ? forecastResult.value : null;

  if (historyResult.status === 'rejected') {
    showMessage('message', 'danger',
      describe(historyResult.reason, 'Could not load recorded demand.'));
  }

  renderBanners(forecast, forecastResult.status === 'rejected' ? forecastResult.reason : null);
  renderModelPanel(forecast, forecastResult.status === 'rejected' ? forecastResult.reason : null);
  drawChart(history, forecast);
}

function drawChart(history, forecast) {
  const canvas = el('forecastChart');

  // Guards the vendored bundle rather than throwing an unhandled error that would also take
  // out the panels below.
  if (typeof Chart === 'undefined') {
    el('chartNote').textContent = 'Chart library unavailable';
    return;
  }

  const historyPoints = history ? history.points : [];
  const forecastPoints = forecast ? forecast.forecast.points : [];

  if (!historyPoints.length && !forecastPoints.length) {
    if (state.chart) {
      state.chart.destroy();
      state.chart = null;
    }
    el('chartNote').textContent = 'No demand recorded for this SKU';
    el('chartLegendNote').textContent = '';
    return;
  }

  const labels = [...historyPoints.map((p) => p.date), ...forecastPoints.map((p) => p.date)];
  const historyLength = historyPoints.length;
  const pad = (n) => new Array(Math.max(n, 0)).fill(null);

  // The forecast series repeats the last recorded value at its own first index so the two lines
  // join. Anchoring the band there too starts it at zero width, which is honest: uncertainty
  // about a day already observed is nil, and it grows from there.
  const bridge = historyLength > 0 ? historyPoints[historyLength - 1].quantity : null;
  const project = (values) => (historyLength > 0
    ? [...pad(historyLength - 1), bridge, ...values]
    : values);

  const datasets = [];

  if (forecastPoints.length) {
    datasets.push({
      label: 'Lower bound',
      data: project(forecastPoints.map((p) => p.lower)),
      borderWidth: 0,
      pointRadius: 0,
      backgroundColor: COLOR.band,
      fill: false,
      order: 3,
    });
    datasets.push({
      label: 'Upper bound',
      data: project(forecastPoints.map((p) => p.upper)),
      borderWidth: 0,
      pointRadius: 0,
      backgroundColor: COLOR.band,
      // Fills to the dataset one place earlier in this array — the lower bound. Chart.js draws
      // the highest `order` first, which is what puts the band behind both lines.
      fill: '-1',
      order: 3,
    });
  }

  datasets.push({
    label: 'Recorded demand',
    data: [...historyPoints.map((p) => p.quantity), ...pad(forecastPoints.length)],
    borderColor: COLOR.history,
    backgroundColor: COLOR.history,
    borderWidth: 2,
    pointRadius: 0,
    tension: 0.15,
    order: 1,
  });

  if (forecastPoints.length) {
    datasets.push({
      label: 'Forecast',
      data: project(forecastPoints.map((p) => p.predicted)),
      borderColor: COLOR.forecast,
      backgroundColor: COLOR.forecast,
      borderWidth: 2,
      borderDash: [6, 4],
      pointRadius: 0,
      tension: 0.15,
      order: 0,
    });
  }

  const anomalies = anomalyDataset(labels, forecast);
  if (anomalies) {
    datasets.push(anomalies);
  }

  if (state.chart) {
    state.chart.destroy();
  }

  state.chart = new Chart(canvas, {
    type: 'line',
    data: { labels, datasets },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      // Index mode with intersect off: the point of hovering is to read every series on that
      // day, and requiring a hit on a 0-radius point would make that nearly impossible.
      interaction: { mode: 'index', intersect: false },
      plugins: {
        legend: {
          labels: { filter: (item) => !HIDDEN_SERIES.has(item.text) },
        },
        tooltip: {
          callbacks: {
            label: (ctx) => (ctx.parsed.y === null
              ? null
              : `${ctx.dataset.label}: ${formatNumber(ctx.parsed.y, 1)}`),
          },
        },
      },
      scales: {
        x: { ticks: { maxTicksLimit: 12, autoSkip: true } },
        y: { beginAtZero: true, title: { display: true, text: 'Units per day' } },
      },
    },
  });

  const span = historyPoints.length
    ? `${historyPoints[0].date} to ${historyPoints[historyLength - 1].date}`
    : 'no history';
  el('chartNote').textContent = history && history.truncated
    ? `${formatNumber(historyPoints.length)} of ${formatNumber(history.observations)} `
      + `recorded days shown (${span})`
    : `${formatNumber(historyPoints.length)} recorded days (${span})`;

  el('chartLegendNote').textContent = forecastPoints.length
    ? 'The shaded band is the model’s prediction interval; the forecast line joins the last '
      + 'recorded day, where the interval is still zero wide.'
    : 'No forecast is being shown — only recorded demand.';
}

/** Marks anomalous history days on the line, so the banner points somewhere. */
function anomalyDataset(labels, forecast) {
  const anomalies = forecast ? forecast.forecast.anomalies || [] : [];
  if (!anomalies.length) {
    return null;
  }

  const index = new Map(labels.map((label, i) => [label, i]));
  const data = new Array(labels.length).fill(null);
  const colors = new Array(labels.length).fill(COLOR.history);
  let marked = 0;

  anomalies.forEach((anomaly) => {
    const at = index.get(anomaly.date);
    // An anomaly older than the charted window has no point to sit on. The banner still counts
    // it, so the two do not have to agree for the page to be truthful.
    if (at === undefined) {
      return;
    }
    data[at] = anomaly.observed;
    // Lowercase off the wire — DemandAnomaly.severity is a Severity, and Severity carries a
    // @JsonValue. Keyed raw, every marker would fall through to the history colour and the
    // anomalies would be invisible against the line they sit on.
    colors[at] = SEVERITY_HEX[severityKey(anomaly.severity)] || COLOR.history;
    marked += 1;
  });

  if (!marked) {
    return null;
  }

  return {
    label: 'Anomaly',
    data,
    showLine: false,
    pointRadius: 5,
    pointHoverRadius: 7,
    pointStyle: 'triangle',
    pointBackgroundColor: colors,
    pointBorderColor: colors,
    order: 0,
  };
}

/* Banners ------------------------------------------------------------------ */

function renderBanners(forecast, failure) {
  const host = el('banners');
  const blocks = [];

  if (failure) {
    blocks.push(banner('warning', 'Forecast unavailable',
      describe(failure, 'The ML service did not return a forecast.')));
  }

  if (forecast) {
    const anomalies = forecast.forecast.anomalies || [];
    if (anomalies.length) {
      const worst = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW']
        .find((sev) => anomalies.some((a) => severityKey(a.severity) === sev)) || 'LOW';
      const recent = anomalies.slice(-3).map((a) => `${a.date} ${a.kind.toLowerCase()} `
        + `(${formatNumber(a.observed, 1)} against an expected ${formatNumber(a.expected, 1)})`);

      blocks.push(banner(severityClass(worst),
        `${anomalies.length} anomalous ${anomalies.length === 1 ? 'day' : 'days'} in the history`,
        `Marked on the chart. Most recent: ${recent.join('; ')}. `
        + 'The forecast was fitted on this history, so a spike still influences it.'));
    }

    (forecast.forecast.warnings || []).forEach((warning) => {
      // A warning with no severity keeps its informational styling rather than dropping to the
      // grey severityClass falls back to.
      blocks.push(banner(warning.severity ? severityClass(warning.severity) : 'info',
        warning.code, warning.message));
    });
  }

  host.innerHTML = blocks.join('');
}

function banner(kind, title, text) {
  return `<div class="alert alert-${kind} py-2" role="alert">
            <strong>${escapeHtml(title)}</strong>
            <span class="small d-block">${escapeHtml(text)}</span>
          </div>`;
}

/* Reorder panel ------------------------------------------------------------ */

async function loadReorder(sku) {
  const host = el('reorderPanel');
  host.innerHTML = '<p class="text-body-secondary mb-0">Loading…</p>';
  try {
    host.innerHTML = reorderMarkup(await api.reorderRecommendation(sku));
  } catch (error) {
    host.innerHTML = quietNote(error, 'No recommendation could be computed.');
  }
}

/**
 * The reorder panel.
 *
 * Field names here are snake_case because ReorderRecommendationResponse is the ML service's wire
 * contract, annotated with @JsonProperty, and Jackson applies those names on the way out as well
 * as in. Reading `suggestedOrder` instead of `suggested_order` is not a missing value that renders
 * as a dash — it is undefined, so the panel would confidently report "No order" on a SKU that
 * needs one. `eoq` and every field of `intermediates` carry no annotation and stay as they are.
 */
function reorderMarkup(rec) {
  const decision = rec.decision || {};
  const headline = rec.suggested_order
    ? `<p class="kpi-value text-danger mb-0">${formatNumber(decision.order_quantity)}</p>
       <p class="small text-body-secondary">units to order now</p>`
    : `<p class="kpi-value text-success mb-0">No order</p>
       <p class="small text-body-secondary">stock covers the lead time</p>`;

  const figures = [
    ['Reorder point', decision.reorder_point],
    ['Safety stock', decision.safety_stock],
    ['Inventory position', decision.inventory_position],
    ['EOQ', rec.eoq],
  ].map(([label, value]) => `
      <div class="d-flex justify-content-between border-bottom py-1">
        <span class="small text-body-secondary">${escapeHtml(label)}</span>
        <span class="small fw-semibold">${formatNumber(value, 0)}</span>
      </div>`).join('');

  // The "why": every step the ML service took, with the formula that produced it. This is the
  // reason the endpoint exists — a bare quantity is not something a planner can defend.
  const steps = rec.intermediates && rec.intermediates.length
    ? `<h3 class="h6 mt-3">How it was calculated</h3>
       <ol class="list-unstyled mb-0">
         ${rec.intermediates.map((step) => `
           <li class="mb-2">
             <div class="d-flex justify-content-between gap-2">
               <span class="small fw-semibold">${escapeHtml(step.label || step.name)}</span>
               <span class="small text-nowrap">${formatNumber(step.value, 2)}
                 ${escapeHtml(step.unit || '')}</span>
             </div>
             ${step.formula ? `<div class="formula">${escapeHtml(step.formula)}</div>` : ''}
             ${step.description
               ? `<div class="small text-body-secondary">${escapeHtml(step.description)}</div>`
               : ''}
           </li>`).join('')}
       </ol>`
    : '';

  const strategy = [rec.ordering_strategy, rec.method_note].filter(Boolean).join(' — ');

  return `${headline}${figures}
    ${strategy ? `<p class="small text-body-secondary mt-2 mb-0">${escapeHtml(strategy)}</p>` : ''}
    ${steps}`;
}

/* What-if panel ------------------------------------------------------------ */

/** Resets the result area to its prompt. Called on every product switch — a result is per-SKU. */
function resetWhatif() {
  el('whatifResult').innerHTML =
    '<p class="text-body-secondary mb-0">Enter a scenario and run it to see the effect on '
    + 'this product’s recommendation.</p>';
}

/**
 * Reads the form into the shock the API expects.
 *
 * The demand field is entered as a percent change and converted to the multiplier the backend
 * takes: +30 becomes ×1.30, −20 becomes ×0.80. An empty demand field means "no demand shock", so
 * its three fields are omitted together; an empty lead-time field means "keep the product's own".
 */
function readShock() {
  const shock = {};

  const pct = el('wifDemandPct').value.trim();
  if (pct !== '') {
    shock.demandMultiplier = 1 + Number(pct) / 100;
    const start = el('wifWindowStart').value;
    const days = el('wifWindowDays').value.trim();
    shock.windowStartDate = start || null;
    shock.windowDays = days !== '' ? Number(days) : null;
  }

  const lead = el('wifLeadTime').value.trim();
  if (lead !== '') {
    shock.leadTimeDaysOverride = Number(lead);
  }

  return shock;
}

function renderWhatifError(text) {
  el('whatifResult').innerHTML = `<p class="text-warning-emphasis mb-0">${escapeHtml(text)}</p>`;
}

/**
 * Runs one scenario.
 *
 * The common mistakes are caught here so they answer instantly instead of after a round trip, but
 * the backend validates the same rules — this is a convenience, not the gate.
 */
async function runWhatif() {
  if (!state.sku) {
    renderWhatifError('Pick a product first.');
    return;
  }

  const shock = readShock();

  if (shock.demandMultiplier === undefined && shock.leadTimeDaysOverride === undefined) {
    renderWhatifError('Enter a demand change or a lead-time override — or both.');
    return;
  }
  if (shock.demandMultiplier !== undefined) {
    if (!(shock.demandMultiplier > 0)) {
      renderWhatifError(
        'A demand change of −100% or lower would erase all demand — enter more than −100%.');
      return;
    }
    if (!shock.windowStartDate || !shock.windowDays) {
      renderWhatifError('A demand change needs a start date and a length in days.');
      return;
    }
  }

  const button = el('wifRun');
  button.disabled = true;
  el('whatifResult').innerHTML = '<p class="text-body-secondary mb-0">Simulating…</p>';

  try {
    el('whatifResult').innerHTML = whatifMarkup(await api.simulate(state.sku, shock));
  } catch (error) {
    renderWhatifError(describe(error, 'The simulation could not be run.'));
  } finally {
    button.disabled = false;
  }
}

/**
 * Baseline and simulated side by side, with the headline deltas called out above them.
 *
 * The two recommendations render with the same reorderMarkup() the panel above uses, so a planner
 * reads them the same way. The deltas are computed here because the point of the panel is the
 * change, not the two absolute figures.
 */
function whatifMarkup(result) {
  const base = (result.baseline && result.baseline.decision) || {};
  const sim = (result.simulated && result.simulated.decision) || {};

  const row = (label, a, b) => {
    const missing = a === null || a === undefined || b === null || b === undefined;
    return `
      <div class="d-flex justify-content-between border-bottom py-1">
        <span class="small text-body-secondary">${escapeHtml(label)}</span>
        <span class="small fw-semibold">${formatNumber(a, 0)} → ${formatNumber(b, 0)}
          ${missing ? '' : formatDelta(b - a)}</span>
      </div>`;
  };

  return `
    <div class="mb-3">
      <h3 class="h6">What changes</h3>
      ${row('Order quantity', base.order_quantity, sim.order_quantity)}
      ${row('Reorder point', base.reorder_point, sim.reorder_point)}
      ${row('Safety stock', base.safety_stock, sim.safety_stock)}
      <p class="small text-body-secondary mt-2 mb-0">
        Safety stock is derived from the variability of past demand, so a demand-only shock leaves
        it unchanged; a lead-time change widens the protection window and moves it.
      </p>
    </div>
    <div class="row g-3">
      <div class="col-lg-6">
        <h3 class="h6 text-body-secondary">Baseline</h3>
        ${reorderMarkup(result.baseline)}
      </div>
      <div class="col-lg-6">
        <h3 class="h6 text-body-secondary">Simulated</h3>
        ${reorderMarkup(result.simulated)}
      </div>
    </div>`;
}

/** A signed, coloured delta: more to hold or order is red, less is green, no change is muted. */
function formatDelta(diff) {
  const rounded = Math.round(diff);
  if (rounded === 0) {
    return '<span class="text-body-secondary">±0</span>';
  }
  const cls = rounded > 0 ? 'text-danger' : 'text-success';
  const sign = rounded > 0 ? '+' : '−';
  return `<span class="${cls}">${sign}${formatNumber(Math.abs(rounded), 0)}</span>`;
}

/* Explain panel ------------------------------------------------------------ */

async function loadExplain(sku) {
  const host = el('explainPanel');
  host.innerHTML = '<p class="text-body-secondary mb-0">Loading…</p>';
  try {
    host.innerHTML = explainMarkup(await api.explain(sku));
  } catch (error) {
    // Routine, not exceptional: only the tree model supports SHAP, so a 422 here usually means
    // the selected model has no explanation to give rather than that anything is broken.
    host.innerHTML = quietNote(error, 'No explanation is available for the selected model.');
  }
}

/**
 * The SHAP panel.
 *
 * snake_case for the same reason as the reorder panel: ExplainResponse is the ML service's wire
 * contract and Jackson applies its @JsonProperty names on the way out too. `contributions` and
 * every field on a contribution except `abs_contribution` are unannotated and stay camel-free.
 */
function explainMarkup(explain) {
  const contributions = explain.contributions || [];

  if (!contributions.length) {
    return `<p class="text-body-secondary mb-0">
              ${escapeHtml(explain.note || 'The selected model reported no feature contributions.')}
            </p>`;
  }

  const largest = Math.max(...contributions.map((c) => Math.abs(c.contribution))) || 1;

  const rows = contributions.map((c) => {
    const width = `${Math.round((Math.abs(c.contribution) / largest) * 100)}%`;
    const down = c.contribution < 0;
    const bar = `<div class="shap-bar" style="width:${width};background-color:`
      + `${down ? 'var(--bs-danger)' : 'var(--bs-success)'}"></div>`;

    return `
      <div class="mb-2">
        <div class="d-flex justify-content-between small">
          <span>${escapeHtml(c.feature)}
            <span class="text-body-secondary">= ${formatNumber(c.value, 2)}</span></span>
          <span class="${down ? 'text-danger' : 'text-success'} fw-semibold">
            ${down ? '' : '+'}${formatNumber(c.contribution, 2)}</span>
        </div>
        <div class="shap-track">
          <div class="shap-half negative">${down ? bar : ''}</div>
          <div class="shap-half">${down ? '' : bar}</div>
        </div>
      </div>`;
  }).join('');

  return `
    <p class="small text-body-secondary">
      Predicting <strong>${formatNumber(explain.predicted, 1)}</strong> for
      ${escapeHtml(explain.explained_date || 'the next day')}, starting from a baseline of
      ${formatNumber(explain.base_value, 1)}.
      ${explain.model_used ? `Model: ${escapeHtml(explain.model_used)}.` : ''}
    </p>
    ${rows}
    <p class="small text-body-secondary mb-0">
      Bars left of centre pushed the prediction down, right of centre pushed it up. Contributions
      shown here are the largest ones and may not sum to the full gap from the baseline.
    </p>`;
}

/* Model panel -------------------------------------------------------------- */

function renderModelPanel(forecast, failure) {
  const host = el('modelPanel');

  if (!forecast) {
    host.innerHTML = failure
      ? `<p class="text-body-secondary mb-0">${escapeHtml(
          describe(failure, 'No forecast was returned.'))}</p>`
      : '<p class="text-body-secondary mb-0">No forecast was returned.</p>';
    return;
  }

  // ForecastResponse is another ML pass-through, so the selection fields are snake_case on the
  // wire. `history` beside them is a backend record and stays camelCase — the two shapes sit in
  // one response and only the annotated one is renamed.
  const f = forecast.forecast;
  const scores = f.scores || [];

  // Every candidate is listed, including the ones that failed to fit, because the selection is
  // only auditable against what it was selected over.
  const table = scores.length
    ? `<div class="table-responsive">
         <table class="table table-sm mb-0">
           <thead><tr>
             <th scope="col">Model</th>
             <th scope="col" class="text-end">MAPE</th>
             <th scope="col" class="text-end">RMSE</th>
             <th scope="col" class="text-end">MAE</th>
           </tr></thead>
           <tbody>
             ${scores.map((s) => `
               <tr class="${s.model === f.selected_model ? 'table-active fw-semibold' : ''}">
                 <td class="small">${escapeHtml(s.model)}
                   ${s.error ? `<span class="badge text-bg-light" title="${escapeHtml(s.error)}">
                     did not fit</span>` : ''}</td>
                 <td class="text-end small">${s.mape === null ? '—' : `${formatNumber(s.mape, 1)}%`}</td>
                 <td class="text-end small">${formatNumber(s.rmse, 1)}</td>
                 <td class="text-end small">${formatNumber(s.mae, 1)}</td>
               </tr>`).join('')}
           </tbody>
         </table>
       </div>`
    : '';

  host.innerHTML = `
    <p class="mb-1"><span class="badge text-bg-primary">${escapeHtml(f.selected_model || '—')}</span>
      ${f.seasonality_detected
        ? '<span class="badge text-bg-info ms-1">seasonality detected</span>' : ''}</p>
    <p class="small text-body-secondary">${escapeHtml(f.selection_reason || '')}</p>
    <p class="small text-body-secondary">
      Fitted on ${formatNumber(forecast.history.observations)} observations
      (${escapeHtml(forecast.history.from || '—')} to ${escapeHtml(forecast.history.to || '—')}),
      averaging ${formatNumber(forecast.history.averageDailyQuantity, 1)} units a day.
    </p>
    ${table}`;
}

/* Shared ------------------------------------------------------------------- */

/**
 * A panel-level failure note.
 *
 * A 422 means this SKU has too little history for the calculation, or the ML service declined it —
 * a fact about the data, not a fault — so it renders quietly. Anything else is reported as a
 * warning.
 *
 * 422 is the only quiet case because it is the only one the API produces here: MlServiceException
 * .apiStatus() answers 422 for a 4xx upstream and 503 otherwise, and nothing emits 502. A 503 is
 * the ML service being down, which is worth the louder styling — unlike a model that simply has no
 * SHAP values to give.
 */
function quietNote(error, fallback) {
  const expected = error instanceof ApiError && error.status === 422;
  return `<p class="${expected ? 'text-body-secondary' : 'text-warning-emphasis'} mb-0">
            ${escapeHtml(describe(error, fallback))}
          </p>`;
}

/* Wiring ------------------------------------------------------------------- */

function loadAll(sku) {
  state.sku = sku;
  clearMessage('message');

  // Reflected in the address bar so a product view can be linked to — the alerts feed on the
  // overview page already links here by SKU.
  const url = new URL(window.location.href);
  url.searchParams.set('sku', sku);
  window.history.replaceState({}, '', url);

  loadProduct(sku);
  loadChart(sku);
  loadReorder(sku);
  loadExplain(sku);
  resetWhatif();
}

el('product').addEventListener('change', (event) => {
  if (event.target.value) {
    loadAll(event.target.value);
  }
});

el('horizon').addEventListener('change', () => {
  if (state.sku) {
    loadChart(state.sku);
  }
});

el('refresh').addEventListener('click', () => {
  if (state.sku) {
    loadAll(state.sku);
  }
});

// Enter in any field submits, same as the button — the form's submit is the one entry point.
el('whatifForm').addEventListener('submit', (event) => {
  event.preventDefault();
  runWhatif();
});

let searchTimer = null;
['search', 'category'].forEach((id) => {
  el(id).addEventListener('input', () => {
    window.clearTimeout(searchTimer);
    // Debounced: the picker asks the server, and a request per keystroke would arrive out of
    // order as often as not.
    searchTimer = window.setTimeout(async () => {
      const sku = await loadPicker(state.sku);
      if (sku && sku !== state.sku) {
        loadAll(sku);
      }
    }, 250);
  });
});

const params = new URLSearchParams(window.location.search);
const requested = params.get('sku') || params.get('id');

loadPicker(requested).then((sku) => {
  if (sku) {
    loadAll(sku);
  }
});

