// schematics tab: the library list on the left, what the selected file costs on the right, build at the bottom
// (SchematicsTab.java). data is FakeSchematics.java. ?sc=loading (first load, no rows) ?sc=refreshing (rows + the
// small label) ?sc=empty ?sc=none (nothing selected) ?sc=noworld ?sc=counting (materials still loading)
(function () {
  const $ = (s, r) => (r || document).querySelector(s);
  const root = $('#sc'); if (!root) return;
  const mode = new URLSearchParams(location.search).get('sc') || '';
  const fmt = n => n.toLocaleString('en-US');

  // FakeSchematics rows: name, path, size, blocks, format, version, bytes, error, unknownSummary
  let rows = [
    { name: 'Small Castle', path: 'Small Castle.litematic', size: [24, 18, 24], blocks: 5230, format: 'Litematica v6', bytes: 48211 },
    { name: 'tower', path: 'tower.schem', size: [9, 40, 9], blocks: 2310, format: 'Sponge v3', bytes: 9120,
      unknown: '12 blocks unrecognized: minecraft:mangrove_vibes (10), minecraft:weird_slab (2)' },
    { name: 'broken', path: 'broken.litematic', size: [0, 0, 0], blocks: 0, format: 'Litematica', bytes: 120, error: 'EOFException: unexpected end of file' },
    { name: 'farm', path: 'villages/farm.schematic', size: [15, 6, 12], blocks: 640, format: 'MCEdit', bytes: 1904 }];
  if (mode === 'empty' || mode === 'loading') rows = [];
  const have = { 'minecraft:oak_log': 40, 'minecraft:glass': 3, 'minecraft:torch': 64 };
  const line = (id, name, needed, note, kind) => ({ id, icon: id, name, needed, note: note || '', kind: kind || 'item' });
  const castle = {
    items: [line('minecraft:stone_bricks', 'Stone Bricks', 3120), line('minecraft:oak_log', 'Oak Log', 212), line('minecraft:glass', 'Glass', 96),
      line('minecraft:oak_stairs', 'Oak Stairs', 88), line('minecraft:torch', 'Torch', 24, 'wall and standing'),
      line('minecraft:farmland', 'Farmland', 16, 'no survival item, till dirt with a hoe'), line('minecraft:beacon', 'Beacon', 1)],
    cantPlace: [{ id: 'minecraft:fire', icon: 'minecraft:flint_and_steel', name: 'Fire', needed: 3, note: 'light it with flint and steel' }],
    notCounted: [{ id: 'minecraft:water', icon: 'minecraft:water_bucket', name: 'Water', needed: 40, note: 'source blocks, 6 flowing not counted' }],
    notes: ['entities (item frames, paintings, armor stands) are not counted'] };
  const simple = { items: [line('minecraft:cobblestone', 'Cobblestone', 900), line('minecraft:oak_planks', 'Oak Planks', 410)], cantPlace: [], notCounted: [],
    notes: ['entities (item frames, paintings, armor stands) are not counted'] };
  const materials = { 'Small Castle.litematic': castle, 'tower.schem': simple, 'villages/farm.schematic': simple };

  let query = '', selected = mode === 'none' || !rows.length ? null : rows[0].path, armed = 0, armTimer = 0;
  const open = { cantPlace: false, notCounted: false };
  const noWorld = mode === 'noworld';

  const list = $('#sc-rows'), count = $('#sc-count'), detail = $('#sc-detail'), tip = $('#sc-tip');
  const sizeText = r => r.error ? '' : r.size.join(' x ');
  const kb = b => b < 1024 ? b + ' B' : b < 1024 * 1024 ? Math.round(b / 1024) + ' KB' : (b / 1024 / 1024).toFixed(1) + ' MB';

  function renderList() {
    const q = query.trim().toLowerCase();
    const shown = rows.filter(r => !q || r.path.toLowerCase().includes(q));
    list.textContent = '';
    for (const r of shown) {
      const el = document.createElement('div');
      el.className = 'row' + (r.path === selected ? ' sel' : '') + (r.error ? ' err' : '') + (r.unknown ? ' warn' : '');
      el.innerHTML = '<i data-icon="schematics" class="sic"></i><div class="txt"><div class="name t-label"></div><div class="sub"></div></div><i data-icon="warning" class="mark"></i>';
      $('.name', el).textContent = r.path;
      $('.sub', el).textContent = r.error ? 'Didn\'t parse' : r.format + ' · ' + sizeText(r);
      el.onclick = () => { selected = r.path; disarm(); render(); };
      el.dataset.tip = r.error ? r.error : '';
      list.appendChild(el);
    }
    Mock.hydrate(list);
    const first = mode === 'loading';
    root.classList.toggle('first-load', first);
    root.classList.toggle('empty', !first && !shown.length);
    const state = $('#sc-list-state');
    if (first) state.innerHTML = '<div class="lead">Reading the schematics folder…</div>';
    else if (!rows.length) state.innerHTML = '<div class="lead">No schematics yet</div><div>Drop .litematic, .schem or .schematic files on the game window, or click Import.</div><div>.minecraft/schematics</div>';
    else state.innerHTML = '<div class="lead">No schematics match</div>';
    const bad = rows.filter(r => r.error).length;
    const noun = rows.length === 1 ? ' schematic' : ' schematics';
    let text = shown.length === rows.length ? rows.length + noun : shown.length + ' of ' + rows.length + noun;
    if (bad) text += ' · ' + bad + (bad === 1 ? ' didn\'t parse' : ' didn\'t parse');
    if (mode === 'refreshing' || first) text = 'Refreshing…';
    count.textContent = text;
    count.classList.toggle('loading', mode === 'refreshing' || first);
  }

  function mrow(l, kind) {
    const el = document.createElement('div');
    const h = kind === 'item' ? (have[l.id] || 0) : 0;
    const short = kind === 'item' && h < l.needed;
    el.className = 'mrow' + (short ? ' short' : '') + (kind === 'item' ? '' : ' plain');
    el.appendChild(l.icon ? Mock.itemImg(l.icon.replace('minecraft:', ''), 'm-icon', 16) : (() => { const i = document.createElement('i'); i.dataset.icon = 'block'; i.className = 'm-icon c-3'; return i; })());
    const t = document.createElement('div'); t.className = 'm-text';
    t.innerHTML = '<div class="m-name"></div>' + (kind === 'item' ? '<div class="m-bar"><i></i></div>' : '');
    $('.m-name', t).textContent = l.name;
    if (l.note) { const n = document.createElement('span'); n.className = 'note'; n.textContent = l.note; $('.m-name', t).appendChild(n); }
    if (kind === 'item') $('.m-bar i', t).style.setProperty('--p', Math.min(100, 100 * h / l.needed) + '%');
    el.appendChild(t);
    const num = document.createElement('div'); num.className = 'm-num';
    num.textContent = kind === 'item' ? fmt(h) + ' / ' + fmt(l.needed) : fmt(l.needed);
    el.appendChild(num);
    return el;
  }

  function renderDetail() {
    const r = rows.find(x => x.path === selected) || null;
    const scroll = $('#sc-mats'), state = $('#sc-rows-state'), head = $('.sc-head', detail);
    detail.classList.toggle('nothing', !r);
    scroll.textContent = '';
    state.className = 'sc-rows-state';
    const build = $('#sc-build'), warn = $('#sc-warn');
    build.disabled = !r || noWorld || !!r.error;
    build.textContent = armed ? 'Click again to build' : 'Build at my feet';
    build.classList.toggle('armed', !!armed);
    warn.classList.add('off');
    if (!r) {
      detail.classList.add('state');
      state.innerHTML = '<div class="lead">Pick a schematic</div><div>Its block list and the build button show here.</div>';
      return;
    }
    $('.name', head).textContent = r.name;
    $('.meta', head).textContent = r.error ? r.format + ' · ' + kb(r.bytes) : r.format + ' · ' + r.size.join(' x ') + ' · ' + kb(r.bytes);
    const unk = $('.unknown', head); unk.textContent = r.unknown || ''; unk.style.display = r.unknown ? '' : 'none';
    if (r.error) {
      $('.sum', head).textContent = 'Didn\'t parse';
      detail.classList.add('state'); state.classList.add('error');
      state.innerHTML = '<div class="lead"></div><div>Re-export it from the mod that made it, or try Refresh.</div>';
      $('.lead', state).textContent = r.error;
      return;
    }
    const m = materials[r.path];
    if (mode === 'counting') {
      $('.sum', head).textContent = 'Counting blocks…';
      detail.classList.add('state');
      state.innerHTML = '<div class="lead">Counting blocks…</div>';
      return;
    }
    detail.classList.remove('state');
    const needed = m.items.reduce((a, l) => a + l.needed, 0);
    const missing = m.items.reduce((a, l) => a + Math.max(0, l.needed - (have[l.id] || 0)), 0);
    const sum = $('.sum', head);
    sum.innerHTML = '';
    sum.append(fmt(needed) + ' blocks · ' + m.items.length + (m.items.length === 1 ? ' kind' : ' kinds'));
    if (missing) { const s = document.createElement('span'); s.className = 'short'; s.textContent = ' · ' + fmt(missing) + ' short'; sum.appendChild(s); }
    else sum.append(' · all in your inventory');
    for (const l of m.items) scroll.appendChild(mrow(l, 'item'));
    for (const [key, label, lines] of [['cantPlace', 'Can\'t place', m.cantPlace], ['notCounted', 'Not counted', m.notCounted]]) {
      if (!lines.length) continue;
      const sec = document.createElement('div');
      sec.className = 'msec' + (open[key] ? ' open' : '');
      sec.innerHTML = '<i data-icon="chevron_right"></i><span></span><span class="cnt"></span>';
      $('span', sec).textContent = label; $('.cnt', sec).textContent = lines.length;
      sec.onclick = () => { open[key] = !open[key]; render(); };
      scroll.appendChild(sec);
      if (open[key]) for (const l of lines) scroll.appendChild(mrow(l, key));
    }
    for (const n of m.notes) { const d = document.createElement('div'); d.className = 'mnote'; d.textContent = n; scroll.appendChild(d); }
    Mock.hydrate(scroll);
    if (missing || m.cantPlace.length) {
      warn.classList.remove('off');
      $('span', warn).textContent = missing ? fmt(missing) + ' short. It pauses when it runs out.' : m.cantPlace.length + ' kind it can\'t place, those spots stay empty.';
    }
  }

  function render() { renderList(); renderDetail(); placeTip(null); if (window.Mock.placeThumbs) Mock.placeThumbs(); }

  function disarm() { armed = 0; clearTimeout(armTimer); }
  $('#sc-build').onclick = () => {
    if (!armed) { armed = 1; armTimer = setTimeout(() => { armed = 0; render(); }, 3000); render(); return; }
    disarm(); render();
  };
  $('#sc-import').onclick = () => { rows.push({ name: 'picked', path: 'picked.litematic', size: [10, 10, 10], blocks: 100, format: 'Litematica v6', bytes: 2048 }); materials['picked.litematic'] = simple; render(); };

  // tooltip: an ellipsized name or a broken row, after the pointer rests for 500 ms
  let tipTimer = 0;
  function placeTip(el, x, y) {
    clearTimeout(tipTimer);
    tip.classList.remove('show');
    if (!el) return;
    const name = $('.name', el);
    const text = el.dataset.tip || (name && name.scrollWidth > name.clientWidth + 1 ? name.textContent : '');
    if (!text) return;
    tipTimer = setTimeout(() => {
      tip.textContent = text; tip.classList.add('show');
      const rr = root.getBoundingClientRect(), z = parseFloat($('#wrap').style.zoom || 1);
      let tx = (x - rr.left) / z + 12, ty = (y - rr.top) / z + 16;
      if (tx + tip.offsetWidth > root.clientWidth - 8) tx = root.clientWidth - 8 - tip.offsetWidth;
      if (ty + tip.offsetHeight > root.clientHeight - 8) ty = (y - rr.top) / z - 8 - tip.offsetHeight;
      tip.style.left = tx + 'px'; tip.style.top = ty + 'px';
    }, 500);
  }
  list.addEventListener('mousemove', e => { const el = e.target.closest('.row'); if (el !== tip._el) { tip._el = el; placeTip(el, e.clientX, e.clientY); } });
  list.addEventListener('mouseleave', () => { tip._el = null; placeTip(null); });

  const sec = root.closest('section');
  $('#hdr-input').addEventListener('input', e => { if (sec.classList.contains('active')) { query = e.target.value; render(); } });
  new MutationObserver(() => { if (sec.classList.contains('active')) { query = $('#hdr-input').value; render(); } }).observe(sec, { attributes: true, attributeFilter: ['class'] });

  render();
  window.Mock.schematics = { render, select: p => { selected = p; render(); } };
})();
