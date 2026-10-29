'use strict';
/** Recall × Android shell integration suite.
 *  Boots the REAL bundled index.html in the jsdom harness with a recording
 *  AndroidBridge and verifies: settings surface, the nine display levels with
 *  level 5 as the true baseline, native mirroring/restore (data safety), and
 *  notification counts that agree with the app's own revision logic. */
const { bootApp } = require('./harness');
const { findBy, lastBy } = require('./bridge-mock');

const results = [];
let current = '';
function suite(n) { current = n; console.log('\n=== ' + n + ' ==='); }
function check(name, cond, expected, actual) {
  const pass = !!cond;
  results.push({ suite: current, name, pass, expected: String(expected), actual: String(actual) });
  console.log((pass ? 'PASS' : 'FAIL') + ' | ' + name + ' | expected=' + expected + ' actual=' + actual);
}

const OCT27 = new Date(2026, 9, 27, 9, 0, 0).getTime();
/** meta as bundled, untouched by the shell at boot */
const STOCK_META = 'width=device-width, initial-scale=1, viewport-fit=cover';
/** meta the engine writes for level 5 (the canonical 100% baseline) */
const BASELINE_META = 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no, viewport-fit=cover';
/** levels 1-4/6-9 rewrite the meta with these pieces */
const calls = (h) => h.bridge.__calls;
const store = (h) => h.bridge.__storage;
const lastCall = (h, name, pred) => {
  const rows = calls(h).filter((c) => c[0] === name && (!pred || pred(c)));
  return rows.length ? rows[rows.length - 1] : null;
};
const countCalls = (h, name, pred) => calls(h).filter((c) => c[0] === name && (!pred || pred(c))).length;
/** top-level `let state` is not on window - read it through the page's own scope */
const appState = (win) => win.eval('state');
const renderSettingsUI = (win) => win.eval("(function(){try{renderBackupReminderSettings()}catch(e){}})()");

(async () => {
  // ---------------------------------------------------------------- boot --
  const h = await bootApp({ now: OCT27, freshDb: true, bridge: {} });
  const win = h.win;

  suite('Startup');
  check('no page errors on load', h.pageErrors.length === 0, 0, h.pageErrors.slice(0, 2).join(' | '));
  check('shell API exposed', !!(win.shell && win.shell.available), true, !!(win.shell));
  check('settings: Android application card', !!win.document.getElementById('displaySizeLevels'), true, false);

  // ------------------------------------------------------ display sizing --
  suite('Display size (9 levels, level 5 = baseline)');
  const ds = win.recallDisplaySize;
  check('engine present', !!ds, true, !!ds);
  check('level 5 scale is exactly 1.0', ds.scaleOf('5') === 1, 1, ds.scaleOf('5'));
  check('default level is 5', ds.get() === '5', '5', ds.get());
  const buttons = win.document.querySelectorAll('#displaySizeLevels button');
  check('nine level buttons', buttons.length === 9, 9, buttons.length);
  check('level 5 marked active by default',
    !!win.document.querySelector('#displaySizeLevels button[data-level="5"].is-active'), true, false);
  const bootMeta = win.document.querySelector('meta[name="viewport"]').getAttribute('content');
  check('boot keeps the bundled stock viewport untouched (no JS layout shift)',
    bootMeta === STOCK_META, STOCK_META, bootMeta);

  const scales = [1, 2, 3, 4, 5, 6, 7, 8, 9].map(n => Number(ds.scaleOf(String(n)).toFixed(4)));
  check('levels 1-4 are strictly smaller than 1.0',
    scales.slice(0, 4).every(s => s < 1), '<1', scales.slice(0, 4).join(','));
  check('levels 6-9 are strictly larger than 1.0',
    scales.slice(5).every(s => s > 1), '>1', scales.slice(5).join(','));
  check('monotonically increasing across all 9 levels',
    scales.every((s, i) => i === 0 || s > scales[i - 1]), 'increasing', scales.join(','));

  ds.set('8');
  check('set(8) forwards to the wrapper', countCalls(h, 'setDisplayScale', c => c[1] === '8') === 1, 1,
    countCalls(h, 'setDisplayScale', c => c[1] === '8'));
  const metaAt8 = win.document.querySelector('meta[name="viewport"]').getAttribute('content');
  check('level 8 rewrites the viewport (scale > 1)', /initial-scale=1\.2/.test(metaAt8), 'initial-scale=1.2', metaAt8);
  ds.set('3');
  const metaAt3 = win.document.querySelector('meta[name="viewport"]').getAttribute('content');
  check('level 3 rewrites the viewport (scale < 1)', /initial-scale=0\.85/.test(metaAt3), 'initial-scale=0.85', metaAt3);
  ds.set('5');
  check('level 5 restores the canonical 100% baseline meta',
    win.document.querySelector('meta[name="viewport"]').getAttribute('content') === BASELINE_META, BASELINE_META,
    win.document.querySelector('meta[name="viewport"]').getAttribute('content'));

  // ------------------------------------------------------- data mirroring --
  suite('Native mirror (export must contain revision data)');
  const mirrorCount = countCalls(h, 'saveData', c => c[1] === 'recall_state');
  check('state mirrored to the wrapper on startup', mirrorCount >= 1, '>=1', mirrorCount);
  const mirrored = store(h)['recall_state'] || '';
  let mirroredJson = null;
  try { mirroredJson = JSON.parse(mirrored); } catch (e) { }
  check('mirrored payload is valid JSON', !!mirroredJson, true, false);
  check('mirrored payload has topics', mirroredJson && Array.isArray(mirroredJson.topics), true,
    mirroredJson && Array.isArray(mirroredJson.topics));
  check('mirrored payload has subjects', mirroredJson && Array.isArray(mirroredJson.subjects), true,
    mirroredJson && Array.isArray(mirroredJson.subjects));
  check('mirrored payload has settings', !!(mirroredJson && mirroredJson.settings), true, false);
  check('mirrored topics are the app\'s real topics',
    mirroredJson && mirroredJson.topics.length === appState(win).topics.length, appState(win).topics.length,
    mirroredJson && mirroredJson.topics.length);

  // exportData() must mirror BEFORE handing over to the native exporter
  const before = countCalls(h, 'saveData', c => c[1] === 'recall_state');
  win.shell.exportData();
  const after = countCalls(h, 'saveData', c => c[1] === 'recall_state');
  check('Export user data refreshes the mirror first', after > before, '>' + before, after);
  check('native export dialog invoked', lastCall(h, 'exportBackup') !== null, true, lastCall(h, 'exportBackup') !== null);

  // empty local storage must never be pushed over good native data
  suite('Data-safety guards');
  const savedState = win.localStorage.getItem('recall_topic_revision_studio_v1');
  win.localStorage.removeItem('recall_topic_revision_studio_v1');
  const pushed = win.shell.mirror();
  win.mirrorResult = pushed;
  check('mirror refuses to push an empty payload', pushed === false, false, pushed);
  win.localStorage.setItem('recall_topic_revision_studio_v1', savedState);

  // captured before the restore suite replaces it with `goodState`
  const seedState = savedState;

  // -------------------------------------------------------------- restore --
  suite('Restore from a native backup import');
  const goodState = JSON.stringify({
    version: 1,
    subjects: ['Restored Subject'],
    settings: { examName: 'Imported exam', examDate: '2027-03-01', examEndDate: '2027-03-01', dailyStudyHours: 8 },
    topics: [{ id: 't_imported', name: 'Imported topic', subject: 'Restored Subject', duration: 30, revisions: [], notes: [] }]
  });
  store(h)['recall_state'] = goodState;
  const restoredOk = win.shell.restore();
  const localNow = JSON.parse(win.localStorage.getItem('recall_topic_revision_studio_v1'));
  check('valid backup is pulled into the app', restoredOk === true, true, restoredOk);
  check('restored topics replace the old ones', localNow.topics.length === 1 && localNow.topics[0].name === 'Imported topic',
    'Imported topic', localNow.topics.map(t => t.name).join(','));

  // corrupted native payload must not destroy what is in the app
  const beforeBad = win.localStorage.getItem('recall_topic_revision_studio_v1');
  store(h)['recall_state'] = '{"topics": [ {"broken": true ';
  const badOk = win.shell.restore();
  const afterBad = win.localStorage.getItem('recall_topic_revision_studio_v1');
  check('corrupted backup is rejected', badOk === false, false, badOk);
  check('existing data untouched after a failed restore', beforeBad === afterBad, true, beforeBad === afterBad);

  store(h)['recall_state'] = JSON.stringify({ topics: [], subjects: [] }); // wrong shape
  check('backup without settings is rejected',
    win.shell.restore() === false, false, true);

  // -------------------------------------------------------- notifications --
  suite('Notifications (counts from real revision data)');
  calls(h).length = 0;
  win.shell.reschedule();

  const daily = lastCall(h, 'scheduleAlarm', c => c[1] === 9101);
  const expectedDue = win.shell.dueToday();
  check('due-today list is non-empty on seeded data', expectedDue.length > 0, '>0', expectedDue.length);
  check('due list matches the app\'s own due items (deduplicated)',
    expectedDue.length === win.eval('getAllDueItems(todayISO()).length') || expectedDue.length < win.eval('getAllDueItems(todayISO()).length'),
    'unique topics <= due items', expectedDue.length + ' vs ' + win.eval('getAllDueItems(todayISO()).length'));
  check('daily reminder scheduled', !!daily, true, !!daily);
  if (daily) {
    const text = daily[4];
    const m = text.match(/^(\d+) topics?/);
    check('daily count matches the app\'s own due list',
      m && Number(m[1]) === expectedDue.length, expectedDue.length, m ? m[1] : 'no count');
    check('daily line is straight (no overdue mixing, no fluff)',
      !/overdue|Congrats|You have/i.test(text), 'clean line', text);
    const mins = expectedDue.reduce((s, t) => s + (Number(t.duration) || 0), 0);
    // same formatting the app uses: 3h 41m / 45m / 1h
    const expectedDur = (n => {
      const h = Math.floor(n / 60), m = n % 60;
      return [h ? h + 'h' : null, m ? m + 'm' : null].filter(Boolean).join(' ') || '0m';
    })(mins);
    check('daily duration matches the sum of topic durations',
      mins > 0 && text.includes(expectedDur + ' estimated.'), expectedDur,
      text.match(/\S+ estimated\./) || 'missing');
    // Compare against the harness clock: the page runs on fake timers set to
    // OCT27, while Node's Date.now() is the real wall clock. Measuring the
    // scheduled time against the real clock made this assertion depend on when
    // the suite happened to run instead of on the app's own timeline.
    check('daily alarm is scheduled for the configured time',
      typeof daily[2] === 'number' && daily[2] > h.now(), 'future ms vs app clock', daily[2]);
    console.log('   daily text: ' + text);
  }

  // a topic that is BOTH overdue and due today must be counted once
  const dual = appState(win).topics[0];
  dual.blocked = false;
  appState(win).topics[0].revisions = [
    { id: 'r_over', dueDate: '2026-10-20', completedAt: null },
    { id: 'r_today', dueDate: '2026-10-27', completedAt: null }
  ];
  const dueList = win.shell.dueToday();
  const occurrences = dueList.filter(t => t.id === dual.id).length;
  check('overdue + due-today counted once per topic', occurrences === 1, 1, occurrences);
  check('blocked topics are excluded',
    (function () { appState(win).topics[1].blocked = true; const n = win.shell.dueToday().filter(t => t.id === appState(win).topics[1].id).length; appState(win).topics[1].blocked = false; return n; })() === 0,
    0, 'counted');

  // overdue reminder: off by default, on on request
  const overdueOff = lastCall(h, 'scheduleAlarm', c => c[1] === 9102);
  check('overdue reminder off by default', !overdueOff, false, !!overdueOff);
  win.shell.toggleOverdueReminder();
  win.shell.reschedule();
  const overdueOn = lastCall(h, 'scheduleAlarm', c => c[1] === 9102);
  check('overdue reminder schedules when enabled', !!overdueOn, true, !!overdueOn);
  // The notification counts what the Overdue SCREEN shows, so a topic that is
  // overdue but also due today (Home > Overdue) is NOT part of it.
  const expectedOverdue = win.shell.overdueItems().length;
  const expectedOverdueText = expectedOverdue
    ? expectedOverdue + (expectedOverdue === 1 ? ' topic overdue.' : ' topics overdue.')
    : 'No overdue topics.';
  check('overdue text reports the Overdue screen\'s count',
    overdueOn && overdueOn[4] === expectedOverdueText, expectedOverdueText,
    overdueOn ? overdueOn[4] : 'none');
  check('overdue count excludes topics that are also due today',
    expectedOverdue === win.eval('(function(){var t=todayISO(),d={};getItemsForDate(t).forEach(function(x){if(!x.revision.completedAt&&!x.revision.clearedByLaterRevisionAt)d[String(x.topic.id)]=1});return getAllDueItems(t).filter(function(x){return x.revision.dueDate<t&&!d[String(x.topic.id)]}).length})()'),
    'same rule as renderOverduePage', expectedOverdue);

  // ...and it still fires when nothing is overdue, with a plain confirmation.
  const dueBackup = appState(win).topics.map(t => t.revisions);
  appState(win).topics.forEach(t => { t.revisions = t.revisions.filter(r => r.dueDate >= win.eval('todayISO()')); });
  win.shell.reschedule();
  const overdueEmpty = lastCall(h, 'scheduleAlarm', c => c[1] === 9102);
  check('overdue reminder still scheduled with nothing overdue',
    !!overdueEmpty, true, !!overdueEmpty);
  check('empty overdue text is a plain confirmation',
    overdueEmpty && overdueEmpty[4] === 'No overdue topics.',
    'No overdue topics.', overdueEmpty ? overdueEmpty[4] : 'none');
  appState(win).topics.forEach((t, i) => { t.revisions = dueBackup[i]; });
  win.shell.reschedule();
  win.shell.toggleOverdueReminder(); // leave the switch as found

  // ------------------------------------------ instant completion notice --
  suite('Completion notification');
  // Pick a topic that is due today with nothing missed behind it, so marking it
  // completed has no side effects on other suites' fixtures.
  const pickCleanRev = `(function(){
    var today = todayISO();
    var t = state.topics.filter(function(x){
      if (x.blocked) return false;
      var revs = x.revisions || [];
      if (!revs.some(function(r){ return !r.completedAt && !r.clearedByLaterRevisionAt && r.dueDate <= today; })) return false;
      return !revs.some(function(r){ return !r.completedAt && !r.clearedByLaterRevisionAt && r.dueDate < today; });
    })[0];
    if (!t) return null;
    var r = t.revisions.filter(function(x){ return !x.completedAt && !x.clearedByLaterRevisionAt && x.dueDate <= today; })[0];
    return { id: t.id, rev: r.id, name: t.name };
  })()`;
  calls(h).length = 0;
  const doneTarget = win.eval(pickCleanRev);
  check('a completable topic exists', !!doneTarget, true, doneTarget ? 'found' : 'none');
  if (doneTarget) win.eval(`completeRevisionDirect(${JSON.stringify(doneTarget.id)}, ${JSON.stringify(doneTarget.rev)})`);
  const done = lastCall(h, 'showNotification', c => c[1] === 9103);
  check('completing a topic posts a notification', !!done, true, !!done);
  check('notification is the straight "✓ Completed" line',
    done && done[2] === '\u2713 Completed', '\u2713 Completed', done ? JSON.stringify(done[2]) : 'none');
  check('notification carries the topic name',
    done && done[3] === '"' + doneTarget.name + '"', '"' + (doneTarget ? doneTarget.name : '?') + '"',
    done ? JSON.stringify(done[3]) : 'none');
  const doneCount = countCalls(h, 'showNotification', c => c[1] === 9103);
  check('exactly one notification per completion', doneCount === 1, 1, doneCount);
  // Re-running the same completion changes nothing -> no second ping.
  if (doneTarget) win.eval(`completeRevisionDirect(${JSON.stringify(doneTarget.id)}, ${JSON.stringify(doneTarget.rev)})`);
  check('no duplicate notification when nothing changes',
    countCalls(h, 'showNotification', c => c[1] === 9103) === 1, 1,
    countCalls(h, 'showNotification', c => c[1] === 9103));
  check('no page errors after the completion notice',
    h.pageErrors.length === 0, 0, h.pageErrors.slice(0, 2).join(' | '));

  // ------------------------------------------------------------- settings --
  suite('Settings switches');
  check('daily switch on by default',
    win.document.getElementById('dailySummarySwitch').classList.contains('on'), true, false);
  check('overdue switch off by default',
    !win.document.getElementById('overdueReminderSwitch').classList.contains('on'), true, true);
  win.shell.toggleDailySummary();
  check('toggling daily summary writes the native flag',
    countCalls(h, 'setFlag', c => c[1] === 'recall_dailySummary' && c[2] === false) === 1, 1,
    countCalls(h, 'setFlag', c => c[1] === 'recall_dailySummary'));
  check('switch reflects the new state',
    !win.document.getElementById('dailySummarySwitch').classList.contains('on'), true, true);
  win.shell.toggleDailySummary();

  const timeSel = win.document.getElementById('dailySummaryTime');
  check('time selector has 24 hourly options', timeSel.options.length === 24, 24, timeSel.options.length);
  win.shell.setDailySummaryTime('07:00');
  check('time change reaches the wrapper as a string flag',
    countCalls(h, 'setStringFlag', c => c[1] === 'recall_dailySummaryTime' && c[2] === '07:00') >= 1, true,
    countCalls(h, 'setStringFlag', c => c[1] === 'recall_dailySummaryTime'));
  check('time round-trips back out of the wrapper',
    h.bridge.__stringFlags['recall_dailySummaryTime'] === '07:00', '07:00',
    h.bridge.__stringFlags['recall_dailySummaryTime']);
  check('time is NOT stored in the boolean flag channel',
    h.bridge.__flags['recall_dailySummaryTime'] !== true, 'not true', h.bridge.__flags['recall_dailySummaryTime']);

  // -------------------------------------------------- overdue page opens --
  suite('Overdue page cards open the topic detail');
  // Note: deliberately NO schedule block - a topic without one must not make
  // renderOverduePage() throw (buildRevisionDates used to dereference
  // schedule.intervals unguarded and blanked the whole page).
  appState(win).topics.push({
    id: 't_over_only', name: 'Overdue Only Topic', subject: 'Polity', difficulty: 'Medium',
    duration: 15, blocked: false, notes: [],
    revisions: [{ id: 'r_over_only', dueDate: '2026-10-20', completedAt: null, status: 'overdue' }]
  });
  win.eval('renderOverduePage()');
  const cardCount = win.document.querySelectorAll('#overdueList .overdue-individual-card').length;
  check('overdue page renders without throwing (no-schedule topic)', cardCount > 0, '>0 cards', cardCount);
  win.eval('renderOverduePage()');
  const overCard = win.document.querySelector('#overdueList .overdue-individual-card');
  check('overdue card rendered', !!overCard, true, !!overCard);
  if (overCard) {
    check('overdue card is focusable (role=button, tabindex)',
      overCard.getAttribute('role') === 'button' && overCard.getAttribute('tabindex') === '0',
      'button/0', overCard.getAttribute('role') + '/' + overCard.getAttribute('tabindex'));
    overCard.click();
    const name = win.document.getElementById('detailTopicName');
    check('clicking an overdue card opens its detail',
      !!name && name.textContent === 'Overdue Only Topic', 'Overdue Only Topic',
      name ? name.textContent : 'no detail element');
    const moreBtn = win.document.querySelector('#overdueList .overdue-more-btn');
    if (moreBtn) {
      const nameEl = win.document.getElementById('detailTopicName');
      if (nameEl) nameEl.textContent = ''; // reset so we can see what the next click opens
      moreBtn.click();
      const menu = win.document.querySelector('#overdueList .overdue-popup-menu');
      check('the ⋮ menu still opens (and does not fall through to the card)',
        !!menu && menu.hidden === false, 'menu open', menu ? 'hidden=' + menu.hidden : 'no menu');
      const nameAfter = win.document.getElementById('detailTopicName');
      check('⋮ button does not also open the detail',
        !nameAfter || nameAfter.textContent !== 'Overdue Only Topic', 'detail untouched',
        nameAfter ? nameAfter.textContent : '-');
    } else {
      check('overdue card has a ⋮ menu button', false, true, false);
    }
    check('no page errors after overdue interactions', h.pageErrors.length === 0, 0, h.pageErrors.slice(0, 2).join(' | '));
  }

  // ------------------------------------------- Export Backup Reminder --
  suite('Export Backup Reminder');
  const REM_KEY = 'recall_export_backup_reminder_v1';
  const remBackdrop = () => win.document.getElementById('backupReminderBackdrop');
  const setReminder = (o) => win.localStorage.setItem(REM_KEY, JSON.stringify(o));
  const getReminder = () => JSON.parse(win.localStorage.getItem(REM_KEY) || 'null');

  renderSettingsUI(win);
  const dayBoxes = win.document.querySelectorAll('#backupReminderDays input[type=checkbox]');
  check('settings renders 7 day checkboxes', dayBoxes.length === 7, 7, dayBoxes.length);
  const remTimeSel = win.document.getElementById('backupReminderTime');
  check('settings renders 24 time options', remTimeSel && remTimeSel.options.length === 24, 24, remTimeSel && remTimeSel.options.length);
  const remCfg = JSON.parse(win.eval('JSON.stringify(getBackupReminderSettings())'));
  check('defaults to Mon/Wed/Fri 09:00',
    JSON.stringify(remCfg.days) === JSON.stringify([1, 3, 5]) && remCfg.time === 9,
    '[1,3,5]@9', JSON.stringify(remCfg.days) + '@' + remCfg.time);

  // Boot default (now = Tue 09:00, days Mon/Wed/Fri) -> must NOT show
  win.eval('maybeShowBackupReminder()');
  check('not shown on a non-selected day', !remBackdrop().classList.contains('open'), false, true);

  // Today selected, no time gate -> shows once, cancel locked with 10s
  const dow = win.eval('new Date().getDay()');
  setReminder({ days: [dow], time: 0, lastShown: '' });
  win.eval('maybeShowBackupReminder()');
  check('shown on a selected day after the configured time', remBackdrop().classList.contains('open'), true, false);
  const cancelBtn = win.document.getElementById('backupReminderCancelBtn');
  const countEl = win.document.getElementById('backupReminderCountdown');
  check('cancel starts disabled at (10s)', cancelBtn.disabled && countEl.textContent === '(10s)', 'disabled/(10s)',
    (cancelBtn.disabled ? 'disabled' : 'enabled') + '/' + countEl.textContent);

  // Outside tap must NOT dismiss
  remBackdrop().dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
  check('tapping outside does not dismiss', remBackdrop().classList.contains('open'), true, false);
  // Escape must NOT dismiss either (only the two buttons may)
  win.document.dispatchEvent(new win.KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
  check('Escape does not dismiss', remBackdrop().classList.contains('open'), true, false);
  // Cancel is a no-op during the countdown
  win.eval('cancelBackupReminder()');
  check('cancel is a no-op during the countdown', remBackdrop().classList.contains('open'), true, false);
  check('Export button is enabled from the start',
    !win.document.querySelector('.backup-reminder-export').disabled, true, false);

  // Second call in the same app day -> once per app day
  win.eval('closeBackupReminder(); maybeShowBackupReminder()');
  check('shown only once per app day', !remBackdrop().classList.contains('open'), false, true);

  // Countdown ticks to zero and unlocks cancel
  setReminder({ days: [dow], time: 0, lastShown: '' });
  win.eval('maybeShowBackupReminder()');
  await h.advance(10000, 1000);
  check('countdown reaches (0s)', countEl.textContent === '(0s)', '(0s)', countEl.textContent);
  check('cancel enabled after the countdown', !cancelBtn.disabled, true, cancelBtn.disabled);
  win.eval('cancelBackupReminder()');
  check('cancel dismisses after the countdown', !remBackdrop().classList.contains('open'), false, true);

  // Time gate: not before the configured hour
  setReminder({ days: [dow], time: 23, lastShown: '' });
  win.eval('maybeShowBackupReminder()');
  check('not shown before the configured time', !remBackdrop().classList.contains('open'), false, true);

  // Day Start boundary: at 03:00 with dayStartAt=5 the app day is still yesterday.
  // Days cover both weekdays (Mon+Tue) so ONLY the boundary can be what blocks it.
  setReminder({ days: [1, 2], time: 0, lastShown: '' });
  const remState = appState(win);
  remState.settings.dayStartAt = 5;
  h.setNow(new Date(2026, 9, 27, 3, 0, 0).getTime()); // 03:00 < day start 05:00
  win.eval('maybeShowBackupReminder()');
  check('never appears before the Day Start hour', !remBackdrop().classList.contains('open'), false, true);
  h.setNow(new Date(2026, 9, 27, 5, 30, 0).getTime()); // just after day start
  win.eval('maybeShowBackupReminder()');
  check('appears on first open after the Day Start hour', remBackdrop().classList.contains('open'), true, false);
  win.eval('closeBackupReminder()');
  remState.settings.dayStartAt = 0;
  h.setNow(OCT27);

  // Confirm -> native export (wrapper), reminder closes, no date bookkeeping
  setReminder({ days: [dow], time: 0, lastShown: '' });
  calls(h).length = 0;
  win.eval('maybeShowBackupReminder()');
  check('reminder reopened for the export check', remBackdrop().classList.contains('open'), true, false);
  win.eval('confirmExportBackup()');
  check('Export Backup hands over to the native save flow', lastCall(h, 'exportBackup') !== null, true, false);
  check('state is mirrored before the native export', countCalls(h, 'saveData', c => c[1] === 'recall_state') >= 1, '>=1',
    countCalls(h, 'saveData', c => c[1] === 'recall_state'));
  check('popup closed after exporting', !remBackdrop().classList.contains('open'), false, true);
  check('countdown stopped after dismissing', !win.eval('backupReminderTimer'), true, !!win.eval('backupReminderTimer'));
  check('reminder prefs untouched by showing/exporting',
    getReminder().lastShown === '2026-10-27' && JSON.stringify(getReminder().days) === JSON.stringify([dow]),
    '2026-10-27 / [' + dow + ']', getReminder().lastShown + ' / ' + JSON.stringify(getReminder().days));

  // Filename contract (browser path)
  const names = [];
  const origClick = win.HTMLAnchorElement.prototype.click;
  win.URL.createObjectURL = () => 'blob:mock';
  win.URL.revokeObjectURL = () => { };
  win.HTMLAnchorElement.prototype.click = function () { names.push(this.download); };
  win.eval('exportData()');
  win.HTMLAnchorElement.prototype.click = origClick;
  check('export filename is Recall_DD-MM-YYYY_Backup.json',
    names.length === 1 && /^Recall_\d{2}-\d{2}-\d{4}_Backup\.json$/.test(names[0]),
    'Recall_27-10-2026_Backup.json', names[0]);
  check('no page errors after the reminder flow', h.pageErrors.length === 0, 0, h.pageErrors.slice(0, 2).join(' | '));

  // -------------------------------------------------------- action sounds --
  suite('Action sounds (completed / overdue, one shot each)');
  const pickPendingDue = `(function(){
    var t = state.topics.filter(function(x){ return !x.blocked && (x.revisions||[]).some(function(r){ return !r.completedAt && r.dueDate <= todayISO(); }); })[0];
    return t ? { id: t.id, rev: t.revisions.filter(function(r){ return !r.completedAt && r.dueDate <= todayISO(); })[0].id } : null;
  })()`;
  const pickPendingToday = `(function(){
    var t = state.topics.filter(function(x){ return !x.blocked && (x.revisions||[]).some(function(r){ return !r.completedAt && r.status !== 'overdue' && r.dueDate >= todayISO(); }); })[0];
    return t ? { id: t.id, rev: t.revisions.filter(function(r){ return !r.completedAt && r.status !== 'overdue' && r.dueDate >= todayISO(); })[0].id } : null;
  })()`;

  calls(h).length = 0;
  const compTarget = win.eval(pickPendingDue);
  if (compTarget) win.eval(`setHomeRevisionStatus(${JSON.stringify(compTarget.id)}, ${JSON.stringify(compTarget.rev)}, 'completed')`);
  check('completed sound fires when a revision is marked completed',
    !!compTarget && countCalls(h, 'playCompletionSound') === 1, 1, countCalls(h, 'playCompletionSound'));
  check('no overdue sound on completion',
    countCalls(h, 'playOverdueSound') === 0, 0, countCalls(h, 'playOverdueSound'));

  calls(h).length = 0;
  const overTarget = win.eval(pickPendingToday);
  if (overTarget) win.eval(`setHomeRevisionStatus(${JSON.stringify(overTarget.id)}, ${JSON.stringify(overTarget.rev)}, 'overdue')`);
  check('overdue sound fires when a revision is marked overdue',
    !!overTarget && countCalls(h, 'playOverdueSound') === 1, 1, countCalls(h, 'playOverdueSound'));
  check('no completion sound on overdue',
    countCalls(h, 'playCompletionSound') === 0, 0, countCalls(h, 'playCompletionSound'));

  // the topic-detail path ("Mark overdue") must sound the same way
  calls(h).length = 0;
  const detailTarget = win.eval(pickPendingToday);
  if (detailTarget) {
    win.eval(`(function(){ try {
      openTopicId = ${JSON.stringify(detailTarget.id)}; window.openRevisionId = ${JSON.stringify(detailTarget.rev)};
      markOpenTopicOverdue(); return true;
    } catch (e) { return String(e); } })()`);
  }
  check('overdue sound fires from the topic-detail "Mark overdue" path',
    !!detailTarget && countCalls(h, 'playOverdueSound') === 1, 1, countCalls(h, 'playOverdueSound'));

  // with the toggle off, neither action may make a sound
  calls(h).length = 0;
  win.shell.toggleSound();
  const mutedTarget = win.eval(pickPendingToday);
  if (mutedTarget) win.eval(`setHomeRevisionStatus(${JSON.stringify(mutedTarget.id)}, ${JSON.stringify(mutedTarget.rev)}, 'overdue')`);
  check('sound switch mutes both effects',
    countCalls(h, 'playOverdueSound') + countCalls(h, 'playCompletionSound') === 0, 0,
    countCalls(h, 'playOverdueSound') + countCalls(h, 'playCompletionSound'));
  win.shell.toggleSound(); // leave it as found
  check('no page errors after the sound flow', h.pageErrors.length === 0, 0, h.pageErrors.slice(0, 2).join(' | '));

  // -------------------------------------------------------------- summary --
  console.log('\n=== summary ===');
  const fails = results.filter(r => !r.pass);
  console.log(`${results.length - fails.length}/${results.length} passed`);
  if (h.pageErrors.length) console.log('page errors: ' + h.pageErrors.join(' | '));
  process.exitCode = fails.length ? 1 : 0;
  process.exit(process.exitCode);
})().catch(e => { console.error('HARNESS ERROR', e); process.exit(2); });