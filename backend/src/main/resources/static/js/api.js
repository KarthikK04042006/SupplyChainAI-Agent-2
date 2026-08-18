/**
 * The one place that knows how to talk to the backend.
 *
 * Two things live here because they must not be duplicated per page: the bearer token goes on
 * every request, and every failure comes back as an RFC 7807 ProblemDetail. GlobalExceptionHandler
 * returns that shape for all of it — validation, a missing product, an ML service outage — so one
 * parser gives every page a real message instead of "request failed".
 */

const TOKEN_KEY = 'sf.token';
const USER_KEY = 'sf.user';
const ROLE_KEY = 'sf.role';

/** Carries the parsed ProblemDetail so a caller can branch on status without re-reading the body. */
export class ApiError extends Error {
  constructor(message, status, problem) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.problem = problem || {};
  }

  /** True for the 409 the resolve endpoint answers when someone else got there first. */
  get isConflict() {
    return this.status === 409;
  }
}

export const session = {
  get token() {
    return sessionStorage.getItem(TOKEN_KEY);
  },
  get username() {
    return sessionStorage.getItem(USER_KEY);
  },
  get role() {
    return sessionStorage.getItem(ROLE_KEY);
  },
  save({ token, username, role }) {
    sessionStorage.setItem(TOKEN_KEY, token);
    sessionStorage.setItem(USER_KEY, username || '');
    sessionStorage.setItem(ROLE_KEY, role || '');
  },
  clear() {
    [TOKEN_KEY, USER_KEY, ROLE_KEY].forEach((key) => sessionStorage.removeItem(key));
  },
};

/**
 * sessionStorage, not localStorage: the token dies with the tab.
 *
 * Neither is safe against script injection on this origin — the real mitigation is that the token
 * is short-lived and the backend re-checks the role on every call. sessionStorage at least means a
 * shared machine does not hand the next person a live session.
 */

function readableProblem(problem, status) {
  if (!problem || typeof problem !== 'object') {
    return `Request failed with status ${status}`;
  }
  const parts = [];
  if (problem.detail) {
    parts.push(problem.detail);
  } else if (problem.title) {
    parts.push(problem.title);
  }
  // Attached by GlobalExceptionHandler for ML failures. The upstream reason names the field
  // Pydantic objected to, which is the only part a caller can act on.
  if (problem.upstreamError) {
    parts.push(`Upstream: ${String(problem.upstreamError).slice(0, 300)}`);
  }
  return parts.length ? parts.join(' — ') : `Request failed with status ${status}`;
}

async function parseBody(response) {
  const type = response.headers.get('content-type') || '';
  if (!type.includes('json')) {
    return null;
  }
  try {
    return await response.json();
  } catch {
    // A body that claimed JSON and was not is a broken response, not a parse worth reporting.
    return null;
  }
}

async function request(path, options = {}) {
  const headers = new Headers(options.headers || {});
  const token = session.token;
  if (token) {
    headers.set('Authorization', `Bearer ${token}`);
  }
  // Set only for bodies we serialize ourselves. FormData must keep the boundary the browser
  // generates, so overriding its type would make the upload unparseable server-side.
  if (options.json !== undefined) {
    headers.set('Content-Type', 'application/json');
  }

  const response = await fetch(path, {
    method: options.method || 'GET',
    headers,
    body: options.json !== undefined ? JSON.stringify(options.json) : options.body,
  });

  if (response.status === 401 && !options.noRedirect) {
    // Expired or absent: the page cannot recover, so bounce rather than render an empty shell.
    session.clear();
    const here = window.location.pathname + window.location.search;
    window.location.replace(`login.html?next=${encodeURIComponent(here)}`);
    throw new ApiError('Session expired', 401, {});
  }

  const body = await parseBody(response);

  if (!response.ok) {
    throw new ApiError(readableProblem(body, response.status), response.status, body);
  }
  return body;
}

/** Drops null and blank params so an unset filter is absent rather than sent as "". */
function query(params = {}) {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== null && value !== undefined && value !== '') {
      search.set(key, value);
    }
  });
  const rendered = search.toString();
  return rendered ? `?${rendered}` : '';
}

export const api = {
  login(username, password) {
    return request('/api/auth/login', {
      method: 'POST',
      json: { username, password },
      noRedirect: true,
    });
  },

  dashboardSummary() {
    return request('/api/dashboard/summary');
  },

  alerts(params) {
    return request(`/api/alerts${query(params)}`);
  },

  resolveAlert(id, note) {
    return request(`/api/alerts/${id}/resolve`, {
      method: 'PATCH',
      json: note ? { note } : {},
    });
  },

  products(params) {
    return request(`/api/products${query(params)}`);
  },

  product(id) {
    return request(`/api/products/${encodeURIComponent(id)}`);
  },

  demandHistory(id, params) {
    return request(`/api/products/${encodeURIComponent(id)}/demand-history${query(params)}`);
  },

  forecast(id, params) {
    return request(`/api/products/${encodeURIComponent(id)}/forecast${query(params)}`);
  },

  reorderRecommendation(id) {
    return request(`/api/products/${encodeURIComponent(id)}/reorder-recommendation`);
  },

  // The SKU rides in the body, not the path: a scenario is not a resource with a stable URL.
  // `shock` carries the optional demandMultiplier/windowStartDate/windowDays/leadTimeDaysOverride.
  simulate(sku, shock) {
    return request('/api/simulate', { method: 'POST', json: { sku, ...shock } });
  },

  explain(id) {
    return request(`/api/products/${encodeURIComponent(id)}/explain`);
  },

  inventoryLevels(params) {
    return request(`/api/inventory/levels${query(params)}`);
  },

  runScan() {
    return request('/api/inventory/scan', { method: 'POST' });
  },

  upload(path, file) {
    const form = new FormData();
    form.append('file', file);
    return request(path, { method: 'POST', body: form });
  },
};
