/**
 * Products × warehouses heatmap.
 *
 * The warehouse columns arrive with the response rather than being inferred from the cells, so the
 * header is rebuilt on every load. Sorting is client-side over the loaded page — the footer says
 * so, because a "worst cover first" sort that only reordered 50 of 400 rows while looking global
 * would be misleading.
 */

import {
  api, requireSession, renderNav, escapeHtml, clearMessage, formatNumber, ApiError,
} from './auth.js';

if (!requireSession()) {
  throw new Error('redirecting to login');
}

renderNav('inventory.html');

const PAGE_SIZE = 50;

const STATUS_LABEL = {
  OK: 'Healthy',
  STOCKOUT_RISK: 'At risk',
  OVERSTOCK: 'Overstocked',
  UNKNOWN: 'No demand history',
};

const state = {
  page: 0,
  totalPages: 1,
  columns: [],
  rows: [],
  sort: { key: 'sku', direction: 'asc' },
};

function el(id) {
  return document.getElementById(id);
}

function describe(error, fallback) {
  return error instanceof ApiError ? error.message : fallback;
}

function filters() {
  return {
    q: el('search').value.trim(),
    category: el('category').value,
    warehouse: el('warehouse').value,
    status: el('status').value,
    page: state.page,
    size: PAGE_SIZE,
  };
}

async function load() {
  const body = el('gridBody');
  try {
    const grid = await api.inventoryLevels(filters());

    state.columns = grid.warehouses || [];
    state.rows = grid.rows || [];
    state.totalPages = Math.max(grid.totalPages, 1);

    fillOptions('category', grid.categories, 'All categories');
    // Warehouse options come from the columns, which the response already returns in code order.
    // Only refilled while the filter is unset, since a narrowed response carries one column and
    // would otherwise delete the options needed to widen it again.
    if (!el('warehouse').value) {
      fillOptions('warehouse', state.columns.map((c) => c.code), 'All warehouses',
        new Map(state.columns.map((c) => [c.code, `${c.code} — ${c.name}`])));
    }

    renderLegend(grid.statusCounts || {});
    renderHead();
    renderBody();

    el('subtitle').textContent = grid.truncated
      ? `Showing the first ${formatNumber(grid.totalMatching)} products — the filter matched more `
        + 'than one grid holds, so narrow it to see the rest.'
      : `${formatNumber(grid.totalMatching)} products across `
        + `${formatNumber(state.columns.length)} warehouses`;

    el('gridCount').textContent =
      `Page ${grid.page + 1} of ${state.totalPages} · sorting applies to the `
      + `${formatNumber(state.rows.length)} rows loaded here`;

    el('prevPage').disabled = grid.page <= 0;
    el('nextPage').disabled = grid.page + 1 >= state.totalPages;
  } catch (error) {
    body.innerHTML = `<tr><td colspan="5" class="text-center text-danger py-4">
      ${escapeHtml(describe(error, 'Could not load inventory levels.'))}</td></tr>`;
    el('subtitle').textContent = 'Inventory levels unavailable';
  }
}

/** Rebuilds a select, keeping the current choice when it still exists. */
function fillOptions(id, values, allLabel, labels) {
  const select = el(id);
  const current = select.value;
  select.innerHTML = `<option value="">${escapeHtml(allLabel)}</option>`
    + (values || []).map((value) => {
      const label = labels && labels.has(value) ? labels.get(value) : value;
      return `<option value="${escapeHtml(value)}">${escapeHtml(label)}</option>`;
    }).join('');
  if (current) {
    select.value = current;
  }
}

function renderLegend(counts) {
  const total = Object.values(counts).reduce((sum, n) => sum + n, 0);
  el('legend').innerHTML = ['STOCKOUT_RISK', 'OVERSTOCK', 'OK', 'UNKNOWN']
    .map((status) => `
      <span class="d-inline-flex align-items-center gap-2 small">
        <span class="cell-${status} border d-inline-block"
              style="width:1.1rem;height:1.1rem;border-radius:0.2rem;"></span>
        ${escapeHtml(STATUS_LABEL[status])}
        <span class="text-body-secondary">${formatNumber(counts[status] || 0)}</span>
      </span>`)
    .concat([`
      <span class="d-inline-flex align-items-center gap-2 small">
        <span class="border d-inline-block"
              style="width:1.1rem;height:1.1rem;border-radius:0.2rem;"></span>
        Not stocked here
      </span>`,
      `<span class="small text-body-secondary ms-auto">
        ${formatNumber(total)} cells in the filtered grid</span>`])
    .join('');
}

/** One header cell per warehouse, appended after the fixed columns. */
function renderHead() {
  const head = el('headRow');
  head.querySelectorAll('th[data-warehouse]').forEach((th) => th.remove());

  state.columns.forEach((column) => {
    const th = document.createElement('th');
    th.scope = 'col';
    th.className = 'text-end sortable';
    th.dataset.warehouse = column.code;
    th.dataset.sort = `wh:${column.code}`;
    th.title = [column.name, column.region].filter(Boolean).join(' · ');
    th.textContent = column.code;
    head.appendChild(th);
  });

  head.querySelectorAll('th.sortable').forEach((th) => {
    th.classList.remove('asc', 'desc');
    if (th.dataset.sort === state.sort.key) {
      th.classList.add(state.sort.direction);
    }
    th.onclick = () => sortBy(th.dataset.sort);
  });
}

function sortBy(key) {
  state.sort = state.sort.key === key
    ? { key, direction: state.sort.direction === 'asc' ? 'desc' : 'asc' }
    : { key, direction: key === 'sku' || key === 'category' ? 'asc' : 'desc' };
  renderHead();
  renderBody();
}

/**
 * The value a row sorts by.
 *
 * A warehouse column sorts on days of supply rather than on units, because the column exists to
 * answer "where is this about to run out" and 40 units means nothing without the demand behind it.
 * Rows with no figure sort last in either direction — an unknown is not a low number.
 */
function sortValue(row, key) {
  if (key.startsWith('wh:')) {
    const cell = row.cells[key.slice(3)];
    return cell ? cell.daysOfSupply : null;
  }
  return row[key];
}

function sortedRows() {
  const { key, direction } = state.sort;
  const factor = direction === 'asc' ? 1 : -1;

  return [...state.rows].sort((left, right) => {
    const a = sortValue(left, key);
    const b = sortValue(right, key);

    const aMissing = a === null || a === undefined || a === '';
    const bMissing = b === null || b === undefined || b === '';
    if (aMissing || bMissing) {
      return aMissing && bMissing ? 0 : (aMissing ? 1 : -1);
    }
    if (typeof a === 'string' || typeof b === 'string') {
      return String(a).localeCompare(String(b)) * factor;
    }
    return (a - b) * factor;
  });
}

function renderBody() {
  const body = el('gridBody');
  const span = 5 + state.columns.length;

  if (!state.rows.length) {
    body.innerHTML = `<tr><td colspan="${span}" class="text-center text-body-secondary py-4">
      No products match these filters.</td></tr>`;
    return;
  }
  if (!state.columns.length) {
    body.innerHTML = `<tr><td colspan="${span}" class="text-center text-body-secondary py-4">
      No active warehouses. Upload inventory levels to create them.</td></tr>`;
    return;
  }

  body.innerHTML = sortedRows().map(rowMarkup).join('');
}

function rowMarkup(row) {
  const cells = state.columns
    .map((column) => cellMarkup(row, row.cells[column.code]))
    .join('');

  return `
    <tr>
      <td class="sticky-col">
        <a href="product.html?sku=${encodeURIComponent(row.sku)}"
           class="font-monospace small">${escapeHtml(row.sku)}</a>
        <span class="d-block small text-body-secondary">${escapeHtml(row.name || '')}</span>
      </td>
      <td class="small">${escapeHtml(row.category || '—')}</td>
      <td class="text-end small">${row.dailyDemand === null
        ? '<span class="text-body-secondary" title="No demand recorded in the last 90 days">—</span>'
        : formatNumber(row.dailyDemand, 1)}</td>
      <td class="text-end small">${formatNumber(row.totalOnHand)}${divergence(row)}</td>
      <td class="text-end small">${formatNumber(row.totalOnOrder)}</td>
      ${cells}
    </tr>`;
}

/**
 * Flags a summed-cells total that disagrees with the aggregate stored on the product.
 *
 * Only meaningful with every warehouse in view: a filtered grid sums one column and is *supposed*
 * to come out lower, so flagging it then would cry wolf on every filtered load. The aggregate is
 * what the risk scan and the KPI cards read, so a real divergence matters.
 */
function divergence(row) {
  if (el('warehouse').value || Math.abs(row.totalOnHand - row.productStock) < 0.01) {
    return '';
  }
  return `<span class="badge text-bg-warning ms-1"
    title="Warehouse rows sum to ${formatNumber(row.totalOnHand)}, but the product aggregate the
risk scan reads is ${formatNumber(row.productStock)}. Re-run the inventory import to resync.">≠</span>`;
}

function cellMarkup(row, cell) {
  if (!cell) {
    return '<td class="cell cell-empty small">·</td>';
  }

  const status = cell.status || 'UNKNOWN';
  const cover = cell.daysOfSupply === null
    ? 'cover unknown'
    : `${formatNumber(cell.daysOfSupply, 1)} days of cover`;

  const tooltip = `${row.sku} at ${cell.warehouse}: ${formatNumber(cell.onHand)} on hand, `
    + `${formatNumber(cell.onOrder)} on order, ${cover} — ${STATUS_LABEL[status]}`;

  return `
    <td class="cell cell-${status} small" title="${escapeHtml(tooltip)}">
      ${formatNumber(cell.onHand)}
      <span class="cell-days">${cell.daysOfSupply === null
        ? '—' : `${formatNumber(cell.daysOfSupply, 0)}d`}</span>
    </td>`;
}

/* Wiring ------------------------------------------------------------------- */

let searchTimer = null;
el('search').addEventListener('input', () => {
  window.clearTimeout(searchTimer);
  searchTimer = window.setTimeout(() => {
    state.page = 0;
    load();
  }, 250);
});

['category', 'warehouse', 'status'].forEach((id) => {
  el(id).addEventListener('change', () => {
    state.page = 0;
    load();
  });
});

el('prevPage').addEventListener('click', () => {
  if (state.page > 0) {
    state.page -= 1;
    load();
  }
});

el('nextPage').addEventListener('click', () => {
  if (state.page + 1 < state.totalPages) {
    state.page += 1;
    load();
  }
});

el('refresh').addEventListener('click', () => {
  clearMessage('message');
  load();
});

load();
