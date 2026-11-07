'use strict';
/**
 * Mechanism regression tests for the CURRENT Recall HTML (recall_latest_7_november .html).
 *
 * The page is rendered by jsdom exactly as shipped (no extraction, no rewriting), so
 * every assertion runs against the real functions the app calls. Covers the data-path
 * fixes: revision preservation on reschedule, timer reset, note metadata on topic edit,
 * study-day history grouping, weekday schedules, and shadow-copy recovery.
 *
 *   node tests/recall-core.test.js
 */
const fs = require('fs');
const path = require('path');
const { JSDOM, VirtualConsole } = require('jsdom');

const HTML_PATH = path.join(__dirname, '..', '..', 'recall_latest_7_november .html');
const HTML = fs.readFileSync(HTML_PATH, 'utf8');

const results = [];
function check(name, pass, expected, actual) {
  results.push({ name, pass });
  console.log(`${pass ? 'PASS' : 'FAIL'} | ${name} | expected=${JSON.stringify(expected)} actual=${JSON.stringify(actual)}`);
}
function addDays(iso, n) {
  const [y, m, d] = iso.split('-').map(Number);
  const dt = new Date(Date.UTC(y, m - 1, d));
  dt.setUTCDate(dt.getUTCDate() + n);
  return dt.toISOString().slice(0, 10);
}

const pageErrors = [];
const vc = new VirtualConsole();
vc.on('jsdomError', (e) => pageErrors.push(String(e && e.message || e)));
vc.on('error', (...a) => pageErrors.push(a.map(String).join(' ')));

const dom = new JSDOM(HTML, {
  url: 'https://appassets.androidplatform.net/app/index.html',
  runScripts: 'dangerously',
  pretendToBeVisual: true,
  virtualConsole: vc
});
const win = dom.window;
const $ = (expr) => win.eval(expr);

function bootState(mutator) {
  const T = $('todayISO()');
  const base = {
    version: 1,
    subjects: ['History'],
    settings: {
      examName: 'Test', examDate: addDays(T, 60), examEndDate: addDays(T, 60), examStartDate: addDays(T, -20),
      dailyStudyHours: 8, nonStudyHours: 0, defaultPreset: 'daily', theme: 'dark', dayStartAt: 0,
      studyWithTimerOnly: true, hideCompletedNotes: false, toastDurationSec: 1.5,
      smartRevision: { Easy: [1, 3, 5], Medium: [1, 2, 4], Hard: [1, 2, 3] }
    },
    topics: []
  };
  if (mutator) mutator(base, T);
  $(`state = ${JSON.stringify(base)}; state.topics = state.topics.map(normalizeTopic); renderAll();`);
  return T;
}

function topic(id, name, duration, revisions) {
  return {
    id, name, subject: 'History', difficulty: 'Medium', duration,
    startDate: '2026-01-01', firstRevisionDate: '', initialDuration: null,
    schedule: { type: 'preset', preset: 'daily', intervals: [1], repeat: 1 },
    revisions, notes: [], telegramLinks: [], createdAt: new Date().toISOString()
  };
}
function rev(id, dueDate, extra) {
  return Object.assign({
    id, dueDate, sequence: 1, completedAt: null, actualMinutes: null,
    originalDueDate: dueDate, postponedFrom: null, status: ''
  }, extra || {});
}

// ---------------------------------------------------------------- Home totals --
{
  const T = bootState((s, t) => {
    s.topics = [
      // A: one missed revision AND one due today -> Home shows it in Overdue only.
      topic('t_a', 'A', 30, [rev('r_a1', addDays(t, -10), { sequence: 1 }), rev('r_a2', t, { sequence: 2 })]),
      topic('t_b', 'B', 15, [rev('r_b1', t, { sequence: 1 })]),
      // C: completed today -> Completed section.
      topic('t_c', 'C', 45, [rev('r_c1', t, { sequence: 1, completedAt: new Date().toISOString(), status: 'completed' })])
    ];
  });
  const summary = $(`JSON.stringify(getHomeDaySummary("${T}"))`);
  const s = JSON.parse(summary);
  check('Home counts one row per topic and one completion per topic', s.totalTopics === 3, 3, s.totalTopics);
  check('Home planned minutes come from topic estimates', s.plannedMinutes === 90, 90, s.plannedMinutes);
  check('Home completed rows counted once', s.completedTopics === 1, 1, s.completedTopics);
  check('Home completed minutes match the completed estimate', s.completedMinutes === 45, 45, s.completedMinutes);
  check('Overdue section shows the topic once, merged with today', s.overdueItems.length === 1, 1, s.overdueItems.length);
  check('Today section excludes the merged overdue topic', s.todayItems.length === 1, 1, s.todayItems.length);
  check('No page errors after Home render', pageErrors.length === 0, 0, pageErrors.slice(0, 2).join(' | '));
}

// ------------------------------------------- reschedule must preserve today's work --
{
  const T = bootState((s, t) => {
    s.topics = [
      topic('t_a', 'A', 30, [
        rev('r_done', addDays(t, -3), { sequence: 1, completedAt: '2026-11-01T10:00:00.000Z', status: 'completed', actualSeconds: 600 }),
        rev('r_miss', addDays(t, -1), { sequence: 2 }),
        rev('r_today', t, { sequence: 3, elapsedSeconds: 125 }),
        rev('r_future', addDays(t, 5), { sequence: 4 }),
        rev('r_post', addDays(t, 9), { sequence: 5, postponedFrom: addDays(t, 2) })
      ])
    ];
  });
  $(`rescheduleFuture(findTopic('t_a'))`);
  const doc = JSON.parse($(`JSON.stringify(findTopic('t_a'))`));
  const ids = doc.revisions.map((r) => r.id);
  const todayRec = doc.revisions.find((r) => r.id === 'r_today');
  const postRec = doc.revisions.find((r) => r.id === 'r_post');
  const doneRec = doc.revisions.find((r) => r.id === 'r_done');
  const missed = doc.revisions.find((r) => r.id === 'r_miss');
  check("reschedule keeps today's pending revision (same id)", !!todayRec, true, !!todayRec);
  check("reschedule keeps today's saved stopwatch time", todayRec && todayRec.elapsedSeconds === 125, 125, todayRec && todayRec.elapsedSeconds);
  check('reschedule keeps a postponed future revision', !!postRec && postRec.dueDate === addDays(T, 9), addDays(T, 9), postRec && postRec.dueDate);
  check('reschedule keeps completed history and its time', !!doneRec && doneRec.actualSeconds === 600, 600, doneRec && doneRec.actualSeconds);
  check('reschedule keeps overdue work', !!missed, true, !!missed);
  check('reschedule does not duplicate an occupied date',
    doc.revisions.filter((r) => r.dueDate === T).length === 1, 1, doc.revisions.filter((r) => r.dueDate === T).length);
  const seqs = doc.revisions.map((r) => Number(r.sequence) || 0);
  check('reschedule sequence numbers stay unique', new Set(seqs).size === seqs.length, seqs.length, new Set(seqs).size);
  check('reschedule still generates future dates', doc.revisions.some((r) => r.dueDate > T), true, doc.revisions.filter((r) => r.dueDate > T).length);
}

// ------------------------------------------------------------ timer reset clears --
{
  const T = bootState((s, t) => {
    s.topics = [topic('t_a', 'A', 30, [rev('r_today', t, { sequence: 1, elapsedSeconds: 1500 })])];
  });
  $(`openTopicId='t_a'; window.openRevisionId='r_today'; window.timerElapsedSeconds=1500; timerHasRun=true; timerReset();`);
  const saved = $(`findTopic('t_a').revisions.find(r=>r.id==='r_today').elapsedSeconds`);
  check('timer reset clears the saved revision time too', saved === 0, 0, saved);
  $(`persistElapsedForOpenRevision()`);
  const afterPersist = $(`findTopic('t_a').revisions.find(r=>r.id==='r_today').elapsedSeconds`);
  check('a following persist cannot resurrect the reset time', afterPersist === 0, 0, afterPersist);
}

// ---------------------------------------------- editing a topic keeps note fields --
{
  const T = bootState((s, t) => {
    const created = '2026-10-01T05:00:00.000Z';
    const tp = topic('t_a', 'A', 30, [rev('r_today', t, { sequence: 1 })]);
    tp.notes = [{
      id: 'n_1', title: 'Key points', body: 'bullet', difficulty: 'Hard',
      checked: true, checkedAt: '2026-10-02T05:00:00.000Z', collapsed: true, createdAt: created
    }];
    s.topics = [tp];
  });
  $(`openTopicModal('t_a'); saveTopic({preventDefault(){}});`);
  const note = JSON.parse($(`JSON.stringify(findTopic('t_a').notes[0])`));
  check('editing a topic keeps note createdAt', note.createdAt === '2026-10-01T05:00:00.000Z', '2026-10-01T05:00:00.000Z', note.createdAt);
  check('editing a topic keeps note checkedAt', note.checkedAt === '2026-10-02T05:00:00.000Z', '2026-10-02T05:00:00.000Z', note.checkedAt);
  check('editing a topic keeps the collapsed state', note.collapsed === true, true, note.collapsed);
  check('editing a topic keeps note content', note.title === 'Key points' && note.difficulty === 'Hard', 'Key points/Hard', `${note.title}/${note.difficulty}`);
}

// ------------------------------------------------ weekday schedule cannot be empty --
{
  $(`state.settings.dayStartAt=0`);
  const wd = JSON.parse($(`JSON.stringify(scheduleConfigFromPreset('weekdays',[1],1,{weekdays:[],stopMode:'exam',stopDate:''}))`));
  check('empty weekday selection falls back to Monday', Array.isArray(wd.weekdays) && wd.weekdays.length === 1 && wd.weekdays[0] === 1, [1], wd.weekdays);
}

// ------------------------------------------------- history groups by study day --
{
  const T = $(`todayISO()`);
  const [y, m, d] = T.split('-').map(Number);
  const early = new Date(y, m - 1, d, 1, 30, 0).toISOString(); // 01:30 local today
  bootState((s, t) => {
    s.settings.dayStartAt = 3; // 01:30 still belongs to YESTERDAY's study day
    s.topics = [topic('t_a', 'A', 30, [rev('r_1', t, { sequence: 1, completedAt: early, status: 'completed' })])];
  });
  const groups = JSON.parse($(`JSON.stringify(getHistoryDateGroups(findTopic('t_a')))`));
  check('history groups a 01:30 completion into the previous study day', groups[0] && groups[0].date === addDays(T, -1), addDays(T, -1), groups[0] && groups[0].date);
  const studyDay = $(`studyDayISOForTimestamp(${JSON.stringify(early)})`);
  check('study-day helper agrees with Home', studyDay === addDays(T, -1), addDays(T, -1), studyDay);
}

// ------------------------------------- missing primary key recovers from shadow --
{
  const T = $(`todayISO()`);
  const payload = JSON.stringify({
    version: 1, subjects: ['History'],
    settings: { examEndDate: T, dailyStudyHours: 8, dayStartAt: 0 },
    topics: [topic('t_a', 'A', 30, [rev('r_1', T, { sequence: 1 })]), topic('t_b', 'B', 20, [rev('r_2', T, { sequence: 1 })])]
  });
  const recovered = $(`(function(){
    localStorage.setItem('recall_topic_revision_studio_v1_backup', ${JSON.stringify(payload)});
    localStorage.removeItem('recall_topic_revision_studio_v1');
    var s = loadState();
    return JSON.stringify({topics:(s.topics||[]).length});
  })()`);
  const r = JSON.parse(recovered);
  check('a missing primary key recovers the shadow copy', r.topics === 2, 2, r.topics);
}

// ------------------------------------------------------------------------ summary --
console.log('\n=== summary ===');
const fails = results.filter((r) => !r.pass);
console.log(`${results.length - fails.length}/${results.length} passed`);
if (pageErrors.length) console.log('page errors:', pageErrors.slice(0, 5));
try { win.close(); } catch (e) { }
process.exit(fails.length ? 1 : 0);
