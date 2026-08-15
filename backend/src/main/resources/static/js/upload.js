/**
 * CSV upload, ADMIN only.
 *
 * The three importers share one ImportResult shape, so there is one result renderer here rather
 * than three. Two failure modes are genuinely different and are shown differently: a 200 carrying
 * rejected rows (the file loaded, these lines did not) and a 400 (nothing loaded at all).
 */

import {
  api, requireRole, renderNav, escapeHtml, showMessage, clearMessage, formatNumber, ApiError,
} from './auth.js';

// Bounces a non-ADMIN before anything renders. Cosmetic — the endpoints are ADMIN-gated in
// SecurityConfig and would reject the upload anyway — but landing on a page whose every control
// returns 403 is worse than not being offered it.
if (!requireRole('ADMIN')) {
  throw new Error('redirecting');
}

renderNav('upload.html');

const UPLOADS = [
  { key: 'products', path: '/api/products/import', label: 'Product catalogue' },
  { key: 'inventory', path: '/api/inventory/import', label: 'Inventory levels' },
  { key: 'demand', path: '/api/demand/import', label: 'Demand history' },
];

function slot(key) {
  return document.querySelector(`[data-result="${key}"]`);
}

function describe(error, fallback) {
  return error instanceof ApiError ? error.message : fallback;
}

async function send(upload, file) {
  const host = slot(upload.key);
  clearMessage('message');

  // A local extension check, not validation: the server decides what is parseable. This only
  // avoids sending a file that plainly is not a CSV and waiting for the round trip to say so.
  if (!/\.csv$/i.test(file.name)) {
    host.innerHTML = note('warning',
      `${escapeHtml(file.name)} is not a .csv file. Export the sheet as CSV and try again.`);
    return;
  }

  host.innerHTML = `<p class="small text-body-secondary mb-0">
    Uploading ${escapeHtml(file.name)}…</p>`;

  try {
    host.innerHTML = resultMarkup(await api.upload(upload.path, file), file);
  } catch (error) {
    // A 400 means the file was rejected whole — unreadable, empty, or missing a required header.
    // Distinguished from rejected rows because the remedy is different: fix the file, not a line.
    const rejected = error instanceof ApiError && error.status === 400;
    host.innerHTML = note(rejected ? 'danger' : 'warning',
      `<strong>${escapeHtml(rejected ? 'File rejected' : 'Upload failed')}</strong>
       <span class="d-block">${escapeHtml(describe(error, 'The upload could not be completed.'))}
       </span>
       ${rejected ? '<span class="d-block small mt-1">Nothing was imported from this file.</span>'
        : ''}`);
  }
}

function resultMarkup(result, file) {
  const clean = result.rejected === 0;
  const counts = [
    ['Parsed', result.rowsParsed, 'secondary'],
    ['Inserted', result.inserted, 'success'],
    ['Updated', result.updated, 'info'],
    ['Rejected', result.rejected, result.rejected ? 'danger' : 'secondary'],
  ].map(([label, value, kind]) => `<span class="badge text-bg-${kind} me-1">`
    + `${escapeHtml(label)} ${formatNumber(value)}</span>`).join('');

  // Every reported row is listed, scrolled rather than truncated: the row numbers are the whole
  // point. The importer itself caps the list at 100, and that cap is named when it is reached.
  const errors = result.errors && result.errors.length
    ? `<div class="error-list border rounded p-2 mt-2">
         ${result.errors.map((line) => `<div>${escapeHtml(line)}</div>`).join('')}
       </div>
       ${result.errors.length < result.rejected
         ? `<p class="small text-body-secondary mb-0 mt-1">
              Showing the first ${formatNumber(result.errors.length)} of
              ${formatNumber(result.rejected)} rejected rows.</p>`
         : ''}`
    : '';

  return `
    <div class="alert alert-${clean ? 'success' : 'warning'} py-2 mb-0" role="alert">
      <strong>${escapeHtml(file.name)}</strong>
      <span class="d-block small mb-2">${clean
        ? 'Imported with no rejected rows.'
        : `Imported with ${formatNumber(result.rejected)} rejected `
          + `${result.rejected === 1 ? 'row' : 'rows'} — the rest loaded.`}</span>
      ${counts}
      ${errors}
    </div>`;
}

function note(kind, html) {
  return `<div class="alert alert-${kind} py-2 mb-0" role="alert">${html}</div>`;
}

/* Wiring ------------------------------------------------------------------- */

UPLOADS.forEach((upload) => {
  const input = document.querySelector(`[data-input="${upload.key}"]`);
  const zone = document.querySelector(`[data-zone="${upload.key}"]`);

  input.addEventListener('change', () => {
    if (input.files.length) {
      send(upload, input.files[0]);
      // Cleared so re-picking the same corrected file fires change again.
      input.value = '';
    }
  });

  ['dragenter', 'dragover'].forEach((event) => {
    zone.addEventListener(event, (e) => {
      e.preventDefault();
      zone.classList.add('dragover');
    });
  });

  ['dragleave', 'drop'].forEach((event) => {
    zone.addEventListener(event, () => zone.classList.remove('dragover'));
  });

  zone.addEventListener('drop', (e) => {
    e.preventDefault();
    const file = e.dataTransfer.files[0];
    if (file) {
      send(upload, file);
    }
  });
});

document.getElementById('scan').addEventListener('click', async () => {
  const button = document.getElementById('scan');
  const status = document.getElementById('scanNote');

  button.disabled = true;
  button.textContent = 'Scanning…';
  status.textContent = '';
  clearMessage('message');

  try {
    const result = await api.runScan();
    const skipped = result.productsSkipped.length
      ? ` ${result.productsSkipped.length} skipped for insufficient history.`
      : '';
    showMessage('message', 'success',
      `Scanned ${result.productsScanned} products: ${result.alertsOpened} alerts opened, `
      + `${result.alertsUpdated} updated, ${result.alertsResolved} resolved.${skipped} `
      + 'Forecast accuracy has been rescored.');
    status.textContent = 'Scan complete — the overview reflects it now.';
  } catch (error) {
    showMessage('message', 'danger', describe(error, 'The scan could not be completed.'));
  } finally {
    button.disabled = false;
    button.textContent = 'Run inventory scan';
  }
});
