/* Builds the Recall app's bundled index.html from the audited source + the
 * Android shell integration. Run: node shell/inject.js */
const fs = require('fs');
const path = require('path');


const SRC = path.join(__dirname, 'Revision bests - hardened.html');
const OUT = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'app', 'index.html');

const headJs = fs.readFileSync(path.join(__dirname, 'head.js'), 'utf8');
const bodyJs = fs.readFileSync(path.join(__dirname, 'body.js'), 'utf8');
const settingsHtml = fs.readFileSync(path.join(__dirname, 'settings.html'), 'utf8');

let html = fs.readFileSync(SRC, 'utf8');

function mustFind(anchor, label) {
  const n = html.split(anchor).length - 1;
  if (n !== 1) throw new Error(`${label}: expected 1 occurrence of ${JSON.stringify(anchor)}, found ${n}`);
  return n;
}

// 1) Display Size engine in <head> - it must run before first paint.
const charset = '<meta charset="UTF-8">';
mustFind(charset, 'charset');
html = html.replace(charset, charset + '\n<script>\n' + headJs + '\n</script>');

// 2) Settings: retitle the existing card so it matches the Android grouping.
const oldTitle = '<h3>Data & backup</h3>';
mustFind(oldTitle, 'settings title');
html = html.replace(oldTitle, '<h3>Data management</h3>');

// 3) Settings: add the wrapper-level export/import row to that same card so
//    there is exactly one data-management section (no duplicate controls).
const spacer = '<div style="height:14px"></div>';
mustFind(spacer, 'settings spacer');
html = html.replace(spacer, [
  '<div class="settings-actions">',
  '<button class="secondary-btn" onclick="shell.exportData()">Android backup</button>',
  '<button class="secondary-btn" onclick="shell.importData()">Restore Android backup</button>',
  '<span class="small-muted" id="shellBackupStatus">Not mirrored to the Android wrapper yet.</span>',
  '</div>',
  spacer
].join(''));

// 4) Settings: append the Android cards (application, display, notifications,
//    sound) to the settings grid.
const lastCard = 'Reset all app data</button></div></div>';
mustFind(lastCard, 'last settings card');
html = html.replace(lastCard, lastCard + '\n        ' + settingsHtml.trim());

// 5) Shell integration script at the end of <body>.
const bodyClose = '</body>';
mustFind(bodyClose, 'body close');
html = html.replace(bodyClose, '<script>\n' + bodyJs + '\n</script>\n' + bodyClose);

fs.mkdirSync(path.dirname(OUT), { recursive: true });
fs.writeFileSync(OUT, html);

// Sanity: the produced file must still parse as one script.
const scripts = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)];
console.log('scripts:', scripts.length, 'bytes:', html.length);
scripts.forEach((m, i) => {
  try { new Function(m[1]); } catch (e) { console.error('SCRIPT ' + i + ' SYNTAX ERROR: ' + e.message); process.exitCode = 1; }
});
console.log('wrote', OUT);