/**
 * Role awareness and the shared page chrome.
 *
 * The role hierarchy here mirrors the RoleHierarchy bean in SecurityConfig: ADMIN implies MANAGER
 * implies VIEWER. Two copies of one rule is a real cost, so be clear about what this copy is for.
 *
 * IT IS COSMETIC. The backend re-checks every request and will reject anything this file lets
 * through — a user who edits sessionStorage gains a visible button and a 403. Hiding actions here
 * is about not offering people work they cannot do, not about stopping them.
 */

import { session, api, ApiError } from './api.js';

const HIERARCHY = {
  ADMIN: ['ADMIN', 'MANAGER', 'VIEWER'],
  MANAGER: ['MANAGER', 'VIEWER'],
  VIEWER: ['VIEWER'],
};

export function hasRole(required) {
  const role = session.role;
  if (!role) {
    return false;
  }
  return (HIERARCHY[role] || []).includes(required);
}

export const isAdmin = () => hasRole('ADMIN');
export const isManager = () => hasRole('MANAGER');

/**
 * Sends an unauthenticated visitor to the login page.
 *
 * Called at the top of every page's script. It only checks that a token exists — whether it is
 * still valid is the backend's answer, and api.js handles the 401 when it comes.
 */
export function requireSession() {
  if (!session.token) {
    const here = window.location.pathname + window.location.search;
    window.location.replace(`login.html?next=${encodeURIComponent(here)}`);
    return false;
  }
  return true;
}

/**
 * Bounces a user whose role cannot use this page at all.
 *
 * Only the upload page needs this. Everywhere else the role gates individual buttons, and a
 * VIEWER still has a whole page worth of things to read.
 */
export function requireRole(required) {
  if (!requireSession()) {
    return false;
  }
  if (!hasRole(required)) {
    window.location.replace('overview.html?denied=1');
    return false;
  }
  return true;
}

const NAV = [
  { href: 'overview.html', label: 'Overview' },
  { href: 'product.html', label: 'Products' },
  { href: 'inventory.html', label: 'Inventory' },
  { href: 'upload.html', label: 'Data upload', role: 'ADMIN' },
];

/** Renders the navbar into #nav, marking the current page and dropping entries by role. */
export function renderNav(current) {
  const host = document.getElementById('nav');
  if (!host) {
    return;
  }

  const links = NAV.filter((item) => !item.role || hasRole(item.role))
    .map((item) => {
      const active = item.href === current ? ' active' : '';
      const aria = item.href === current ? ' aria-current="page"' : '';
      return `<li class="nav-item">
                <a class="nav-link${active}"${aria} href="${item.href}">${item.label}</a>
              </li>`;
    })
    .join('');

  host.innerHTML = `
    <nav class="navbar navbar-expand-lg navbar-dark bg-dark">
      <div class="container-fluid">
        <a class="navbar-brand" href="overview.html">Supply Forecast</a>
        <button class="navbar-toggler" type="button" data-bs-toggle="collapse"
                data-bs-target="#navItems" aria-controls="navItems" aria-expanded="false"
                aria-label="Toggle navigation">
          <span class="navbar-toggler-icon"></span>
        </button>
        <div class="collapse navbar-collapse" id="navItems">
          <ul class="navbar-nav me-auto mb-2 mb-lg-0">${links}</ul>
          <div class="d-flex align-items-center gap-2 text-light">
            <span class="small">
              ${escapeHtml(session.username || '')}
              <span class="badge text-bg-secondary ms-1">${escapeHtml(session.role || '')}</span>
            </span>
            <button class="btn btn-sm btn-outline-light" id="logout" type="button">Log out</button>
          </div>
        </div>
      </div>
    </nav>`;

  document.getElementById('logout').addEventListener('click', () => {
    session.clear();
    window.location.replace('login.html');
  });
}

/** Escapes text bound for innerHTML. Product names and alert messages are operator-supplied. */
export function escapeHtml(value) {
  if (value === null || value === undefined) {
    return '';
  }
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}

/** Renders a dismissible alert into a container, replacing whatever was there. */
export function showMessage(containerId, kind, text) {
  const host = document.getElementById(containerId);
  if (!host) {
    return;
  }
  host.innerHTML = `
    <div class="alert alert-${kind} alert-dismissible fade show" role="alert">
      ${escapeHtml(text)}
      <button type="button" class="btn-close" data-bs-dismiss="alert" aria-label="Close"></button>
    </div>`;
}

export function clearMessage(containerId) {
  const host = document.getElementById(containerId);
  if (host) {
    host.innerHTML = '';
  }
}

/** Bootstrap contextual class per severity, shared by the feed and the KPI cards. */
export const SEVERITY_CLASS = {
  CRITICAL: 'danger',
  HIGH: 'warning',
  MEDIUM: 'info',
  LOW: 'secondary',
};

/**
 * Normalises a severity to the uppercase spelling the tables above are keyed by.
 *
 * Both spellings genuinely arrive. Severity carries a @JsonValue that emits the ML service's
 * lowercase form, so an alert or a forecast warning comes back as "high" — but DashboardSummary
 * builds its bySeverity map from name(), so the KPI card gets "HIGH". Indexing a lookup table
 * with the raw value therefore works on one page and silently misses on the other, which shows
 * up as an uncoloured badge rather than as an error.
 */
export function severityKey(value) {
  return value === null || value === undefined ? '' : String(value).toUpperCase();
}

/** Bootstrap contextual class for a severity in either spelling. */
export function severityClass(value) {
  return SEVERITY_CLASS[severityKey(value)] || 'secondary';
}

export function formatNumber(value, digits = 0) {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return '—';
  }
  return Number(value).toLocaleString(undefined, {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  });
}

export function formatCurrency(value) {
  if (value === null || value === undefined) {
    return '—';
  }
  return Number(value).toLocaleString(undefined, {
    style: 'currency',
    currency: 'USD',
    maximumFractionDigits: 0,
  });
}

export function formatDateTime(value) {
  if (!value) {
    return '—';
  }
  return new Date(value).toLocaleString();
}

export { session, api, ApiError };
