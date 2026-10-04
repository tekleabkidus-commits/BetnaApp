(() => {
  'use strict';
  if (window.__betnaCredentials) return;
  const post = data => window.betnaPasswords?.postMessage(JSON.stringify(data));
  const visible = input => !input.disabled && input.getClientRects().length > 0;
  const roots = (root = document) => {
    const found = [root];
    for (const element of root.querySelectorAll('*')) if (element.shadowRoot) found.push(...roots(element.shadowRoot));
    return found;
  };
  const inputs = () => roots().flatMap(root => Array.from(root.querySelectorAll('input'))).filter(visible);
  const passwordInputs = () => inputs().filter(input => input.type === 'password' && !/otp|one.?time/i.test(input.autocomplete + ' ' + input.name));
  function read() {
    const fields = passwordInputs();
    const password = fields.find(field => field.autocomplete === 'new-password' && field.value) || fields.find(field => field.value);
    if (!password) return null;
    const form = password.closest('form');
    if (form && new URL(form.action || location.href, location.href).origin !== location.origin) return null;
    const peers = fields.filter(field => field !== password && field.form === password.form && field.value);
    const registration = password.autocomplete === 'new-password' || peers.length > 0;
    if (registration && peers.some(field => field.value !== password.value)) return null;
    const candidates = inputs().filter(field => field.type !== 'password' && (!form || field.form === form));
    const username = candidates.find(field => field.autocomplete === 'username') || candidates.find(field => /email|tel/.test(field.type)) || candidates.find(field => /user|phone|mobile|account|login/i.test(field.name + ' ' + field.id)) || candidates.find(field => field.type === 'text');
    const result = { username: username?.value || '', password: password.value, registration };
    return result.password.length <= 1024 && result.username.length <= 254 ? result : null;
  }
  let attempted = false, last = 0, hadPassword = false, queued = false;
  function scan() {
    queued = false;
    const fields = passwordInputs();
    const present = fields.length > 0;
    if (present !== hadPassword) {
      hadPassword = present;
      post({ kind: 'state', hasPassword: present, registration: fields.some(field => field.autocomplete === 'new-password') });
    }
    if (attempted && !present && Date.now() - last >= 1200) {
      attempted = false;
      post({ kind: 'form_resolved' });
    }
  }
  const queueScan = () => { if (!queued) { queued = true; requestAnimationFrame(scan); } };
  function capture(event) {
    if (event?.type === 'click' && !event.composedPath().some(element => element?.matches?.('button,input[type="submit"],[role="button"]'))) return;
    if (Date.now() - last < 1200) return;
    const credentials = read();
    if (!credentials?.password) return;
    last = Date.now(); attempted = true;
    post({ kind: 'attempt', ...credentials });
    setTimeout(scan, 1500); setTimeout(scan, 4000);
  }
  function fill(account) {
    if (location.origin !== account.origin) return false;
    const password = passwordInputs().find(field => field.autocomplete !== 'new-password');
    if (!password) return false;
    const form = password.closest('form');
    if (form && new URL(form.action || location.href, location.href).origin !== location.origin) return false;
    const candidates = inputs().filter(field => field.type !== 'password' && (!form || field.form === form));
    const username = candidates.find(field => field.autocomplete === 'username') || candidates.find(field => /email|tel/.test(field.type)) || candidates.find(field => /user|phone|mobile|account|login/i.test(field.name + ' ' + field.id)) || candidates.find(field => field.type === 'text');
    const set = (element, value) => {
      if (!element) return;
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(element, value);
      element.dispatchEvent(new Event('input', { bubbles: true, composed: true }));
      element.dispatchEvent(new Event('change', { bubbles: true, composed: true }));
    };
    set(username, account.username); set(password, account.password);
    return true;
  }
  window.__betnaCredentials = { read, fill, scan };
  function start() {
    if (!document.documentElement) return;
    const observer = new MutationObserver(queueScan);
    observer.observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['type', 'hidden', 'style', 'class'] });
    scan();
    post({ kind: 'ready', hasPassword: passwordInputs().length > 0 });
  }
  document.addEventListener('submit', capture, true);
  document.addEventListener('click', capture, true);
  document.addEventListener('focusin', queueScan, true);
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start, { once: true }); else start();
})();
