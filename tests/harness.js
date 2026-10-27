'use strict';
/**
 * Deterministic test harness for the Goal Tracker HTML app.
 * - jsdom renders the real index.html (no rewrite, no extraction).
 * - @sinonjs/fake-timers controls Date.now / performance.now / setTimeout / setInterval.
 * - fake-indexeddb provides the persistence layer (shared across "reload"s).
 *
 * The app state lives inside an IIFE, so tests verify behavior through the
 * authoritative persistence layer (IndexedDB 'meta'/'state') plus the DOM,
 * exactly like the real app does.
 */
const fs = require('fs');
const path = require('path');
const { JSDOM, VirtualConsole } = require('jsdom');
const { makeBridge } = require('./bridge-mock');
const fakeTimers = require('@sinonjs/fake-timers');
require('fake-indexeddb/auto'); // sets globalThis.indexedDB & friends

// jsdom's window.close() never closes IndexedDB connections, and fake-indexeddb queues
// every `open()` behind a blocked `deleteDatabase()` — so wiping the shared DB between
// tests deadlocks unless all connections are closed first. Track every connection the
// app (or harness) opens and close them before any wipe.
const _openConns = new Set();
(function trackIdbOpens(){
  const f = globalThis.indexedDB;
  if(!f || f.__patched) return;
  const origOpen = f.open.bind(f);
  f.open = function(...args){
    const req = origOpen(...args);
    try {
      req.addEventListener('success', () => { try { _openConns.add(req.result); } catch (e) {} });
      req.addEventListener('success', () => {
        try { req.result.addEventListener('close', () => _openConns.delete(req.result)); } catch (e) {}
      });
    } catch (e) {}
    return req;
  };
  f.__patched = true;
})();
function closeAllIdbConnections(){
  for (const c of _openConns) { try { c.close(); } catch (e) {} }
  _openConns.clear();
}

// Browsers treat unhandled promise rejections as console errors; Node would kill the
// process. Mirror browser behavior: record them instead of crashing.
process.on('unhandledRejection', (e) => {
  if (global.__recordRejection) global.__recordRejection(e);
});

const HTML_PATH = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'app', 'index.html');
const HTML = fs.readFileSync(HTML_PATH, 'utf8');

function flushReal(times = 6) {
  let p = Promise.resolve();
  for (let i = 0; i < times; i++) p = p.then(() => new Promise((r) => setImmediate(r)));
  return p;
}

/**
 * Boot a fresh page (a "WebView load") over the shared fake-IndexedDB.
 * @param {object} opts
 * @param {number} [opts.now]  fake wall-clock start (ms epoch). Default: 2026-10-20 10:00:00 local (UTC in CI).
 * @param {object} [opts.statePatch] shallow-merged into the persisted state before boot (settings etc).
 * @param {array}  [opts.tasks] if given, replaces default tasks seeding.
 * @param {boolean}[opts.freshDb] wipe IndexedDB first.
 */
async function bootApp(opts = {}) {
  const now = opts.now ?? new Date(2026, 9, 20, 10, 0, 0).getTime();

  if (opts.freshDb) await wipeDb();

  const logs = [];
  const pageErrors = [];
  const virtualConsole = new VirtualConsole();
  virtualConsole.on('log', (...a) => logs.push(['log', a.map(String).join(' ')]));
  virtualConsole.on('warn', (...a) => logs.push(['warn', a.map(String).join(' ')]));
  virtualConsole.on('error', (...a) => {
    logs.push(['error', a.map(String).join(' ')]);
    pageErrors.push(a.map(String).join(' '));
  });
  virtualConsole.on('jsdomError', (e) => pageErrors.push('jsdomError: ' + (e && e.stack || e)));

  const dom = new JSDOM(HTML, {
    url: 'https://appassets.androidplatform.net/app/index.html',
    runScripts: 'dangerously',
    pretendToBeVisual: true,
    virtualConsole,
    beforeParse(window) {
      // --- shared IndexedDB ---
      window.indexedDB = globalThis.indexedDB;
      window.IDBKeyRange = globalThis.IDBKeyRange;
      window.IDBCursor = globalThis.IDBCursor;
      window.structuredClone = globalThis.structuredClone;
      // --- fake timers (Date, perf clock, intervals) ---
      window.__clock = fakeTimers.withGlobal(window).install({
        now,
        toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'Date', 'performance', 'requestAnimationFrame', 'cancelAnimationFrame'],
      });
      // --- optional Android bridge mock (notification/bridge tests) ---
      if (opts.bridge) window.AndroidBridge = makeBridge(window, opts.bridge);
      // --- small polyfills jsdom lacks ---
      if (!window.CSS) window.CSS = {};
      if (!window.CSS.escape) window.CSS.escape = (s) => String(s).replace(/[^a-zA-Z0-9_-]/g, (c) => '\\' + c);
    },
  });

  const win = dom.window;
  const clock = win.__clock;
  global.__recordRejection = (e) => pageErrors.push('unhandledRejection: ' + ((e && e.stack) || e));

  async function advance(ms, step = 250) {
    let remaining = ms;
    while (remaining > 0) {
      const d = Math.min(step, remaining);
      await clock.tickAsync(d);
      await flushReal(2);
      remaining -= d;
    }
    await flushReal(4);
    await clock.tickAsync(0);
    await flushReal(2);
  }

  const harness = {
    win,
    clock,
    logs,
    pageErrors,
    bridge: (opts.bridge && dom.window.AndroidBridge) || null,
    advance,
    now: () => clock.now,
    setNow(ms) { clock.setSystemTime(ms); },

    // ---- persistence-level inspection (authoritative store) ----
    async readState() {
      const dbs = await new Promise((res, rej) => {
        const r = globalThis.indexedDB.open('nbtcDB');
        r.onsuccess = () => res(r.result);
        r.onerror = () => rej(r.error);
      }).catch(() => null);
      if (!dbs) return null;
      return new Promise((res) => {
        const tx = dbs.transaction('meta', 'readonly').objectStore('meta').get('state');
        tx.onsuccess = () => res(tx.result || null);
        tx.onerror = () => res(null);
      });
    },
    async readTimeline() {
      return idbAllStore('timeline');
    },
    async readTasks() {
      return idbAllStore('tasks');
    },
    async readSessions() {
      return idbAllStore('sessions');
    },

    // ---- DOM helpers ----
    $(sel) { return win.document.querySelector(sel); },
    $$(sel) { return [...win.document.querySelectorAll(sel)]; },
    text(sel) { const el = win.document.querySelector(sel); return el ? el.textContent : null; },
    style(sel, prop) { const el = win.document.querySelector(sel); return el ? el.style[prop] : null; },
    async click(sel) {
      const el = typeof sel === 'string' ? win.document.querySelector(sel) : sel;
      if (!el) throw new Error('click: no element ' + sel);
      el.dispatchEvent(new win.MouseEvent('click', { bubbles: true, cancelable: true }));
      await flushReal(3);
    },

    async reload(opts2 = {}) { return bootApp({ now: clock.now, ...opts2 }); },

    close() {
      try { clock.uninstall(); } catch (e) { /* noop */ }
      try { win.close(); } catch (e) { /* noop */ }
    },
  };

  // wait for boot(): first paint removes .app-booting from body
  const deadline = Date.now() + 15000;
  while (Date.now() < deadline) {
    await flushReal(2);
    if (!win.document.body.classList.contains('app-booting')) break;
    // keep the fake clock idle-moving so intervals that flush the splash can run
    await clock.tickAsync(50);
  }
  await flushReal(4);
  await clock.tickAsync(0);
  await flushReal(2);

  if (opts.statePatch || opts.tasks) {
    // Not yet implemented here: use seedState() before boot instead.
  }
  return harness;
}

function idbAllStore(name) {
  return new Promise((res) => {
    const dbsReq = globalThis.indexedDB.open('nbtcDB');
    dbsReq.onsuccess = () => {
      const db = dbsReq.result;
      const r = db.transaction(name, 'readonly').objectStore(name).getAll();
      r.onsuccess = () => res(r.result || []);
      r.onerror = () => res([]);
    };
    dbsReq.onerror = () => res([]);
  });
}

/** Wipe the whole fake IndexedDB (fresh install scenario). */
async function wipeDb() {
  closeAllIdbConnections(); // release open connections so the delete is not blocked forever
  await new Promise((res) => {
    const req = globalThis.indexedDB.deleteDatabase('nbtcDB');
    req.onsuccess = () => res();
    req.onerror = () => res();
    req.onblocked = () => res();
  });
}

/** Seed state/settings before boot (merge over the app's own default). */
async function seedState(patch) {
  // Boot once to create the default record, patch it, then callers re-boot.
  // Simpler: write a raw record directly; getState() fills any missing fields.
  const base = {
    key: 'state',
    currentStreakHours: 0, bestStreakHours: 0, streakPomodoros: 0,
    completedPomodorosToday: 0, completedPomodorosLifetime: 0,
    hearts: 1, lastActiveDate: null, // let the app derive
    dailyStudySeconds: 0, dailyLifelinesEarned: 0,
    activeTimer: null, extraStudy: null, missionText: '',
    pendingReports: [], lastReportDayKey: null,
    goal: null, planners: [], plannerChapters: [], plannerPending: [],
    thoughtSessionCountsByDay: {}, thoughtNoBackupByDay: {},
    thoughtHistoryCountsByDay: {}, thoughtNextAvailableAtByDay: {},
    realityCheckPending: null,
    ...patch,
  };
  await new Promise((res) => {
    const dbReq = globalThis.indexedDB.open('nbtcDB');
    dbReq.onupgradeneeded = () => {
      const db = dbReq.result;
      if (!db.objectStoreNames.contains('meta')) db.createObjectStore('meta', { keyPath: 'key' });
      if (!db.objectStoreNames.contains('tasks')) db.createObjectStore('tasks', { keyPath: 'id', autoIncrement: true });
      if (!db.objectStoreNames.contains('timeline')) db.createObjectStore('timeline', { keyPath: 'id', autoIncrement: true });
      if (!db.objectStoreNames.contains('sessions')) db.createObjectStore('sessions', { keyPath: 'id', autoIncrement: true });
    };
    dbReq.onsuccess = () => {
      const db = dbReq.result;
      const needs = !db.objectStoreNames.contains('meta');
      if (needs) { db.close(); return res(); }
      const tx = db.transaction('meta', 'readwrite').objectStore('meta').put(base);
      tx.onsuccess = () => { db.close(); res(); };
      tx.onerror = () => { db.close(); res(); };
    };
    dbReq.onerror = () => res();
  });
}

async function seedTasks(tasks) {
  await new Promise((res) => {
    const dbReq = globalThis.indexedDB.open('nbtcDB');
    dbReq.onupgradeneeded = () => {
      const db = dbReq.result;
      if (!db.objectStoreNames.contains('meta')) db.createObjectStore('meta', { keyPath: 'key' });
      if (!db.objectStoreNames.contains('tasks')) db.createObjectStore('tasks', { keyPath: 'id', autoIncrement: true });
      if (!db.objectStoreNames.contains('timeline')) db.createObjectStore('timeline', { keyPath: 'id', autoIncrement: true });
      if (!db.objectStoreNames.contains('sessions')) db.createObjectStore('sessions', { keyPath: 'id', autoIncrement: true });
    };
    dbReq.onsuccess = () => {
      const db = dbReq.result;
      const tx = db.transaction('tasks', 'readwrite');
      const store = tx.objectStore('tasks');
      tasks.forEach((t) => store.put(t));
      tx.oncomplete = () => { db.close(); res(); };
      tx.onerror = () => { db.close(); res(); };
    };
    dbReq.onerror = () => res();
  });
}

module.exports = { bootApp, seedState, seedTasks, wipeDb, flushReal };
