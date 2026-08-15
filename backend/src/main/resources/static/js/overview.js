import {
  api, requireSession, renderNav, isManager, escapeHtml, showMessage, clearMessage,
  formatNumber, formatCurrency, formatDateTime, SEVERITY_CLASS, severityKey, severityClass,
  ApiError,
} from './auth.js';

if (!requireSession()) {
  throw new Error('redirecting to login');
}

renderNav('overview.html');

const PAGE_SIZE = 25;
const state = { page: 0, totalPages: 1 };

// Arriving here from a page a role could not open. Worth naming rather than silently landing on
// a different page than the one that was clicked.
if (new URLSearchParams(window.location.search).get('denied')) {
  showMessage('message', 'warning', 'That page is restricted to administrators.');
}

/** MANAGER and up only. A scan writes alert rows, so a VIEWER never sees the button. */
if (isManager()) {
  const slot = document.getElementById('scanSlot');
  slot.innerHTML = '<button class="btn btn-primary" id="scan" type="button">Run scan</button>';
  document.getElementById('scan').addEventListener('click', runScan);
}

async function runScan() {
  const button = document.getElementById('scan');
  button.disabled = true;
  button.textContent = 'Scanning…';
  clearMessage('message');
  try {
    const result = await api.runScan();
    const skipped = result.productsSkipped.length
      ? ` ${result.productsSkipped.length} skipped for insufficient history.`
      : '';
    showMessage('message', 'success',
      `Scanned ${result.productsScanned} products: ${result.alertsOpened} alerts opened, `
      + `${result.alertsUpdated} updated, ${result.alertsResolved} resolved.${skipped}`);
    await Promise.all([loadSummary(), loadAlerts()]);
  } catch (error) {
    showMessage('message', 'danger', describe(error, 'The scan could not be completed.'));
  } finally {
    button.disabled = false;
    button.textContent = 'Run scan';
  }
}

function describe(error, fallback) {
  return error instanceof ApiError ? error.message : fallback;
}

async function loadSummary() {
  try {
    const summary = await api.dashboardSummary();

    document.getElementById('kpiSkus').textContent = formatNumber(summary.totalSkus);
    document.getElementById('kpiSkusNote').textContent = 'products in the catalogue';

    const bySeverity = summary.alerts.bySeverity || {};
    document.getElementById('kpiAlerts').textContent = formatNumber(summary.alerts.open);
    document.getElementById('kpiAlertsNote').innerHTML = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW']
      .map((sev) => `<span class="badge text-bg-${SEVERITY_CLASS[sev]} me-1">`
        + `${sev.charAt(0)}${sev.slice(1).toLowerCase()} ${formatNumber(bySeverity[sev] || 0)}`
        + '</span>')
      .join('');

    const value = summary.inventoryValue;
    document.getElementById('kpiValue').textContent = formatCurrency(value.amount);
    // Unpriced SKUs are named on the card. The figure is real but partial, and a card that
    // omitted the gap would read as a total.
    document.getElementById('kpiValueNote').textContent = value.skusUnpriced > 0
      ? `${formatNumber(value.skusPriced)} priced, ${formatNumber(value.skusUnpriced)} unpriced`
      : `all ${formatNumber(value.skusPriced)} SKUs priced`;

    const accuracy = summary.forecastAccuracy;
    document.getElementById('kpiAccuracy').textContent = accuracy.averagePercent === null
      ? '—'
      : `${formatNumber(accuracy.averagePercent, 1)}%`;
    document.getElementById('kpiAccuracyNote').textContent = accuracy.averagePercent === null
      ? 'not yet scored — run a scan'
      : `${formatNumber(accuracy.skusScored)} SKUs scored `
        + `${formatDateTime(accuracy.lastScoredAt)}`;

    document.getElementById('scanNote').textContent =
      `Figures read ${formatDateTime(summary.generatedAt)}`;
  } catch (error) {
    showMessage('message', 'danger', describe(error, 'Could not load the KPI figures.'));
    document.getElementById('scanNote').textContent = 'KPI figures unavailable';
  }
}

function filters() {
  const resolved = document.getElementById('filterResolved').value;
  return {
    severity: document.getElementById('filterSeverity').value,
    type: document.getElementById('filterType').value,
    resolved: resolved === '' ? null : resolved,
    page: state.page,
    size: PAGE_SIZE,
  };
}

async function loadAlerts() {
  const body = document.getElementById('alertRows');
  try {
    const result = await api.alerts(filters());
    state.totalPages = Math.max(result.totalPages, 1);

    body.innerHTML = result.alerts.length
      ? result.alerts.map(row).join('')
      : '<tr><td colspan="7" class="text-center text-body-secondary py-4">'
        + 'No alerts match these filters.</td></tr>';

    result.alerts.forEach((alert) => {
      const button = document.getElementById(`resolve-${alert.id}`);
      if (button) {
        button.addEventListener('click', () => resolve(alert.id));
      }
    });

    document.getElementById('alertCount').textContent =
      `${formatNumber(result.totalMatching)} matching · `
      + `${formatNumber(result.counts.open)} open (${formatNumber(result.counts.critical)} critical, `
      + `${formatNumber(result.counts.high)} high) · page ${result.page + 1} of ${state.totalPages}`;

    document.getElementById('prevPage').disabled = result.page <= 0;
    document.getElementById('nextPage').disabled = result.page + 1 >= state.totalPages;
  } catch (error) {
    body.innerHTML = '<tr><td colspan="7" class="text-center text-danger py-4">'
      + `${escapeHtml(describe(error, 'Could not load alerts.'))}</td></tr>`;
  }
}

function row(alert) {
  // AlertView serializes severity through Severity's @JsonValue, so this arrives lowercase —
  // unlike the KPI map above, which is built from name(). Normalised for both the badge class
  // and the sev- row border, which app.css keys by the uppercase name.
  const severity = severityKey(alert.severity);
  const badge = severityClass(alert.severity);
  const repeat = alert.occurrences > 1
    ? `<span class="badge text-bg-light ms-1" title="Reported by ${alert.occurrences} scans">`
      + `×${alert.occurrences}</span>`
    : '';

  // Only MANAGER and up get a resolve button, and only for an alert that is still open.
  const action = isManager() && !alert.resolved
    ? `<button class="btn btn-sm btn-outline-primary" id="resolve-${alert.id}" type="button">
         Resolve</button>`
    : alert.resolved
      ? `<span class="small text-body-secondary" title="${escapeHtml(alert.resolutionNote || '')}">
           by ${escapeHtml(alert.resolvedBy || 'unknown')}</span>`
      : '';

  return `
    <tr class="alert-row sev-${escapeHtml(severity)}">
      <td><span class="badge text-bg-${badge}">${escapeHtml(severity)}</span></td>
      <td><a href="product.html?sku=${encodeURIComponent(alert.sku)}"
             class="font-monospace">${escapeHtml(alert.sku)}</a></td>
      <td class="small">${escapeHtml(alert.type)}${repeat}</td>
      <td class="small">${escapeHtml(alert.message)}</td>
      <td class="text-end">${alert.daysOfSupply === null
        ? '—' : formatNumber(alert.daysOfSupply, 1)}</td>
      <td class="small text-body-secondary">${formatDateTime(alert.createdAt)}</td>
      <td class="text-end">${action}</td>
    </tr>`;
}

async function resolve(id) {
  // The note is optional on the API, and an empty prompt sends none rather than an empty string.
  const note = window.prompt('Resolution note (optional):', '');
  if (note === null) {
    return;
  }
  clearMessage('message');
  try {
    await api.resolveAlert(id, note.trim());
    await Promise.all([loadSummary(), loadAlerts()]);
  } catch (error) {
    if (error instanceof ApiError && error.isConflict) {
      // Someone else resolved it first. The list is simply out of date, so refresh rather than
      // leaving a dead error over a row that no longer needs action.
      showMessage('message', 'info', 'That alert was already resolved. Refreshed the list.');
      await Promise.all([loadSummary(), loadAlerts()]);
      return;
    }
    showMessage('message', 'danger', describe(error, 'Could not resolve that alert.'));
  }
}

['filterSeverity', 'filterType', 'filterResolved'].forEach((id) => {
  document.getElementById(id).addEventListener('change', () => {
    state.page = 0;
    loadAlerts();
  });
});

document.getElementById('prevPage').addEventListener('click', () => {
  if (state.page > 0) {
    state.page -= 1;
    loadAlerts();
  }
});

document.getElementById('nextPage').addEventListener('click', () => {
  if (state.page + 1 < state.totalPages) {
    state.page += 1;
    loadAlerts();
  }
});

document.getElementById('refresh').addEventListener('click', () => {
  clearMessage('message');
  loadSummary();
  loadAlerts();
});

loadSummary();
loadAlerts();
