#!/usr/bin/env node
/**
 * Static resource + binding audit.
 *
 * There is no Android SDK in this sandbox, so this script is the local stand-in for a
 * `:app:assembleDebug` run. It catches the failures that actually break a build:
 *
 *   • XML that is not well formed,
 *   • `@drawable/…`, `@string/…`, `@color/…`, `@style/…`, `@font/…`, `@dimen/…`, `@bool/…`,
 *     `@integer/…`, `@menu/…`, `@xml/…`, `@mipmap/…` references with no definition,
 *   • `R.<type>.<name>` references from Kotlin with no definition,
 *   • view-binding fields used from Kotlin (`binding.fooBar`) that do not exist in the
 *     layout the binding is inflated from — this is the one that CI reports as
 *     "Unresolved reference: fooBar" and it is entirely preventable,
 *   • layout ids referenced by `findViewById`/binding that were removed,
 *   • strings used by Kotlin (`R.string.x`) that no longer exist,
 *   • layouts that reference an id twice, or a style/data-binding mistake.
 *
 * Exit code 1 on any error. Run locally before pushing; CI runs it too, before Gradle.
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const MAIN = path.join(ROOT, 'app/src/main');
const RES = path.join(MAIN, 'res');
const JAVA = path.join(MAIN, 'java');

const errors = [];
const warnings = [];
const fail = (m) => errors.push(m);
const warn = (m) => warnings.push(m);

// ------------------------------------------------------------------ collect res

/** All resource files, flattened: type -> name -> [files] */
const resources = new Map();
const layouts = new Map(); // layout name -> { ids:Set, bindingField:parent }

function walk(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(p, out);
    else out.push(p);
  }
  return out;
}

const resFiles = walk(RES);
for (const file of resFiles) {
  // res/values(-qualifier)/name.xml  or  res/type(-qualifier)/name.ext
  const parts = path.relative(RES, file).split(path.sep);
  if (parts.length < 2) continue;
  const typeDir = parts[0];
  const base = parts[parts.length - 1];
  const nameNoExt = base.replace(/\.[^.]+$/, '');
  if (typeDir.startsWith('values')) {
    const text = fs.readFileSync(file, 'utf8');
    // <string name="x">, <color name="x">, <style name="x">, <dimen name="x">, <bool ...>, <integer ...>
    const re = /<(string|color|style|dimen|bool|integer|string-array|array|plurals)\s+name="([^"]+)"/g;
    // <plurals> also generates R.plurals.* entries with the same name.
    let m;
    while ((m = re.exec(text))) {
      // A `string-array` is `R.array.name`; `plurals` is `R.plurals.name`; everything else
      // keeps its XML tag as the R type.
      const rType = m[1] === 'string-array' ? 'array' : m[1];
      add(rType, m[2], file);
    }
  } else {
    const type = typeDir.split('-')[0];
    add(type, nameNoExt, file);
  }
}

function add(type, name, file) {
  if (!resources.has(type)) resources.set(type, new Map());
  const map = resources.get(type);
  if (!map.has(name)) map.set(name, []);
  map.get(name).push(file);
}

const has = (type, name) => (resources.get(type) || new Map()).has(name);

// -------------------------------------------------------------- parse layouts

const XML_NS = 'http://schemas.android.com/apk/res/android';
const BIND_NS = 'http://schemas.android.com/apk/res-auto';

/** Lightweight, dependency-free XML reader good enough for Android resource files. */
function parseXml(text, file) {
  const root = { tag: '#root', attrs: {}, children: [] };
  const stack = [root];
  let i = 0;
  const len = text.length;
  while (i < len) {
    const lt = text.indexOf('<', i);
    if (lt < 0) break;
    if (text.startsWith('<!--', lt)) {
      const end = text.indexOf('-->', lt);
      i = end < 0 ? len : end + 3;
      continue;
    }
    if (text.startsWith('<?', lt)) {
      const end = text.indexOf('?>', lt);
      i = end < 0 ? len : end + 2;
      continue;
    }
    if (text.startsWith('<![CDATA[', lt)) {
      const end = text.indexOf(']]>', lt);
      i = end < 0 ? len : end + 3;
      continue;
    }
    if (text.startsWith('<!', lt)) {
      const end = text.indexOf('>', lt);
      i = end < 0 ? len : end + 1;
      continue;
    }
    const gt = findTagEnd(text, lt);
    if (gt < 0) {
      fail(`${file}: unterminated tag at offset ${lt}`);
      break;
    }
    const raw = text.slice(lt + 1, gt);
    i = gt + 1;
    if (raw.startsWith('/')) {
      const tag = raw.slice(1).trim();
      const open = stack.pop();
      if (!open || open.tag !== tag) {
        fail(`${file}: mismatched </${tag}> (expected </${open ? open.tag : 'nothing'}>)`);
      }
      continue;
    }
    const selfClosing = raw.endsWith('/');
    const body = selfClosing ? raw.slice(0, -1) : raw;
    const tagMatch = /^([\w.:-]+)/.exec(body);
    if (!tagMatch) continue;
    const tag = tagMatch[1];
    const attrs = {};
    const attrRe = /([\w.:-]+)\s*=\s*"([^"]*)"/g;
    let a;
    while ((a = attrRe.exec(body.slice(tag.length)))) attrs[a[1]] = a[2];
    const node = { tag, attrs, children: [] };
    stack[stack.length - 1].children.push(node);
    if (!selfClosing) stack.push(node);
  }
  if (stack.length !== 1) fail(`${file}: ${stack.length - 1} unclosed element(s)`);
  return root.children[0];
}

function findTagEnd(text, start) {
  let quote = null;
  for (let i = start + 1; i < text.length; i++) {
    const c = text[i];
    if (quote) {
      if (c === quote) quote = null;
    } else if (c === '"' || c === "'") {
      quote = c;
    } else if (c === '>') {
      return i;
    }
  }
  return -1;
}

// Resource types that can appear after `@` in a layout/manifest reference.
const RES_TYPES = [
  'drawable', 'string', 'color', 'style', 'font', 'dimen', 'bool', 'integer', 'menu', 'xml',
  'mipmap', 'layout', 'array', 'attr', 'id', 'anim', 'interpolator', 'plurals', 'raw',
];

const SKIP_FIELDS = new Set([
  'root', 'inflate', 'getRoot', 'bind', 'lifecycle', 'executePendingBindings',
  'isInitialized', 'hashCode', 'toString', 'equals', 'copy',
]);

/** Handles that look like bindings but never are (e.g. a list of views named `binding`). */
const SKIP_BINDING_NAMES = new Set(['it', 'this']);

const EXPLICIT_IDS = new Set();

for (const [type, map] of resources) {
  if (type !== 'layout' && type !== 'menu' && type !== 'xml' && type !== 'navigation') continue;
  for (const [name, files] of map) {
    for (const file of files) {
      const text = fs.readFileSync(file, 'utf8');
      const doc = parseXml(text, file);
      if (!doc) continue;
      const ids = new Set();
      const bindingFields = new Map(); // field -> tag (only for layout)
      const visit = (node, inInclude = false) => {
        const id = node.attrs['android:id'];
        if (id && id.startsWith('@+id/')) {
          const idName = id.slice(5);
          if (ids.has(idName)) warn(`${file}: duplicate id @+id/${idName}`);
          ids.add(idName);
          EXPLICIT_IDS.add(idName);
        }
        // `binding.foo` is generated for any element that has an id — including <include>,
        // <View> and any tag with a dotted class name — so the field set is the id set.
        if (id && id.startsWith('@+id/') && /^[a-z][a-zA-Z0-9_]*$/.test(id.slice(5))) {
          bindingFields.set(id.slice(5), node.tag);
        }
        for (const child of node.children) visit(child, inInclude || node.tag === 'include');
      };
      visit(doc);
      if (type === 'layout') layouts.set(name, { ids, fields: bindingFields, file });
      // resource refs in this file
      checkRefs(text, file);
    }
  }
}

/** Mirrors the view-binding field naming rule: prefer android:id, else a typed index. */
function bindingField(node) {
  const id = node.attrs['android:id'];
  if (id && id.startsWith('@+id/')) {
    const name = id.slice(5);
    if (/^[a-z][a-zA-Z0-9_]*$/.test(name)) return name;
  }
  return null;
}

// ------------------------------------------------------------- reference check

function checkRefs(text, file) {
  const re = /@(\+)?([a-zA-Z]+)\/([\w.-]+)/g;
  let m;
  while ((m = re.exec(text))) {
    const plus = m[1];
    const type = m[2];
    const name = m[3];
    if (plus) {
      if (type === 'id') EXPLICIT_IDS.add(name);
      continue;
    }
    if (type === 'android' || type === 'null') continue;
    if (!RES_TYPES.includes(type)) continue;
    if (type === 'attr') continue; // theme attrs, resolved at build time
    if (!has(type, name)) fail(`${path.relative(ROOT, file)}: @${type}/${name} is not defined`);
  }
  // ?attr/... and ?android:attr/... are fine.
}

// --------------------------------------------------------------- Kotlin check

const kotlinFiles = walk(JAVA).filter((f) => f.endsWith('.kt') || f.endsWith('.java'));

/** Bindings whose layout we cannot resolve are reported once, not per usage. */
const bindingCache = new Map();

for (const file of kotlinFiles) {
  const text = fs.readFileSync(file, 'utf8');
  const rel = path.relative(ROOT, file);

  // R.type.name
  const re = /(?<![\w.])R\.([a-zA-Z]+)\.([A-Za-z0-9_]+)/g;
  let m;
  while ((m = re.exec(text))) {
    const type = m[1];
    const name = m[2];
    if (type === 'styleable') continue;
    if (type === 'id' && EXPLICIT_IDS.has(name)) continue;
    if (!RES_TYPES.includes(type)) continue;
    if (type === 'style') {
      // R.style.Theme_JoeBrowser_BottomSheet -> "Theme.JoeBrowser.BottomSheet"
      if (!has('style', name)) {
        const dotted = name.replace(/_/g, '.');
        if (!has('style', dotted)) fail(`${rel}: R.style.${name} is not defined`);
      }
      continue;
    }
    if (!has(type, name)) fail(`${rel}: R.${type}.${name} is not defined`);
  }

  // binding.fooBar.member — map binding type back to its layout, per *variable*.
  // Two shapes exist in this codebase:
  //   val binding: FooBinding = FooBinding.inflate(...)   (explicit type)
  //   binding = FooBinding.inflate(...)                  (assigned lateinit field)
  const varToBinding = new Map(); // varName -> bindingType
  let mm;
  // Binding handles resolved by position: an identifier (`row`, `binding`, `target`) is typed
  // by its nearest preceding declaration, so a file that inflates two different row layouts
  // (as HomeFragment does) is checked against the right one for each access.
  const decls = []; // {pos, name, type}
  // One pattern for every declaration form; `(?:val|var)\s+` is optional because typed
  // parameters (`row: ItemRowBinding`) have no keyword and the assignment form has one.
  // Listing overlapping alternatives double-counted declarations and made the `pos`-sorted
  // pick unreliable, which silently disabled the check for inferred locals like
  // `val sheet = SheetXBinding.inflate(...)`.
  // Fully-qualified form: `com.joebrowser.app.databinding.SheetXBinding.inflate(...)`.
  const unqualified = text.replace(/\b(?:[a-z][\w]*\.)+databinding\./g, '');
  const declRes = [
    /(?:\b(?:val|var)\s+)?([A-Za-z_]\w*)(?:\s*:\s*(\w+Binding))?\s*=\s*(\w+Binding)\.inflate\b/g,
    /\b(?:val|var)\s+(\w+)\s*:\s*(\w+Binding)\b/g,
    /\b(\w+)\s*:\s*(\w+Binding)\b/g, // typed parameter
  ];
  for (const re of declRes) {
    let dm;
    while ((dm = re.exec(unqualified))) {
      // Inflate form: the capture that matched a binding type is the right one.
      const type = (dm[3] && /Binding$/.test(dm[3])) ? dm[3] : dm[2];
      if (!type || !/Binding$/.test(type)) continue;
      const name = dm[1].replace(/^this\./, '');
      if (!name || SKIP_BINDING_NAMES.has(name)) continue;
      decls.push({ pos: dm.index, name, type });
    }
  }
  // De-duplicate identical (pos,name,type) records produced by overlapping patterns.
  {
    const seen = new Set();
    for (let i = decls.length - 1; i >= 0; i--) {
      const k = `${decls[i].pos}|${decls[i].name}|${decls[i].type}`;
      if (seen.has(k)) decls.splice(i, 1); else seen.add(k);
    }
  }
  decls.sort((a, b) => a.pos - b.pos);

  // Delegated accessor: `private val binding get() = _binding!!` makes `binding.*` mean
  // `_binding.*`, which is how every fragment in this app reaches its layout.
  const delegate = new Map();
  for (const m of text.matchAll(/\bval\s+(\w+)\s+get\(\)\s*=\s*(\w+)/g)) {
    if (m[1] !== m[2]) delegate.set(m[1], m[2]);
  }

  const typeOf = (name, pos) => {
    const direct = decls.filter((d) => d.name === name && d.pos <= pos).pop();
    if (direct) return direct.type;
    const target = delegate.get(name);
    if (!target) return null;
    const indirect = decls.filter((d) => d.name === target && d.pos <= pos).pop();
    return indirect ? indirect.type : null;
  };

  const accessRe = /\b([a-z][A-Za-z0-9_]*)\.([a-z][A-Za-z0-9_]*)\b/g;
  let am;
  while ((am = accessRe.exec(text))) {
    const [, handle, field] = am;
    if (SKIP_FIELDS.has(field)) continue;
    const type = typeOf(handle, am.index);
    if (!type || !/Binding$/.test(type)) continue;
    const layoutName = bindingToLayout(type);
    const layout = layouts.get(layoutName);
    if (!layout) {
      fail(`${rel}: ${type} has no layout ${layoutName}.xml`);
      continue;
    }
    if (!layout.fields.has(field)) {
      fail(`${rel}: ${type}.${field} does not exist in ${layoutName}.xml`);
    }
  }

  // Snackbar/lambda false positives ("binding" as a plain variable name) are avoided by only
  // checking files that actually inflate a generated binding, above.
}

function bindingToLayout(bindingName) {
  const base = bindingName.replace(/Binding$/, '');
  // OrderDataItemBinding -> order_data_item, FragmentHomeBinding -> fragment_home
  const snake = base
    .replace(/([a-z0-9])([A-Z])/g, '$1_$2')
    .replace(/([A-Z]+)([A-Z][a-z])/g, '$1_$2')
    .toLowerCase();
  return snake;
}

// ------------------------------------------------------------ palette parity

/**
 * Light and night palettes must define the same names, and no layout may hard-code white.
 *
 * The dark-mode bug this catches: `@color/white` was used for text sitting on the accent, which
 * is bright green in the night palette — white-on-bright-green is ~1.9:1. The semantic role
 * (`@color/on_accent`) is dark in night mode precisely so that cannot happen.
 */
{
  const readColors = (file) =>
    new Set([...fs.readFileSync(file, 'utf8').matchAll(/<color name="(\w+)"/g)].map((m) => m[1]));
  const light = readColors(path.join(RES, 'values/colors.xml'));
  const night = readColors(path.join(RES, 'values-night/colors.xml'));
  for (const name of light) {
    if (!night.has(name)) fail(`color "${name}" is defined for light but missing from values-night`);
  }
  for (const name of night) {
    if (!light.has(name)) fail(`color "${name}" is defined for night but missing from values`);
  }
  for (const file of walk(path.join(RES, 'layout')).filter((f) => f.endsWith('.xml'))) {
    const text = fs.readFileSync(file, 'utf8');
    if (/@color\/white|@android:color\/white/.test(text)) {
      fail(`${path.relative(ROOT, file)}: literal white — use @color/on_accent or a text role`);
    }
  }
}

// ------------------------------------------------------------- touch targets

/**
 * Nothing clickable may be smaller than 48dp on either axis. A 34dp tab counter is how a
 * "responsive" layout ends up unusable on a phone; the pattern that fixes it (a small visual
 * inside a 48dp transparent frame) is in the same file, so the rule is easy to follow.
 */
for (const file of walk(path.join(RES, 'layout')).filter((f) => f.endsWith('.xml'))) {
  const text = fs.readFileSync(file, 'utf8');
  for (const m of text.matchAll(/<([A-Za-z.]+)\s([^>]*?)\/?>/gs)) {
    const body = m[2];
    if (!/android:clickable="true"/.test(body)) continue;
    if (/android:minHeight="(?:4[89]|[5-9]\d|\d{3,})dp"/.test(body)) continue;
    // `match_parent` inherits the parent's size, which for a segment half is the sized
    // container above it — those are fine and cannot be judged from this file alone.
    if (/android:layout_height="match_parent"/.test(body)) continue;
    const w = /android:layout_width="(\d+)dp"/.exec(body);
    const h = /android:layout_height="(\d+)dp"/.exec(body);
    if ((w && Number(w[1]) < 48) || (h && Number(h[1]) < 48)) {
      const id = /android:id="@\+id\/(\w+)"/.exec(body);
      fail(`${path.relative(ROOT, file)}: ${id ? id[1] : m[1]} is clickable but smaller than 48dp`);
    }
  }
}

// --------------------------------------------------------------- dead copy check

/**
 * Every declared string must be used somewhere. Dead keys were what made the copy "diet" in 1.4.0
 * unverifiable — the numbers went down only because nobody could tell which keys were live.
 * This runs as a warning, not an error: a string added for a screen still in progress is legal,
 * but the count is printed so it cannot grow silently.
 */
{
  const declared = [];
  for (const file of walk(path.join(RES, 'values')).filter((f) => f.endsWith('.xml'))) {
    for (const m of fs.readFileSync(file, 'utf8')
      .matchAll(/<string\s+name="([\w.]+)"/g)) declared.push(m[1]);
  }
  const used = new Set();
  const sources = [
    ...walk(path.join(RES)).filter((f) => f.endsWith('.xml')),
    ...walk(JAVA).filter((f) => f.endsWith('.kt') || f.endsWith('.java')),
    path.join(MAIN, 'AndroidManifest.xml'),
  ].filter((f) => !f.includes('/values/'));
  for (const file of sources) {
    const text = fs.readFileSync(file, 'utf8');
    for (const m of text.matchAll(/@string\/([\w.]+)/g)) used.add(m[1]);
    for (const m of text.matchAll(/R\.string\.([\w.]+)/g)) used.add(m[1]);
  }
  const dead = declared.filter((k) => !used.has(k));
  if (dead.length) {
    warn(`${dead.length} unused string(s): ${dead.slice(0, 8).join(', ')}${dead.length > 8 ? '…' : ''}`);
  }
  console.log(`strings:   ${declared.length} declared, ${dead.length} unused`);
}

// --------------------------------------------------------- single-colour policy

/**
 * Requirement: one solid green identity, no gradients.
 *
 * Layouts and Kotlin must not invent colours — a literal `#RRGGBB` in a layout is how the old
 * rainbow avatar palette and the stray `#10B981` chip survived a redesign unnoticed. Shape
 * drawables may use alpha-white overlays on the deep-green hero (`#1FFFFFFF`), which are scrims on
 * a known surface, not new hues; anything else is an error.
 */
{
  const ALLOWED_SCRIM = /^#(?:[0-9A-Fa-f]{2})?FFFFFF$|^#00000000$/;
  for (const file of walk(path.join(RES, 'layout')).filter((f) => f.endsWith('.xml'))) {
    const text = fs.readFileSync(file, 'utf8');
    for (const m of text.matchAll(/android:(?:textColor|background|tint)="(#[0-9A-Fa-f]{6,8})"/g)) {
      fail(`${path.relative(ROOT, file)}: hard-coded colour ${m[1]} — use a @color role`);
    }
  }
  // Launcher/splash artwork paints brand colours on purpose; the ic_* icon set must stay
  // untinted black so one usage-site tint controls it.
  const ARTWORK = /(launcher|splash|ic_logo)/;
  for (const file of walk(path.join(RES, 'drawable')).filter((f) => f.endsWith('.xml'))) {
    const text = fs.readFileSync(file, 'utf8');
    if (/<gradient\b/.test(text)) {
      fail(`${path.relative(ROOT, file)}: gradient — the design is solid fills only`);
    }
    // A drawable that paints a path is an icon: its fill must be #000 + a tint at usage site.
    const isArtwork = ARTWORK.test(path.basename(file));
    for (const m of (isArtwork ? [] : text.matchAll(/android:fillColor="(#[0-9A-Fa-f]{6,8})"/g))) {
      // #000 (filled) and #00000000 (stroke-only) are both correct for a tinted icon.
      if (!/^#(?:FF)?000000$|^#00000000$/i.test(m[1])) {
        fail(`${path.relative(ROOT, file)}: icon fill ${m[1]} — icons must be #000 and tinted`);
      }
    }
    for (const m of (isArtwork ? [] : text.matchAll(/<solid android:color="(#[0-9A-Fa-f]{6,8})"/g))) {
      if (!ALLOWED_SCRIM.test(m[1])) {
        fail(`${path.relative(ROOT, file)}: hard-coded fill ${m[1]} — use a @color role`);
      }
    }
  }
}

// ------------------------------------------------- non-public Material attributes

/**
 * `com.google.android.material.R.attr.*` is obfuscated/private in the Material Components
 * artifacts: referencing one compiles in some configurations and fails in others (this broke
 * `UiKit.textButton` once already, on both debug and release). Any such reference is a bug —
 * the fix is to style the widget by hand or use a public `Widget.Material3.*` style.
 */
for (const file of kotlinFiles) {
  const text = fs.readFileSync(file, 'utf8');
  if (/com\.google\.android\.material\.R\.(attr|styleable|id)/.test(text)) {
    fail(`${path.relative(ROOT, file)}: references a non-public Material R symbol — style the widget by hand`);
  }
}

// Material widgets must not be told to use a style that the app does not define itself.
{
  const THEMES = [
    path.join(RES, 'values/themes.xml'),
    path.join(RES, 'values/styles.xml'),
    path.join(RES, 'values-night/themes.xml'),
  ].filter((f) => fs.existsSync(f));
  const declared = new Set();
  for (const f of THEMES) {
    for (const m of fs.readFileSync(f, 'utf8').matchAll(/<style\s+name="([^"]+)"/g)) declared.add(m[1]);
  }
  for (const file of kotlinFiles) {
    for (const m of fs.readFileSync(file, 'utf8').matchAll(/setStyle\(\s*(R\.style\.\w+)/g)) {
      const name = m[1].replace('R.style.', '').replace(/_/g, '.');
      if (!declared.has(name)) fail(`${path.relative(ROOT, file)}: setStyle(${name}) — style is not declared`);
    }
  }
}

// ------------------------------------------------------------------- report

const RES_COUNT = (() => {
  let n = 0;
  for (const [, map] of resources) n += map.size;
  return n;
})();

console.log(`resources: ${RES_COUNT} names across ${resources.size} types`);
console.log(`layouts:   ${layouts.size}`);
console.log(`kotlin:    ${kotlinFiles.length} files`);

if (warnings.length) {
  console.log(`\n${warnings.length} warning(s):`);
  for (const w of warnings) console.log('  warn  ' + w);
}

if (errors.length) {
  console.log(`\n${errors.length} error(s):`);
  for (const e of errors) console.log('  FAIL  ' + e);
  process.exit(1);
}
console.log('\nstatic audit passed');
