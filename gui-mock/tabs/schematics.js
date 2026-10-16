// schematics tab: the library list on the left, what the selected file costs on the right, the placement map and its
// controls under the summary, build at the bottom (SchematicsTab.java, SchematicPane.java, PlacementMap.java). data is
// FakeSchematics.java. ?sc=loading (first load, no rows) ?sc=refreshing (rows + the small label) ?sc=empty ?sc=none
// (nothing selected) ?sc=noworld ?sc=counting (materials still loading) ?sc=nether (placed in another dimension)
// ?sc=parsing ?sc=building ?sc=builderr (the build request states). &rot=1&mir=1 turns and flips, &zoom=1|2|4
(function () {
  const $ = (s, r) => (r || document).querySelector(s);
  const root = $('#sc'); if (!root) return;
  const qs = new URLSearchParams(location.search);
  const mode = qs.get('sc') || '';
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

  // ---- placement: where the picked file goes. FakeSchematics puts the default at the feet (100, 64, -40); the player
  // in FakeFacade stands at 120.5, -30.5 looking a bit west of north. one placement per path, like the real store
  const myDim = 'minecraft:overworld';
  const me = { x: 120.5, z: -30.5, yaw: 200 };
  const placements = {};
  const placementOf = p => placements[p] || (placements[p] = { x: 100, y: 64, z: -40, turns: parseInt(qs.get('rot') || 0, 10) & 3, mirrored: qs.get('mir') === '1', dim: mode === 'nether' ? 'minecraft:the_nether' : myDim });
  const footprintOf = r => { const p = placementOf(r.path), swap = p.turns % 2 === 1; return { x: p.x, z: p.z, sx: swap ? r.size[2] : r.size[0], sz: swap ? r.size[0] : r.size[2] }; };
  const dimName = d => { const s = d.replace(/^minecraft:/, ''); return s === 'the_nether' ? 'the Nether' : s === 'the_end' ? 'the End' : s === 'overworld' ? 'the Overworld' : s.replace(/_/g, ' '); };
  // the request states of build(): IDLE unless the mode says otherwise
  let building = mode === 'parsing' ? { state: 'PARSING' } : mode === 'building' ? { state: 'STARTED' } : mode === 'builderr' ? { state: 'ERROR', message: 'Can\'t build in the Nether from the Overworld' } : { state: 'IDLE' };

  // ---- the map band. scales are whole px per block, wheel steps between them
  const SCALES = [1, 2, 4];
  let scaleIx = Math.max(0, SCALES.indexOf(parseInt(qs.get('zoom') || 2, 10)));
  const map = $('#sc-map'), terrain = $('#sc-terrain'), fp = $('#sc-fp'), meEl = $('#sc-me'), coords = $('#sc-coords'), mapState = $('#sc-map-state');
  // FakeSchematics.fakeColumn: a river with sand banks, a stone hill, trees on grass. 0 = chunk never seen
  const column = (x, z) => {
    const river = Math.abs(x - 60 - Math.round(Math.sin(z / 23) * 18));
    if (river < 5) return ((x + z) & 1) === 0 ? 0xFF3F76E4 : 0xFF3A6CD2;
    if (river < 7) return 0xFFF7E9A3;
    const hx = x - 150, hz = z + 10;
    if (hx * hx + hz * hz < 900) return ((hx * 5 + hz * 3) % 9 + 9) % 9 === 0 ? 0xFF8A8A8A : 0xFF707070;
    if (((x * 31 + z * 17) % 97 + 97) % 97 === 0) return 0xFF007C00;
    return ((x * 7 + z * 13) % 11 + 11) % 11 === 0 ? 0xFF7FB238 : 0xFF6E9A30;
  };
  // the terrain window is 16 x 16 chunks around the centre, chunk aligned, same as Terrain.RADIUS_CHUNKS = 8
  const window16 = (cx, cz) => ({ minX: (((cx + 8) >> 4) - 8) << 4, minZ: (((cz + 8) >> 4) - 8) << 4, size: 256 });
  const known = (win, x, z) => { const cx = (x - win.minX) >> 4, cz = (z - win.minZ) >> 4; return !(cx >= 14 && cz < 3); };

  // the map's height: half of what the header, controls and footer leave, 48..176, the list gets the rest
  function sizeMap() {
    const d = $('#sc-detail');
    const room = d.clientHeight - $('.sc-head', d).offsetHeight - $('.sc-foot', d).offsetHeight - $('#sc-ctl').offsetHeight - 4;
    map.style.height = Math.max(48, Math.min(176, Math.round(room * 0.5))) + 'px';
  }

  let drag = null;
  function renderMap(r) {
    const d = $('#sc-detail');
    const ctl = $('#sc-ctl');
    const p = placementOf(r.path);
    const here = p.dim === myDim;
    for (const b of ctl.querySelectorAll('button')) b.disabled = noWorld || (!here && b.id !== 'sc-feet');
    // the fits of SchematicPane.controlRows: both words on one row, mirror as a glyph on one row, two rows with the
    // words back, mirror's word off again, then three rows. the widths are the real buttons', measured with the words on
    const room = ctl.clientWidth - 16;
    for (const b of ctl.querySelectorAll('.btn span')) b.style.display = '';
    ctl.classList.remove('mid', 'two', 'three');
    const bw = id => $(id).offsetWidth, CH = 30;
    const one = 4 * CH + 4 + 2 * CH + 4, second = 2 * CH + bw('#sc-mirror') + 4 + bw('#sc-feet');
    if (one + second > room) {
      if (one + second - bw('#sc-mirror') + CH <= room) ctl.classList.add('mid');
      else if (second <= room) ctl.classList.add('two');
      else if (second - bw('#sc-mirror') + CH <= room) ctl.classList.add('two', 'mid');
      else ctl.classList.add('three');
    }
    map.classList.toggle('state', noWorld || !here);
    if (noWorld) { mapState.innerHTML = '<div class="lead">Join a world to place it</div>'; return; }
    if (!here) { mapState.innerHTML = '<div class="lead"></div><div>Move to me puts it where you stand.</div>'; $('.lead', mapState).textContent = 'Placed in ' + dimName(p.dim); return; }
    const f = footprintOf(r);
    const s = SCALES[scaleIx], W = map.clientWidth, H = map.clientHeight;
    const cx = f.x + f.sx / 2, cz = f.z + f.sz / 2;
    const win = window16(Math.floor(cx), Math.floor(cz));
    // world (x, z) -> map px: the footprint's middle sits in the middle of the band
    const px = x => Math.round(W / 2 + (x - cx) * s), pz = z => Math.round(H / 2 + (z - cz) * s);
    terrain.width = win.size; terrain.height = win.size;
    terrain.style.width = win.size * s + 'px'; terrain.style.height = win.size * s + 'px';
    terrain.style.left = px(win.minX) + 'px'; terrain.style.top = pz(win.minZ) + 'px';
    const g = terrain.getContext('2d'), img = g.createImageData(win.size, win.size), b = img.data;
    for (let z = 0; z < win.size; z++) for (let x = 0; x < win.size; x++) {
      const i = (z * win.size + x) * 4, wx = win.minX + x, wz = win.minZ + z;
      if (known(win, wx, wz)) { const c = column(wx, wz); b[i] = c >> 16 & 255; b[i + 1] = c >> 8 & 255; b[i + 2] = c & 255; b[i + 3] = 255; }
      // a chunk nobody has seen: the field with a dot every four blocks, a drafting grid with nothing drawn on it
      else if ((wx & 3) === 0 && (wz & 3) === 0) { b[i] = b[i + 1] = b[i + 2] = 136; b[i + 3] = 110; }
    }
    g.putImageData(img, 0, 0);
    const dx = drag ? drag.dx : 0, dz = drag ? drag.dz : 0;
    fp.style.left = px(f.x + dx) + 'px'; fp.style.top = pz(f.z + dz) + 'px';
    fp.style.width = Math.max(2, f.sx * s) + 'px'; fp.style.height = Math.max(2, f.sz * s) + 'px';
    // the front is the file's north after the turn: 0 top, 1 right, 2 bottom, 3 left. the origin corner is the file's
    // 0,0 after the flip and the turn: flipping sends it to the other end of the front edge
    fp.dataset.front = p.turns;
    // the flip sends the origin from the top left to the top right, each cw turn walks it one corner round: the corner
    // is (turns + flip) mod 4 with 0 top left, 1 top right, 2 bottom right, 3 bottom left (PlacementMap.originCorner)
    const o = $('.origin', fp), c = (p.turns + (p.mirrored ? 1 : 0)) & 3, left = c === 0 || c === 3, top = c === 0 || c === 1;
    o.style.left = left ? '-1px' : ''; o.style.right = left ? '' : '-1px';
    o.style.top = top ? '-1px' : ''; o.style.bottom = top ? '' : '-1px';
    // the player, clamped to the edge of the band when they're off it, so you still know which way you are
    let mx = px(me.x), mz = pz(me.z);
    const off = mx < 0 || mz < 0 || mx > W || mz > H;
    meEl.classList.toggle('edge', off);
    meEl.style.left = Math.max(6, Math.min(W - 6, mx)) + 'px'; meEl.style.top = Math.max(6, Math.min(H - 6, mz)) + 'px';
    // minecraft yaw: 0 south, 90 west, 180 north. the arrow art points up (north) so it turns by yaw + 180
    meEl.style.transform = 'rotate(' + (me.yaw + 180) + 'deg)';
    coords.textContent = 'x ' + (p.x + dx) + ' y ' + p.y + ' z ' + (p.z + dz) + ' · ' + p.turns * 90 + '°' + (p.mirrored ? ' · mirrored' : '');
  }

  function move(r, dx, dy, dz) { const p = placementOf(r.path); p.x += dx; p.y += dy; p.z += dz; p.dim = myDim; render(); }
  fp.addEventListener('pointerdown', e => {
    const r = rows.find(x => x.path === selected); if (!r || e.button !== 0) return;
    drag = { x0: e.clientX, y0: e.clientY, dx: 0, dz: 0 };
    fp.classList.add('drag'); fp.setPointerCapture(e.pointerId); e.preventDefault();
  });
  fp.addEventListener('pointermove', e => {
    if (!drag) return;
    const s = SCALES[scaleIx], z = parseFloat($('#wrap').style.zoom || 1);
    drag.dx = Math.round((e.clientX - drag.x0) / z / s); drag.dz = Math.round((e.clientY - drag.y0) / z / s);
    renderMap(rows.find(x => x.path === selected));
  });
  fp.addEventListener('pointerup', e => {
    if (!drag) return;
    const d = drag; drag = null; fp.classList.remove('drag');
    const r = rows.find(x => x.path === selected);
    // one move at the end of the drag, not one per block, so a long drag is one save
    if (r && (d.dx || d.dz)) move(r, d.dx, 0, d.dz); else render();
  });
  map.addEventListener('wheel', e => {
    if (map.classList.contains('state')) return;
    e.preventDefault();
    scaleIx = Math.max(0, Math.min(SCALES.length - 1, scaleIx + (e.deltaY < 0 ? 1 : -1)));
    render();
  }, { passive: false });
  for (const b of document.querySelectorAll('#sc-ctl [data-move]')) b.onclick = e => {
    const r = rows.find(x => x.path === selected); if (!r) return;
    const [dx, dy, dz] = b.dataset.move.split(',').map(Number), n = e.shiftKey ? 8 : 1;
    move(r, dx * n, dy * n, dz * n);
  };
  const turn = cw => { const r = rows.find(x => x.path === selected); if (!r) return; const p = placementOf(r.path), f = footprintOf(r);
    // a quarter turn swaps the sizes, the corner moves half the difference so the middle stays put
    p.x += Math.trunc((f.sx - f.sz) / 2); p.z += Math.trunc((f.sz - f.sx) / 2); p.turns = (p.turns + (cw ? 1 : 3)) & 3; render(); };
  $('#sc-rotl').onclick = () => turn(false);
  $('#sc-rotr').onclick = () => turn(true);
  $('#sc-mirror').onclick = () => { const r = rows.find(x => x.path === selected); if (!r) return; const p = placementOf(r.path); p.mirrored = !p.mirrored; p.turns = (4 - p.turns) & 3; render(); };
  $('#sc-feet').onclick = () => { const r = rows.find(x => x.path === selected); if (!r) return; const p = placementOf(r.path); p.x = 100; p.y = 64; p.z = -40; p.dim = myDim; render(); };
  window.addEventListener('resize', () => { if (selected) render(); });

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
    build.disabled = !r || noWorld || !!r.error || building.state === 'PARSING';
    build.textContent = armed ? 'Click again to build' : 'Build here';
    build.classList.toggle('armed', !!armed);
    warn.classList.add('off'); warn.classList.remove('state', 'error');
    // the map and its controls only exist for a file that parsed
    detail.classList.toggle('nomap', !r || !!r.error);
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
    sizeMap();
    renderMap(r);
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
    // a build request in flight says so in the warning's place: reading, started, or why it didn't start
    if (building.state !== 'IDLE') {
      warn.classList.remove('off'); warn.classList.add(building.state === 'ERROR' ? 'error' : 'state');
      $('span', warn).textContent = building.state === 'PARSING' ? 'Reading the file…' : building.state === 'STARTED' ? 'Building it. Stop is in the bar below.' : building.message;
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
