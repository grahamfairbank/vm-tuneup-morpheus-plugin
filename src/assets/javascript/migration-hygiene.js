/* migration-hygiene: browser side of the Migration Hygiene tabs (0.4.0).
 * Loaded on every page by MigrationHygieneUiLoaderProvider for users who may view results. Tabs render a shell
 * (.migration-hygiene-tab > .mh-slot[data-mh-kind][data-mh-id]); this script fills each slot from the plugin's
 * own same-origin routes after the page has loaded, polls a running cluster scan every 5 seconds, and handles the
 * buttons with one delegated click handler that only acts inside the plugin's own tab.
 * CSV downloads (0.3.0) are built here, from the clusterData JSON route, so they don't depend on how Morpheus
 * treats route response headers; the server's /csv route stays as the "Direct download" fallback.
 * POSTs carry core's CSRF token (Spring Security) from <meta name="_csrf">, or, when the page has none, from a
 * core page fetched once (pattern from com-morpheus-plugin, same author, MIT).
 * Every GET to the plugin's routes sends X-Requested-With: the routes start scans only for these requests (0.4.0).
 * A fragment is put into the page only when it is a same-origin, non-redirected 2xx text/html answer that starts
 * with the plugin's own root element (fragmentOk); anything else, such as a sign-in page after the session
 * expired, shows a short note instead. */
(function () {
  'use strict';
  if (window.__migrationHygiene) return;
  window.__migrationHygiene = true;
  var API = '/plugin/migrationHygiene';
  var ROOT = '.migration-hygiene-tab';
  var ID = /^\d{1,18}$/;
  var POLL_MS = 5000, POLL_MAX = 240; // up to 20 minutes
  var ACTIONS = { 'vm-refresh': true, 'vm-probe': true, 'cluster-rescan': true, 'csv-cluster': true, 'csv-all': true, 'show-all': true };
  var CSV_POLL_MS = 3000, CSV_CAP_MS = 15 * 60 * 1000; // poll clusterData every 3 s, at most 15 minutes per cluster
  var WORST_ROWS = 15; // rows shown in the cluster's VM table until "Show all"
  var ORIGINS = { all: true, migrated: true, 'not-migrated': true, unknown: true };
  var MARKER = '<div class="migration-hygiene';
  var SIGN_IN = 'Reload the page and sign in again.';

  function csrfFromMeta(doc) {
    var m = doc.querySelector('meta[name="_csrf"]');
    if (!m || !m.getAttribute('content')) return null;
    var h = doc.querySelector('meta[name="_csrf_header"]');
    return { header: (h && h.getAttribute('content')) || 'X-XSRF-TOKEN', token: m.getAttribute('content') };
  }

  var csrfPromise = null;
  function getCsrf() {
    if (csrfPromise) return csrfPromise;
    var here = csrfFromMeta(document);
    if (here) return (csrfPromise = Promise.resolve(here));
    csrfPromise = ['/operations/dashboard', '/'].reduce(function (prev, url) {
      return prev.then(function (found) {
        if (found) return found;
        return fetch(url, { credentials: 'same-origin', headers: { Accept: 'text/html' } })
          .then(function (r) { return r.ok ? r.text() : ''; })
          .then(function (html) { return html ? csrfFromMeta(new DOMParser().parseFromString(html, 'text/html')) : null; })
          .catch(function () { return null; });
      });
    }, Promise.resolve(null));
    return csrfPromise;
  }

  function answer(r) {
    return r.text().then(function (t) {
      return { status: r.status, text: t, url: r.url || '', redirected: !!r.redirected, type: (r.headers && r.headers.get('content-type')) || '' };
    });
  }

  // True only for an answer the plugin's own route rendered: 2xx, not redirected, from this origin, text/html, and
  // starting with the plugin's root element. Anything else is never put into the page.
  function fragmentOk(res, origin) {
    if (!res || res.status < 200 || res.status > 299 || res.redirected) return false;
    var from;
    try { from = new URL(res.url, origin).origin; } catch (e) { return false; }
    if (from !== origin) return false;
    if (!/^text\/html(\s*;|$)/i.test(String(res.type || '').trim())) return false;
    return String(res.text || '').replace(/^\s+/, '').indexOf(MARKER) === 0;
  }

  function get(path) {
    return fetch(API + path, { credentials: 'same-origin', headers: { 'Accept': 'text/html', 'X-Requested-With': 'XMLHttpRequest' } }).then(answer);
  }

  // JSON from our own routes. Resolves to the parsed body on HTTP 200; otherwise rejects with an Error whose
  // message is "HTTP <status>: <short text>" (the route's error, or the start of the body).
  function getJson(path) {
    return fetch(API + path, { credentials: 'same-origin', headers: { 'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest' } })
      .then(function (r) {
        if (r.redirected) throw new Error(SIGN_IN);
        return r.text().then(function (t) {
          var body = null;
          try { body = JSON.parse(t); } catch (e) { body = null; }
          if (r.status === 200 && body && typeof body === 'object') return body;
          var why = (body && body.error) ? String(body.error) : (r.status === 200 ? 'the answer was not JSON' : (textOf(t) || HTTP[r.status] || 'no details'));
          throw new Error('HTTP ' + r.status + ': ' + String(why).replace(/\s+/g, ' ').trim().slice(0, 200));
        });
      });
  }

  function post(path, params) {
    return getCsrf().then(function (csrf) {
      var h = { 'Accept': 'text/html', 'X-Requested-With': 'XMLHttpRequest', 'Content-Type': 'application/x-www-form-urlencoded' };
      if (csrf) h[csrf.header] = csrf.token;
      return fetch(API + path, { method: 'POST', credentials: 'same-origin', headers: h, body: new URLSearchParams(params).toString() }).then(answer);
    });
  }

  function slotId(slot) {
    var id = slot && slot.getAttribute('data-mh-id');
    return ID.test(id || '') ? id : null;
  }

  function note(slot, text) {
    var s = slot.querySelector('.mh-act-status');
    if (s) { s.textContent = text; return; }
    var d = document.createElement('div');
    d.className = 'mh-loading';
    d.textContent = text;
    slot.innerHTML = '';
    slot.appendChild(d);
  }

  var HTTP = { 403: 'Your role doesn’t allow this (Migration Hygiene permissions).', 404: 'Not found.', 503: 'Morpheus didn’t answer in time. Try again.' };

  // Plain text of an HTML message (DOMParser runs no scripts), one line, at most 200 characters.
  function textOf(html) {
    try {
      var doc = new DOMParser().parseFromString(String(html || ''), 'text/html');
      Array.prototype.forEach.call(doc.querySelectorAll('style,script'), function (n) { n.remove(); });
      return String(doc.body ? doc.body.textContent : '').replace(/\s+/g, ' ').trim().slice(0, 200);
    } catch (e) { return ''; }
  }

  // The HTML comes only from our own same-origin route (API is a fixed path; ids are digits), and the route
  // escapes every value it renders.
  function inject(slot, res) {
    if (res.redirected) { note(slot, 'Could not load the results: your session may have expired. ' + SIGN_IN); return; }
    if (res.status < 200 || res.status > 299) {
      // Our routes answer errors with a short escaped message: shown as text only, with the status.
      var why = String(res.text || '').replace(/^\s+/, '').indexOf(MARKER) === 0 ? textOf(res.text) : '';
      note(slot, 'Could not load the results (HTTP ' + res.status + '): ' + (why || HTTP[res.status] || 'no details'));
      return;
    }
    if (!fragmentOk(res, window.location.origin)) {
      note(slot, 'Could not load the results: the answer was not from Migration Hygiene (your session may have expired). ' + SIGN_IN);
      return;
    }
    slot.innerHTML = res.text;
    applyFilter(slot);
    showCsv(slot);
    if (slot.querySelector('[data-mh-poll="1"]')) schedulePoll(slot);
    else slot.removeAttribute('data-mh-polls');
  }

  function fail(slot) { return function (e) { note(slot, 'Could not load the results: ' + e); }; }

  function load(slot, extra) {
    var id = slotId(slot);
    if (!id) return;
    var kind = slot.getAttribute('data-mh-kind');
    var path = kind === 'vm' ? '/vm?serverId=' + id : kind === 'cluster' ? '/cluster?clusterId=' + id : null;
    if (!path) return;
    get(path + (extra || '')).then(function (r) { inject(slot, r); }).catch(fail(slot));
  }

  function schedulePoll(slot) {
    var n = parseInt(slot.getAttribute('data-mh-polls') || '0', 10);
    if (n >= POLL_MAX) return;
    slot.setAttribute('data-mh-polls', String(n + 1));
    setTimeout(function () {
      if (!document.documentElement.contains(slot)) return;
      if (!visible(slot)) { slot.removeAttribute('data-mh-init'); slot.removeAttribute('data-mh-polls'); return; } // resume when shown again
      load(slot, '');
    }, POLL_MS);
  }

  // ---- origin filter (cluster view) ---------------------------------------------------------------------
  // State lives on the slot, so it survives the 5-second re-render of a running scan.

  function applyFilter(slot) {
    var sel = slot.querySelector('select.mh-origin-filter');
    if (!sel) return;
    var want = slot.getAttribute('data-mh-filter') || 'all';
    if (!ORIGINS.hasOwnProperty(want)) want = 'all';
    sel.value = want;
    var expanded = slot.getAttribute('data-mh-expanded') === '1';
    var rows = slot.querySelectorAll('tr.mh-vmrow');
    var matched = 0, shown = 0;
    Array.prototype.forEach.call(rows, function (tr) {
      var match = want === 'all' || tr.getAttribute('data-mh-origin') === want;
      if (match) matched++;
      var visible = match && (expanded || shown < WORST_ROWS);
      if (visible) shown++;
      tr.style.display = visible ? '' : 'none';
    });
    var none = slot.querySelector('tr.mh-vmrow-none');
    if (none) none.hidden = matched !== 0;
    var count = slot.querySelector('.mh-filter-count');
    if (count) count.textContent = matched > shown ? 'Showing the worst ' + shown + ' of ' + matched + ' VMs.' : (matched === 1 ? '1 VM.' : matched + ' VMs.');
    var more = slot.querySelector('[data-mh-action="show-all"]');
    if (more) more.hidden = matched <= shown;
  }

  // ---- CSV built in the browser ---------------------------------------------------------------------------

  // Straight port of Views.csvCell (src/main/groovy/.../Views.groovy), which the server's CSV and its tests use:
  // keep the two identical. A text cell that a spreadsheet would read as a formula (starting with a tab or CR, or
  // whose first non-whitespace character is = + - @ or |; FORMULA is Views.FORMULA) gets a leading apostrophe;
  // cells with commas, quotes or line breaks are quoted, quotes doubled. Shared cases: src/test/resources/csv-cell-cases.json.
  var FORMULA = /^(?:[\t\r]|[ \t\n\x0B\f\r\u00A0]*[=+\-@|])/;
  function csvCell(v) {
    if (v === null || v === undefined) return '';
    var s = String(v);
    if (typeof v !== 'number' && s && FORMULA.test(s)) s = "'" + s;
    if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) s = '"' + s.replace(/"/g, '""') + '"';
    return s;
  }

  // Header plus rows, CRLF line ends, as Views.csv does. The caller adds the UTF-8 BOM (for Excel).
  function csvText(columns, rows) {
    var out = [columns.map(csvCell).join(',')];
    rows.forEach(function (r) { out.push(columns.map(function (c) { return csvCell(r[c]); }).join(',')); });
    return out.join('\r\n') + '\r\n';
  }

  // Port of Views.csvFilename: migration-hygiene-<cluster>-<yyyy-MM-dd>.csv (UTC date), safe characters only.
  function csvFilename(cluster) {
    var slug = String(cluster || '').replace(/[^A-Za-z0-9._-]+/g, '-').replace(/^[-.]+|-+$/g, '');
    if (slug.length > 60) slug = slug.substring(0, 60);
    if (!slug) slug = 'cluster';
    return 'migration-hygiene-' + slug + '-' + new Date().toISOString().slice(0, 10) + '.csv';
  }

  function saveCsv(text, filename) {
    var url = URL.createObjectURL(new Blob(['\ufeff', text], { type: 'text/csv;charset=utf-8' }));
    var a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.style.display = 'none';
    document.body.appendChild(a);
    a.click();
    setTimeout(function () { URL.revokeObjectURL(url); a.remove(); }, 2000);
  }

  function setCsv(slot, text) { slot.setAttribute('data-mh-csv-text', text || ''); showCsv(slot); }

  function showCsv(slot) {
    var s = slot.querySelector('.mh-csv-status');
    if (s) s.textContent = slot.getAttribute('data-mh-csv-text') || '';
  }

  function wait(ms) { return new Promise(function (ok) { setTimeout(ok, ms); }); }

  // Polls clusterData until the scan is done or failed, or the cap is reached; resolves to
  // { data, note } where note says why the rows may be incomplete.
  function clusterRows(slot, id, label) {
    var start = Date.now();
    function round() {
      return getJson('/clusterData?clusterId=' + id).then(function (d) {
        if (d.status === 'done') return { data: d, note: '' };
        if (d.status === 'not-scanned') throw new Error(d.error || 'not scanned yet; reload the page and open the tab again');
        if (d.status === 'failed') return { data: d, note: 'The scan of ' + (d.clusterName || 'the cluster') + ' stopped (' + (d.error || 'unknown error') + '); the file has the ' + d.rows.length + ' VMs scanned before that.' };
        if (Date.now() - start >= CSV_CAP_MS) return { data: d, note: 'The scan of ' + (d.clusterName || 'the cluster') + ' had not finished after 15 minutes; the file has the ' + d.rows.length + ' VMs scanned so far.' };
        setCsv(slot, label + (d.total ? 'scanned ' + d.scanned + ' of ' + d.total + '…' : 'listing the cluster’s VMs…'));
        return wait(CSV_POLL_MS).then(round);
      });
    }
    return round();
  }

  function busyCsv(slot, on) {
    if (on) slot.setAttribute('data-mh-csv-busy', '1'); else slot.removeAttribute('data-mh-csv-busy');
  }

  function download(slot, btn) {
    if (slot.getAttribute('data-mh-csv-busy') === '1') return;
    var all = btn.getAttribute('data-mh-action') === 'csv-all';
    var id = slotId(slot);
    if (!all && !id) return;
    busyCsv(slot, true);
    var done = function (msg) { busyCsv(slot, false); setCsv(slot, msg); };
    var failed = function (e) { done('Could not prepare the CSV: ' + ((e && e.message) || e)); };
    if (!all) {
      setCsv(slot, 'Preparing CSV…');
      clusterRows(slot, id, 'Preparing CSV: ').then(function (res) {
        var d = res.data;
        saveCsv(csvText(d.columns, d.rows), csvFilename(d.clusterName || btn.getAttribute('data-mh-name') || ('cluster-' + id)));
        done('CSV downloaded: ' + d.rows.length + ' VMs.' + (res.note ? ' ' + res.note : ''));
      }).catch(failed);
      return;
    }
    setCsv(slot, 'Preparing CSV: listing the clusters you can see…');
    getJson('/clusters').then(function (list) {
      var clusters = (list.clusters || []).filter(function (c) { return c && ID.test(String(c.id)); });
      if (!clusters.length) { done('No HVM clusters to export.'); return; }
      var columns = null, rows = [], notes = [];
      // One cluster at a time, so an all-clusters export never starts every cluster's scan at once.
      return clusters.reduce(function (prev, c, i) {
        return prev.then(function () {
          var label = 'Preparing CSV: cluster ' + (i + 1) + ' of ' + clusters.length + ' (' + (c.name || c.id) + '), ';
          setCsv(slot, label + 'starting…');
          return clusterRows(slot, c.id, label).then(function (res) {
            columns = columns || res.data.columns;
            rows = rows.concat(res.data.rows);
            if (res.note) notes.push(res.note);
          }, function (e) {
            notes.push('Cluster ' + (c.name || c.id) + ' is not in the file: ' + ((e && e.message) || e) + '.');
          });
        });
      }, Promise.resolve()).then(function () {
        if (!columns) { done('Could not prepare the CSV. ' + notes.join(' ')); return; }
        saveCsv(csvText(columns, rows), csvFilename('all-clusters'));
        done('CSV downloaded: ' + rows.length + ' VMs from ' + clusters.length + ' cluster' + (clusters.length === 1 ? '' : 's') + '.' + (notes.length ? ' ' + notes.join(' ') : ''));
      });
    }).catch(failed);
  }

  function act(btn, ev) {
    var slot = btn.closest('.mh-slot');
    var root = btn.closest(ROOT);
    if (!slot || !root || !root.contains(slot)) return;
    var action = btn.getAttribute('data-mh-action');
    if (!ACTIONS.hasOwnProperty(action)) return;
    var id = slotId(slot);
    var kind = slot.getAttribute('data-mh-kind');
    if (action === 'csv-cluster' || action === 'csv-all') { ev.preventDefault(); if (kind === 'cluster') download(slot, btn); return; }
    if (action === 'show-all') { slot.setAttribute('data-mh-expanded', '1'); applyFilter(slot); return; }
    if (!id) return;
    btn.disabled = true;
    if (action === 'vm-refresh' && kind === 'vm') { note(slot, 'Scanning…'); load(slot, '&fresh=1'); return; }
    if (action === 'vm-probe' && kind === 'vm') {
      note(slot, 'Checking the guest agent (up to 20 seconds)…');
      post('/vmProbe', { serverId: id }).then(function (r) { inject(slot, r); }).catch(fail(slot));
      return;
    }
    if (action === 'cluster-rescan' && kind === 'cluster') {
      note(slot, 'Starting a new scan…');
      post('/clusterRescan', { clusterId: id }).then(function (r) { inject(slot, r); }).catch(fail(slot));
    }
  }

  document.addEventListener('click', function (ev) {
    var t = ev.target;
    if (!t || !t.closest || !t.closest(ROOT)) return; // only clicks inside the plugin's own tab
    var btn = t.closest('.mh-act');
    if (btn && !btn.disabled) act(btn, ev);
  });

  document.addEventListener('change', function (ev) {
    var t = ev.target;
    if (!t || !t.closest || !t.closest(ROOT) || !t.matches || !t.matches('select.mh-origin-filter')) return;
    var slot = t.closest('.mh-slot');
    if (!slot) return;
    slot.setAttribute('data-mh-filter', ORIGINS.hasOwnProperty(t.value) ? t.value : 'all');
    slot.removeAttribute('data-mh-expanded');
    applyFilter(slot);
  });

  // Only load slots the user can see (the open tab). Morpheus builds every tab's content with the page but hides
  // all but one; loading hidden slots would start background scans nobody asked for.
  function visible(el) { return !!(el.offsetParent || el.getClientRects().length); }
  function scan() {
    Array.prototype.forEach.call(document.querySelectorAll(ROOT + ' .mh-slot[data-mh-kind]:not([data-mh-init])'), function (slot) {
      if (!visible(slot)) return;
      slot.setAttribute('data-mh-init', '1');
      if (slot.getAttribute('data-mh-loaded') !== '1') load(slot, '');
    });
  }
  var pending = false;
  function soon() { if (pending) return; pending = true; setTimeout(function () { pending = false; scan(); }, 150); }
  scan();
  new MutationObserver(soon).observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'style'] });
  document.addEventListener('click', function () { setTimeout(scan, 200); }, true);
})();
