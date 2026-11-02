#!/usr/bin/env bash
# Local check of the browser script's pure functions, outside the Gradle build (no JS runner in the build).
# Runs in a node:20-alpine container with the repository mounted read-only; nothing is installed or written.
#
#   tools/check-js.sh
#
# Checks:
#   - csvCell (with FORMULA) against src/test/resources/csv-cell-cases.json, the cases Views.csvCell is tested with;
#   - fragmentOk: only a same-origin, non-redirected 2xx text/html answer starting with the plugin's root element
#     is put into the page (MH-09). The cases are listed below.
set -euo pipefail
cd "$(dirname "$0")/.."
docker run --rm -i -v "$PWD":/src:ro -w /src "${NODE_IMAGE:-node:20-alpine}" node - <<'JS'
const fs = require('fs');
const src = fs.readFileSync('src/assets/javascript/migration-hygiene.js', 'utf8');
function grab(re, what) { const m = src.match(re); if (!m) throw new Error('not found: ' + what); return m[0]; }
const code = [
  grab(/var MARKER = [^\n]*\n/, 'MARKER'),
  grab(/var FORMULA = [^\n]*\n/, 'FORMULA'),
  grab(/function csvCell\(v\) \{[\s\S]*?\n  \}\n/, 'csvCell'),
  grab(/function fragmentOk\(res, origin\) \{[\s\S]*?\n  \}\n/, 'fragmentOk'),
].join('\n') + '\nreturn { csvCell: csvCell, fragmentOk: fragmentOk };';
const f = new Function(code)();
let bad = 0;
const csv = JSON.parse(fs.readFileSync('src/test/resources/csv-cell-cases.json', 'utf8'));
for (const k of csv) {
  const got = f.csvCell(k.value);
  if (got !== k.expected) { bad++; console.log('csvCell', JSON.stringify(k.value), 'got', JSON.stringify(got), 'want', JSON.stringify(k.expected)); }
}
const O = 'https://manager.example.com';
const ok = { status: 200, redirected: false, url: O + '/plugin/migrationHygiene/vm?serverId=1', type: 'text/html;charset=utf-8', text: '<div class="migration-hygiene mh-vm">x</div>' };
const frag = [
  ['our fragment', ok, true],
  ['our fragment after a template comment (leading whitespace)', Object.assign({}, ok, { text: ' \n<div class="migration-hygiene mh-cluster" data-mh-poll="1">' }), true],
  ['our message fragment', Object.assign({}, ok, { text: '<div class="migration-hygiene"><div class="mh-note">x</div></div>' }), true],
  ['type without charset', Object.assign({}, ok, { type: 'text/html' }), true],
  ['relative url', Object.assign({}, ok, { url: '/plugin/migrationHygiene/vm?serverId=1' }), true],
  ['redirected to the sign-in page', Object.assign({}, ok, { redirected: true, url: O + '/login/auth' }), false],
  ['sign-in page served without a redirect flag', Object.assign({}, ok, { text: '<!DOCTYPE html><html><body><form action="/login">' }), false],
  ['other origin', Object.assign({}, ok, { url: 'https://evil.example.net/x' }), false],
  ['other port', Object.assign({}, ok, { url: 'https://manager.example.com:8443/x' }), false],
  ['JSON', Object.assign({}, ok, { type: 'application/json' }), false],
  ['text/plain', Object.assign({}, ok, { type: 'text/plain' }), false],
  ['text/htmlx', Object.assign({}, ok, { type: 'text/htmlx' }), false],
  ['no type', Object.assign({}, ok, { type: '' }), false],
  ['403', Object.assign({}, ok, { status: 403 }), false],
  ['302', Object.assign({}, ok, { status: 302 }), false],
  ['marker not at the start', Object.assign({}, ok, { text: '<p>hi</p><div class="migration-hygiene">' }), false],
  ['empty body', Object.assign({}, ok, { text: '' }), false],
  ['null answer', null, false],
];
for (const [name, res, want] of frag) {
  const got = f.fragmentOk(res, O);
  if (got !== want) { bad++; console.log('fragmentOk', name, 'got', got, 'want', want); }
}
console.log(`csvCell: ${csv.length} cases, fragmentOk: ${frag.length} cases, ${bad} failed`);
process.exit(bad ? 1 : 0);
JS
