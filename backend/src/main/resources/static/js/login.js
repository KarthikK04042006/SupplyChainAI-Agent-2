import { api, session, showMessage, clearMessage, ApiError } from './auth.js';

const form = document.getElementById('loginForm');
const submit = document.getElementById('submit');
const spinner = document.getElementById('spinner');

/**
 * Where to go after signing in.
 *
 * Restricted to a same-page relative path. `next` comes from the query string, so accepting it
 * verbatim would turn the login page into an open redirect — a link to
 * login.html?next=https://elsewhere would bounce a freshly authenticated user off-site.
 */
function destination() {
  const next = new URLSearchParams(window.location.search).get('next');
  if (!next || !next.startsWith('/') || next.startsWith('//')) {
    return 'overview.html';
  }
  return next;
}

// An existing session skips the form. Whether the token is still valid is the backend's call;
// api.js will bounce back here with a 401 if it is not.
if (session.token) {
  window.location.replace(destination());
}

function busy(isBusy) {
  submit.disabled = isBusy;
  spinner.classList.toggle('d-none', !isBusy);
}

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  clearMessage('message');

  const username = document.getElementById('username').value.trim();
  const password = document.getElementById('password').value;

  if (!username || !password) {
    showMessage('message', 'warning', 'Enter both a username and a password.');
    return;
  }

  busy(true);
  try {
    const response = await api.login(username, password);
    session.save({
      token: response.token,
      username: response.username,
      role: response.role,
    });
    window.location.replace(destination());
  } catch (error) {
    // The backend answers the same message for a wrong password and an unknown user, so this
    // shows its detail as-is rather than adding a guess about which it was.
    const text = error instanceof ApiError
      ? error.message
      : 'Could not reach the server. Check that the backend is running.';
    showMessage('message', 'danger', text);
    document.getElementById('password').value = '';
    busy(false);
  }
});
