// help tab behaviour (tabs/HelpTab.java, HelpList.java, model/HelpIndex.java). commands are FakeFacade.java:240-263,
// goto written the way the real command prints it ("> goto <x> <z> - Go to an X,Z position").
// url: ?open=goto (row expanded) | ?help=ran (ok result line) | ?help=fail (bad line, text kept) | ?help=none (no commands registered)
(function () {
  const $ = (s, r) => (r || document).querySelector(s);
  const q = new URLSearchParams(location.search);
  const root = $('#help');
  if (!root) return;

  const CMDS = [
    [['goto', 'go', 'path'], 'Go to a coordinate or block', ['Tell Baritone to path to a position or a block. The coordinates can be absolute or relative to you with a tilde.', '',
      'Usage:', '> goto <y> - Go to a Y level', '> goto <x> <z> - Go to an X,Z position', '> goto <x> <y> <z> - Go to an X,Y,Z position', '> goto <block> - Go to the nearest block of a type']],
    [['mine', 'm'], 'Mine some blocks', ['The mine command allows you to tell Baritone to search for and mine individual blocks.', '', 'Usage:', '> mine diamond_ore', '> mine 64 oak_log', '> mine iron_ore coal_ore']],
    [['follow', 'f'], 'Follow entity things', ['Follow players, entities, or entity types.', '', 'Usage:', '> follow entities', '> follow entity zombie', '> follow players', '> follow player <name>']],
    [['get'], 'Get items the long way round', ['Crafts, smelts, mines or collects its way to the items.', '', 'Usage:', '> get oak_log 64', '> get iron_ingot 3 stick 4']],
    [['stop', 'cancel', 's'], 'Stop everything', ['Cancels the current task and path.', '', 'Usage:', '> stop']],
    [['set', 'setting', 'settings'], 'View or change settings', ["Using the set command, you can manage all of Baritone's settings.", '', 'Usage:', '> set list', '> set <setting> <value>', '> set reset <setting>']],
    [['wp', 'waypoint', 'waypoints'], 'Manage waypoints', ['Save, list and go to waypoints.', '', 'Usage:', '> wp save <tag> <name>', '> wp list', '> wp goto <name>']],
    [['gamer'], 'Beat the game', ['Starts the speedrun brain.', '', 'Usage:', '> gamer', '> gamer status', '> gamer reset']],
    [['gui', 'clickgui'], 'Open the click gui', ['Opens this panel. Right shift by default, rebind in Settings or with set guiKey.', '', 'Usage:', '> gui']],
    [['help', '?'], 'View all commands or help on specific ones', ['Lists commands.', '', 'Usage:', '> help', '> help <command>']],
    [['farm'], 'Farm nearby crops', ['Harvests and replants crops.', '', 'Usage:', '> farm', '> farm <range>']],
    [['explore'], 'Explore things', ['Explores outward from a point.', '', 'Usage:', '> explore', '> explore <x> <z>']],
  ].map(([names, short, long]) => ({ names, name: names[0], short, long }));

  if (q.get('help') === 'chips') CMDS.find(c => c.name === 'set').names.push('config', 'configure', 'prefs');   // forces the +N chip
  const all = q.get('help') === 'none' ? [] : CMDS;
  let query = '';
  let expanded = q.get('open') || null;
  // HelpHistory: up walks back, down walks forward and ends on the half typed line, no repeats, 20 max
  const history = [];
  let cursor = 0, draft = '';
  const hreset = () => { cursor = history.length; draft = ''; };

  const $line = $('.hp-line', root), $input = $('#hp-input'), $run = $('#hp-run'), $res = $('#hp-result'), $card = $('.hp-card', root), $scroll = $('#hp-scroll'), $list = $('#hp-list');
  const IDLE = 'Enter runs it. Up arrow brings back earlier commands.';

  // widths for the alias chip overflow, measured like the java does
  const mctx = document.createElement('canvas').getContext('2d');
  const measure = (t, font) => { mctx.font = font; return mctx.measureText(t).width; };

  // HelpIndex.lines: "> usage - note", "Usage:" headings, blank runs squashed to one gap, none at the ends
  function lines(long) {
    const out = [];
    for (const raw of long) {
      const t = raw.trim();
      if (!t) { if (out.length && out[out.length - 1].k !== 'gap') out.push({ k: 'gap' }); }
      else if (t.startsWith('>')) {
        const u = t.slice(1).trim(), d = u.indexOf(' - ');
        out.push({ k: 'use', t: d < 0 ? u : u.slice(0, d).trim(), note: d < 0 ? '' : u.slice(d + 3).trim() });
      } else if (t.endsWith(':') && t.length >= 2) out.push({ k: 'head', t: t.slice(0, -1) });
      else out.push({ k: 'text', t });
    }
    while (out.length && out[out.length - 1].k === 'gap') out.pop();
    return out;
  }

  const esc = s => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

  function chipsFor(c) {
    const nameW = measure(c.name, '500 14px Inter');
    let x = nameW + 8, html = '';
    const end = 244;   // chips stop before the description column
    for (let i = 1; i < c.names.length; i++) {
      let chip = c.names[i], w = measure(chip, '500 12px Inter') + 16, stop = false;
      if (x + w > end) {
        chip = '+' + (c.names.length - i); w = measure(chip, '500 12px Inter') + 16;
        if (x + w > end) break;
        stop = true;
      }
      html += '<span class="hp-chip">' + esc(chip) + '</span>';
      x += w + 4;
      if (stop) break;
    }
    return html;
  }

  function detailHtml(c) {
    const ls = lines(c.long);
    if (!ls.length) return '<div class="hp-text">' + (c.short || 'No more details for this command.') + '</div>';
    return ls.map(l => l.k === 'gap' ? '<div class="hp-gap"></div>'
      : l.k === 'head' ? '<div class="hp-head">' + esc(l.t) + '</div>'
      : l.k === 'use' ? '<div class="hp-use" data-line="' + esc(l.t) + '"><span class="code">' + esc(l.t) + '</span>' + (l.note ? '<span class="note">' + esc(l.note) + '</span>' : '') + '<span class="hint">Use</span></div>'
      : '<div class="hp-text">' + esc(l.t) + '</div>').join('');
  }

  // HelpIndex.score: name 100/90/80, alias 70/60/50, every word in the short text 40, every word at a word start in the long text 30
  function score(c, raw) {
    let s = raw.trim().toLowerCase();
    while (s.startsWith('#')) s = s.slice(1).trim();
    if (!s) return 0;
    const n = c.name;
    if (n === s) return 100; if (n.startsWith(s)) return 90; if (n.includes(s)) return 80;
    let best = 0;
    for (const a of c.names.slice(1)) best = Math.max(best, a === s ? 70 : a.startsWith(s) ? 60 : a.includes(s) ? 50 : 0);
    if (best) return best;
    const words = s.split(/\s+/).filter(Boolean), short = c.short.toLowerCase();
    if (words.every(w => short.includes(w))) return 40;
    const long = c.long.join(' ').toLowerCase();
    const lead = w => new RegExp('(?<![\\p{L}\\p{N}])' + w.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'u').test(long);
    return words.every(lead) ? 30 : 0;
  }
  function rank() {
    if (!query.replace(/^[#\s]+/, '')) return all.slice().sort((a, b) => (a.name < b.name ? -1 : 1));
    return all.map(c => [score(c, query), c]).filter(p => p[0] > 0).sort((a, b) => b[0] - a[0] || (a[1].name < b[1].name ? -1 : 1)).map(p => p[1]);
  }

  function setOpen(row, open, instant) {
    const det = $('.hdet', row), inner = $('.hdi', row);
    if (instant) det.style.transition = 'none';
    row.classList.toggle('open', open);
    det.style.height = open ? inner.scrollHeight + 'px' : '0px';
    if (instant) { det.getBoundingClientRect(); det.style.transition = ''; }
  }

  function render() {
    const list = rank();
    if (expanded && !list.some(c => c.name === expanded)) expanded = null;   // filtered away while open, it must not come back open
    $card.textContent = '';
    for (const c of list) {
      const row = document.createElement('div');
      row.className = 'hrow'; row.dataset.name = c.name;
      row.innerHTML = '<div class="hh"><div class="hn"><span class="nm">' + esc(c.name) + '</span>' + chipsFor(c) + '</div><div class="hd">' + esc(c.short) +
        '</div><span class="hc"><i data-icon="chevron_right" class="c-right"></i><i data-icon="chevron_down" class="c-down"></i></span></div>' +
        '<div class="hdet"><div class="hdi">' + detailHtml(c) + '</div></div>';
      $('.hh', row).addEventListener('click', () => {
        const was = row.classList.contains('open');
        $card.querySelectorAll('.hrow.open').forEach(r => setOpen(r, false));
        expanded = was ? null : c.name;
        if (!was) setOpen(row, true);
      });
      row.querySelectorAll('.hp-use').forEach(u => u.addEventListener('click', () => {
        $input.value = u.dataset.line; $line.classList.remove('invalid'); hreset(); sync(); $input.focus();
      }));
      $card.appendChild(row);
    }
    Mock.hydrate($card);
    $card.querySelectorAll('.hrow').forEach(r => { if (r.dataset.name === expanded) setOpen(r, true, true); });

    $card.style.display = list.length ? '' : 'none';
    let e = $('.hp-empty', $list); if (e) e.remove();
    const msg = !all.length ? 'No commands registered' : !list.length ? 'No command matches "' + esc(query.trim()) + '"' : '';
    if (msg) { e = document.createElement('div'); e.className = 'hp-empty'; e.innerHTML = msg; $list.appendChild(e); }
    if ($scroll._place) $scroll._place();
  }

  // HelpIndex.cleanLine: trimmed, any number of leading #
  const clean = t => { let s = t.trim(); while (s.startsWith('#')) s = s.slice(1).trim(); return s; };
  function sync() { $run.disabled = !clean($input.value); }
  function result(text, kind) { $res.textContent = text; $res.className = 'hp-result t-small' + (kind ? ' ' + kind : ''); }

  function run() {
    const line = clean($input.value);
    if (!line) return;
    if (history[history.length - 1] !== line) { history.push(line); if (history.length > 20) history.shift(); }
    hreset();
    const first = line.split(/\s+/)[0].toLowerCase();
    if (CMDS.some(c => c.names.includes(first))) { result('Ran ' + line, 'ok'); $input.value = ''; $line.classList.remove('invalid'); }
    else { result('Unknown command "' + first + '"', 'bad'); $line.classList.add('invalid'); }
    sync();
  }

  const recall = t => { if (t !== $input.value) { $input.value = t; $line.classList.remove('invalid'); } sync(); };
  // the # in front is already drawn, a second one would read as "# #goto"
  $input.addEventListener('beforeinput', e => { if (e.data === '#' && !$input.value) e.preventDefault(); });
  $input.addEventListener('input', () => { sync(); $line.classList.remove('invalid'); hreset(); });
  $input.addEventListener('keydown', e => {
    const plain = !e.shiftKey && !e.ctrlKey && !e.altKey && !e.metaKey;   // a terminal does not walk history on ctrl-up
    if (e.key === 'Enter') run();
    else if (e.key === 'ArrowUp' && plain && history.length) {
      e.preventDefault();
      if (cursor >= history.length) draft = $input.value;
      cursor = Math.max(0, cursor - 1);
      recall(history[cursor]);
    } else if (e.key === 'ArrowDown' && plain && cursor < history.length) {
      e.preventDefault();
      cursor++;
      recall(cursor >= history.length ? draft : history[cursor]);
    }
  });
  $run.addEventListener('click', run);
  $('#hdr-input').addEventListener('input', e => {
    const s = $('.content > section.active');
    if (s && s.dataset.tab === 'help') { query = e.target.value; render(); }
  });

  Mock.thumbFor($scroll);
  new ResizeObserver(() => { if ($scroll._place) $scroll._place(); }).observe($card);
  const help = q.get('help');
  if (help === 'ran') result('Ran goto 100 64 -30', 'ok');
  if (help === 'fail') { $input.value = 'gett oak_log 4'; result('Unknown command "gett"', 'bad'); $line.classList.add('invalid'); }
  if (q.get('hq')) { query = q.get('hq'); $('#hdr-input').value = query; }   // the shell search field shows what is being searched
  sync();
  render();
  if (document.fonts) document.fonts.load('500 14px Inter').then(() => document.fonts.load('500 12px Inter')).then(render);
  window.addEventListener('load', render);
})();
