import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';

const script = readFileSync(fileURLToPath(new URL('../../main/assets/password-capture.js', import.meta.url)), 'utf8');
function fixture(definitions, action = 'https://betna.test/login') {
  const posts = [], listeners = {}, timers = [];
  let now = 100000;
  const form = { action };
  class Input {
    constructor(values) { Object.assign(this, { name: '', id: '', autocomplete: '', type: 'text', disabled: false, visible: true, _value: '', events: [], form }, values); }
    get value() { return this._value; }
    set value(value) { this._value = value; }
    getClientRects() { return this.visible ? [{}] : []; }
    closest() { return this.form; }
    dispatchEvent(event) { this.events.push(event.type); }
  }
  const fields = definitions.map(values => new Input(values));
  const document = { readyState: 'complete', documentElement: {}, querySelectorAll(selector) { return selector === 'input' || selector === '*' ? fields : []; }, addEventListener(name, callback) { listeners[name] = callback; } };
  const window = { betnaPasswords: { postMessage(data) { posts.push(JSON.parse(data)); } } };
  const context = { window, document, location: { origin: 'https://betna.test', href: 'https://betna.test/login' }, HTMLInputElement: Input, URL, Date: { now: () => now }, Event: class { constructor(type) { this.type = type; } }, MutationObserver: class { observe() {} }, requestAnimationFrame: callback => callback(), setTimeout: callback => { timers.push(callback); } };
  vm.runInNewContext(script, context);
  return { posts, fields, window, click() { listeners.click({ type: 'click', composedPath: () => [{ matches: () => true, type: 'button' }] }); }, submit() { listeners.submit({ type: 'submit' }); }, complete() { for (const field of fields) if (field.type === 'password') field.visible = false; now += 1600; for (const callback of timers.splice(0)) callback(); } };
}

test('captures login through a SPA button and reports only form completion after password fields disappear', () => {
  const f = fixture([{ autocomplete: 'username', _value: '+251900000001' }, { type: 'password', _value: 'SyntheticLogin!' }]);
  f.click();
  assert.equal(f.posts.filter(p => p.kind === 'attempt').length, 1);
  assert.equal(f.posts.find(p => p.kind === 'attempt').username, '+251900000001');
  assert.equal(f.posts.find(p => p.kind === 'attempt').registration, false);
  assert.equal(f.posts.some(p => p.kind === 'form_resolved'), false);
  f.complete();
  assert.equal(f.posts.filter(p => p.kind === 'form_resolved').length, 1);
  assert.deepEqual(f.posts.find(p => p.kind === 'form_resolved'), { kind: 'form_resolved' });
});

test('captures matching registration passwords including autocomplete new-password', () => {
  const f = fixture([{ type: 'tel', _value: '+251900000002' }, { type: 'password', autocomplete: 'new-password', _value: 'SyntheticRegistration!' }, { type: 'password', autocomplete: 'new-password', _value: 'SyntheticRegistration!' }]);
  f.submit();
  const captured = f.posts.find(p => p.kind === 'attempt');
  assert.equal(captured.registration, true);
  assert.equal(captured.password, 'SyntheticRegistration!');
});

test('does not capture mismatched confirmation, hidden fields or cross-origin form submission', () => {
  for (const f of [
    fixture([{ type: 'password', autocomplete: 'new-password', _value: 'SyntheticOne' }, { type: 'password', autocomplete: 'new-password', _value: 'SyntheticTwo' }]),
    fixture([{ type: 'password', _value: 'SyntheticHidden', visible: false }]),
    fixture([{ type: 'password', _value: 'SyntheticCrossOrigin' }], 'https://other.test/login'),
  ]) { f.submit(); assert.equal(f.posts.some(p => p.kind === 'attempt'), false); }
});

test('fills with native setters and input events without submitting and rejects the wrong origin', () => {
  const f = fixture([{ autocomplete: 'username' }, { type: 'password' }]);
  assert.equal(f.window.__betnaCredentials.fill({ origin: 'https://other.test', username: 'SyntheticUser', password: 'SyntheticPassword' }), false);
  assert.equal(f.fields[1].value, '');
  assert.equal(f.window.__betnaCredentials.fill({ origin: 'https://betna.test', username: 'SyntheticUser', password: 'SyntheticPassword' }), true);
  assert.equal(f.fields[0].value, 'SyntheticUser');
  assert.deepEqual(f.fields[1].events, ['input', 'change']);
  assert.equal(f.posts.some(p => p.kind === 'attempt'), false);
});

test('saved logins never overwrite a registration new-password field', () => {
  const f = fixture([{ type: 'password', autocomplete: 'new-password' }]);
  assert.equal(f.window.__betnaCredentials.fill({ origin: 'https://betna.test', username: 'SyntheticUser', password: 'SyntheticPassword' }), false);
});

test('bounds captured values and avoids OTP password fields', () => {
  for (const f of [fixture([{ type: 'password', _value: 'x'.repeat(1025) }]), fixture([{ type: 'password', name: 'otp', _value: '123456' }])]) {
    f.submit(); assert.equal(f.posts.some(p => p.kind === 'attempt'), false);
  }
});
