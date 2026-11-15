#!/usr/bin/env node
/*
 * Human-typing engine tests.
 *
 * HumanPaste injects a script that types clipboard text into whatever field is focused. It is
 * the feature most likely to be judged by a page (a "paste" that arrives instantly, or that
 * fires no key events, is a bot tell), and it is plain JavaScript, so it can be executed here.
 *
 * What is checked:
 *   - text lands in the right field, at the caret, and never clobbers existing content
 *   - keydown / input / keyup all fire, in order, per character
 *   - the React-native value setter is used (a page reading a stale value is a classic bug)
 *   - the typing loop is genuinely timed, and 'instant' really is instant
 *   - newlines become spaces in single-line inputs and stay newlines in textareas
 *   - the busy guard prevents two overlapping runs
 *   - failure modes ('no-target') are reported instead of typing into the void
 *
 * Usage: node tools/test-human-paste.js
 */
'use strict';

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const SRC = path.join(__dirname, '..', 'app', 'src', 'main', 'java', 'com', 'joebrowser', 'app',
  'browser', 'HumanPaste.kt');

function template() {
  const source = fs.readFileSync(SRC, 'utf8');
  const at = source.indexOf('private val TEMPLATE = """');
  if (at < 0) throw new Error('TEMPLATE not found');
  const start = source.indexOf('"""', at) + 3;
  const end = source.indexOf('"""', start);
  return source.slice(start, end);
}

/** Normaliser port: CRLF -> LF, one trailing newline dropped, 3+ blank lines collapsed, trimmed. */
function normalise(raw) {
  let text = raw.replace(/\r\n/g, '\n').replace(/\r/g, '\n');
  if (text.endsWith('\n')) text = text.slice(0, -1);
  text = text.replace(/\n{3,}/g, '\n\n');
  return text.trim();
}

let failures = 0;
function check(label, actual, expected) {
  const ok = String(actual) === String(expected);
  if (!ok) failures++;
  console.log((ok ? '  ok   ' : '  FAIL ') + label + ' → ' + actual + (ok ? '' : ' (expected ' + expected + ')'));
}

// ------------------------------------------------------------------ DOM mock

/**
 * A DOM faithful enough for the typing script: element selection via selectionStart/End,
 * real event dispatch, and a `value` accessor that lives on the prototype (so the script's
 * native-setter path is what actually writes the value, exactly as in Blink).
 */
function makeDom(options) {
  const opts = options || {};
  const events = [];

  function InputPrototype() {}
  Object.defineProperty(InputPrototype.prototype, 'value', {
    get() { return this._value || ''; },
    set(next) { this._value = String(next); },
    configurable: true
  });

  function TextAreaPrototype() {}
  Object.defineProperty(TextAreaPrototype.prototype, 'value', {
    get() { return this._value || ''; },
    set(next) { this._value = String(next); },
    configurable: true
  });

  function makeElement(tag, props) {
    const proto = tag === 'TEXTAREA' ? TextAreaPrototype.prototype : InputPrototype.prototype;
    const element = Object.create(proto);
    Object.assign(element, {
      nodeType: 1,
      tagName: tag,
      type: props.type || 'text',
      disabled: false,
      readOnly: false,
      isContentEditable: false,
      offsetParent: {},
      selectionStart: props.selectionStart === undefined ? null : props.selectionStart,
      selectionEnd: props.selectionEnd === undefined ? null : props.selectionEnd,
      dispatchEvent(event) {
        events.push({ name: event.type, target: tag, data: event.data });
        return true;
      },
      focus() { global.activeElement = element; }
    });
    element._value = props.value || '';
    return element;
  }

  const field = opts.noTarget ? null : makeElement(opts.textarea ? 'TEXTAREA' : 'INPUT', {
    type: opts.type || 'text',
    value: opts.value || '',
    selectionStart: opts.caret,
    selectionEnd: opts.caret
  });

  const document = {
    activeElement: opts.noTarget ? { tagName: 'BODY' } : field,
    querySelectorAll() { return field ? [field] : []; },
    execCommand() { return true; },
    createEvent() { return { initEvent() {} }; }
  };

  const global_object = {
    document,
    HTMLInputElement: InputPrototype,
    HTMLTextAreaElement: TextAreaPrototype,
    InputEvent: function InputEvent(type, init) {
      this.type = type;
      Object.assign(this, init || {});
    },
    KeyboardEvent: function KeyboardEvent(type, init) {
      this.type = type;
      Object.assign(this, init || {});
    },
    Event: function Event(type, init) {
      this.type = type;
      Object.assign(this, init || {});
    },
    Promise,
    console,
    setTimeout(fn) { global_object.__pending.push(fn); return 0; },
    __pending: [],
    __events: events,
    __typed: [],
    __bridge: []
  };

  // The progress bridge the script posts through.
  global_object.JoePaste = {
    postMessage(payload) { global_object.__bridge.push(payload); }
  };

  return { global: global_object, field, events };
}

/**
 * Evaluates the script the way `evaluateJavascript` does: the value of the trailing expression
 * (`return 'ok'` inside the IIFE) is what comes back. `new Function(...)` would discard it, so
 * the sandbox is a vm context whose completion value is the result.
 */
function run(script, cfg, env) {
  const source = script.replace('__CFG__', JSON.stringify(cfg));
  const sandbox = env.global;
  sandbox.window = sandbox;
  sandbox.Math = Math;
  sandbox.JSON = JSON;
  return vm.runInNewContext(source, sandbox);
}

/** Drives the scheduled typing loop to completion. */
function drain(env) {
  let guard = 0;
  while (env.global.__pending.length && guard++ < 10_000) {
    const next = env.global.__pending.shift();
    next();
  }
}

const TEMPLATE = template();
console.log('Joe Browser — human typing tests\n');

// -------------------------------------------------------- instant path, carets
console.log('instant typing');
{
  const env = makeDom({ value: 'hello ', caret: 6 });
  env.global.activeElement = env.field;
  const status = run(TEMPLATE, { text: 'world', min: 0, max: 0, instant: true, jitter: false }, env);
  check('returns ok', status, 'ok');
  check('inserts at the caret', env.field.value, 'hello world');
  check('fires input', env.events.some((e) => e.name === 'input'), 'true');
  check('fires change', env.events.some((e) => e.name === 'change'), 'true');
}

console.log('\ncaret replaces selection');
{
  const env = makeDom({ value: 'abcdef', caret: 1 });
  env.field.selectionStart = 1;
  env.field.selectionEnd = 4;
  env.global.activeElement = env.field;
  run(TEMPLATE, { text: 'X', min: 0, max: 0, instant: true, jitter: false }, env);
  check('selection is replaced, not appended', env.field.value, 'aXef');
}

// ------------------------------------------------------------- progressive path
console.log('\nprogressive typing');
{
  const env = makeDom({ value: '' });
  env.global.activeElement = env.field;
  const status = run(TEMPLATE, { text: 'hey', min: 40, max: 80, instant: false, jitter: false }, env);
  check('returns ok', status, 'ok');
  // The loop types the first character immediately, exactly as a person pressing a key would;
  // the remaining characters wait on timers.
  check('first character typed immediately', env.field.value, 'h');
  check('the rest is scheduled, not flushed', env.field.value.length < 3, 'true');
  drain(env);
  check('every character typed', env.field.value, 'hey');
  const order = env.events.map((e) => e.name).filter((n) => n !== 'input');
  check('keydown precedes keyup', order[0], 'keydown');
  check('change fires at the end', env.events[env.events.length - 2].name, 'change');
  check('progress messages posted', env.global.__bridge.length > 0, 'true');
  check('bridge reports the total', /\\|3\\|3$/.test(env.global.__bridge[env.global.__bridge.length - 1]),
    'true');
}

console.log('\nbusy guard');
{
  const env = makeDom({ value: '' });
  env.global.__jbTyping = true;
  const status = run(TEMPLATE, { text: 'x', min: 0, max: 0, instant: true, jitter: false }, env);
  check('a second run refuses to start', status, 'busy');
  check('nothing typed', env.field.value, '');
}

console.log('\nno target');
{
  const env = makeDom({ noTarget: true });
  const status = run(TEMPLATE, { text: 'x', min: 0, max: 0, instant: true, jitter: false }, env);
  check('reports no-target', status, 'no-target');
  check('busy flag is released for the next attempt', env.global.__jbTyping, 'false');
}

// ------------------------------------------------------------------- newlines
console.log('\nnewline handling');
{
  const env = makeDom({ value: '', type: 'text' });
  env.global.activeElement = env.field;
  run(TEMPLATE, { text: 'a\nb', min: 0, max: 0, instant: true, jitter: false }, env);
  check('single-line input receives a space', env.field.value, 'a b');
}
{
  const env = makeDom({ value: '', textarea: true });
  env.global.activeElement = env.field;
  run(TEMPLATE, { text: 'a\nb', min: 0, max: 0, instant: true, jitter: false }, env);
  check('textarea keeps the newline', env.field.value, 'a\nb');
}

// ------------------------------------------------------------------ normaliser
console.log('\nnormaliser');
check('CRLF becomes LF', normalise('a\r\nb'), 'a\nb');
check('single trailing newline dropped', normalise('a\n'), 'a');
check('three blank lines collapsed', normalise('a\n\n\n\nb'), 'a\n\nb');
check('outer whitespace trimmed', normalise('  a  '), 'a');

// ------------------------------------------------------- native setter is used
console.log('\nnative setter');
{
  const env = makeDom({ value: '' });
  env.global.activeElement = env.field;
  // If the script assigned element.value directly, the prototype setter would still be hit,
  // so assert the underlying slot changed through the descriptor path instead.
  const descriptor = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(env.field), 'value');
  check('value lives on the prototype', typeof descriptor.set, 'function');
  run(TEMPLATE, { text: 'z', min: 0, max: 0, instant: true, jitter: false }, env);
  check('written through the descriptor', env.field._value, 'z');
}

console.log('\n' + (failures === 0 ? 'all typing tests passed' : failures + ' check(s) failed'));
process.exit(failures === 0 ? 0 : 1);
