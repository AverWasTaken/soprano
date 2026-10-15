// tab/theme switching, dev bar, and just enough interaction to show states.
// url params: ?tab= ?theme= ?scale= ?bare=1 (no dev bar) ?ref=1 (flush at 48,48, transparent, for screenshots)
//             ?ov=0.5 (overlay on at that opacity) ?diff=1 ?w= ?h= (panel size in design px) ?basket=id:n,id:n
(function () {
  const $ = (s, r) => (r || document).querySelector(s);
  const $$ = (s, r) => Array.from((r || document).querySelectorAll(s));
  const q = new URLSearchParams(location.search);
  const root = document.documentElement;
  const wrap = $('#wrap');

  const TABS = ['get', 'goto', 'follow', 'waypoints', 'schematics', 'stats', 'help', 'settings'];
  const state = { tab: TABS.includes(q.get('tab')) ? q.get('tab') : 'get', theme: q.get('theme') === 'light' ? 'light' : 'dark' };

  // ---- items (FakeFacade.java:290-304) ----
  const GET_IDS = ('oak_log oak_planks stick crafting_table wooden_pickaxe stone_pickaxe iron_pickaxe diamond_pickaxe cobblestone ' +
    'stone furnace coal charcoal iron_ingot iron_ore raw_iron gold_ingot diamond obsidian bucket water_bucket lava_bucket flint ' +
    'flint_and_steel bread cooked_beef cooked_porkchop cooked_chicken apple wheat wheat_seeds bed white_wool string bow arrow shield ' +
    'iron_sword diamond_sword iron_helmet iron_chestplate iron_leggings iron_boots torch ladder chest ender_pearl blaze_rod ' +
    'blaze_powder ender_eye glass sand gravel dirt netherrack gold_block iron_block diamond_block redstone lapis_lazuli emerald ' +
    'book paper sugar_cane leather fishing_rod').split(' ');
  const title = id => id.split('_').map(w => w[0].toUpperCase() + w.slice(1)).join(' ');
  // dark hero basket. the light hero shot used 194/12/20, try ?basket=oak_log:194,oak_planks:12,stick:20
  const basket = new Map([['oak_log', 129], ['oak_planks', 11], ['stick', 10]]);
  if (q.has('basket')) { basket.clear(); q.get('basket').split(',').filter(Boolean).forEach(p => { const [k, v] = p.split(':'); basket.set(k, parseInt(v, 10) || 1); }); }

  // texture or letter tile (McCanvas.java:84-90), same fallback as the java preview
  function itemImg(id, cls, size) {
    const img = new Image();
    img.className = cls; img.alt = ''; img.draggable = false;
    img.src = 'assets/items/' + id + '.png';
    img.onerror = () => {
      const t = document.createElement('div');
      t.className = 'letter-tile ' + cls; t.style.setProperty('--s', size + 'px');
      t.textContent = title(id)[0];
      img.replaceWith(t);
    };
    return img;
  }

  function renderGrid() {
    const grid = $('#get-grid'); grid.textContent = '';
    for (const id of GET_IDS) {
      const cell = document.createElement('div');
      cell.className = 'item-cell'; cell.dataset.id = id;
      const tile = document.createElement('div'); tile.className = 'item-tile';
      tile.appendChild(itemImg(id, 'item-icon', 32));
      const name = document.createElement('div'); name.className = 'item-name'; name.textContent = title(id);
      tile.appendChild(name);
      cell.appendChild(tile);
      cell.classList.toggle('in-basket', basket.has(id));
      if (basket.has(id)) tile.insertAdjacentHTML('beforeend', '<span class="badge">' + badgeText(basket.get(id)) + '</span>');
      cell.addEventListener('click', e => add(id, e.shiftKey ? 64 : e.ctrlKey ? 10 : 1));
      grid.appendChild(cell);
    }
    $('#get-count').textContent = GET_IDS.length + ' items';
  }
  const badgeText = n => n > 999 ? '999+' : String(n);

  function renderBasket() {
    const rows = $('#basket-rows'); rows.textContent = '';
    for (const [id, n] of basket) {
      const r = document.createElement('div'); r.className = 'brow'; r.dataset.id = id;
      r.appendChild(itemImg(id, 'b-icon', 32));
      r.insertAdjacentHTML('beforeend',
        '<div class="b-text"><div class="b-name">' + title(id) + '</div><div class="b-id">' + id + '</div></div>' +
        '<div class="b-ctl">' +
          '<button class="btn ghost sq step minus"><i data-icon="minus"></i></button>' +
          '<label class="text-field count"><input value="' + n + '" maxlength="4" inputmode="numeric"></label>' +
          '<button class="btn ghost sq step plus"><i data-icon="plus"></i></button>' +
          '<button class="btn ghost sq bin"><i data-icon="trash"></i></button>' +
        '</div>');
      const inp = $('input', r);
      inp.addEventListener('input', () => { inp.value = inp.value.replace(/\D/g, ''); });
      inp.addEventListener('change', () => set(id, parseInt(inp.value || '0', 10)));
      $('.minus', r).onclick = () => set(id, Math.max(1, basket.get(id) - 1));
      $('.plus', r).onclick = () => set(id, Math.min(9999, basket.get(id) + 1));
      $('.bin', r).onclick = () => { basket.delete(id); refresh(); };
      rows.appendChild(r);
    }
    Icons.hydrate(rows);
    const total = [...basket.values()].reduce((a, b) => a + b, 0);
    const kinds = basket.size, dock = $('.dock'), go = $('#basket-go'), sum = $('#basket-summary');
    dock.classList.toggle('empty', kinds === 0);
    if (kinds === 0) {
      sum.textContent = 'Nothing added yet. Click an item to add it; Shift-click adds a stack, Ctrl-click adds 10.';
      go.textContent = 'Get items'; go.disabled = true;
    } else {
      sum.textContent = kinds + (kinds === 1 ? ' item' : ' items') + ' · ' + total + ' total';
      go.textContent = 'Get ' + total + (total === 1 ? ' item' : ' items'); go.disabled = false;
    }
  }
  function set(id, n) { if (n <= 0) basket.delete(id); else basket.set(id, Math.min(9999, n)); refresh(); }
  function add(id, n) { set(id, (basket.get(id) || 0) + n); }
  function refresh() {
    renderBasket();

    for (const cell of $$('.item-cell')) {
      const id = cell.dataset.id, has = basket.has(id), tile = cell.firstChild;
      cell.classList.toggle('in-basket', has);
      let b = $('.badge', tile);
      if (has) { if (!b) { b = document.createElement('span'); b.className = 'badge'; tile.appendChild(b); } b.textContent = badgeText(basket.get(id)); }
      else if (b) b.remove();
    }
    placeThumbs();   // dock height changed, so did the grid (reading clientHeight forces the layout)
  }
  $('#basket-clear').onclick = () => { basket.clear(); refresh(); };


  // grid columns = (w - gutter) / 88, cell width floors to a whole px like VirtualGrid, leftover px sits on the right
  function layoutGrid() {
    const g = $('#get-grid'); if (!g.clientWidth) return;
    const w = g.clientWidth - parseFloat(getComputedStyle(g).paddingRight);
    const cols = Math.max(1, Math.floor(w / 88)), cell = Math.floor(w / cols);
    g.style.gridTemplateColumns = 'repeat(' + cols + ', ' + cell + 'px)';
  }
  new ResizeObserver(layoutGrid).observe($('#get-grid'));

  // ---- common setting tiles (FakeFacade.java:87-127) ----
  const TILES = [['Boats', 'boat', 0], ['Parkour', 'parkour', 0], ['Fast mode', 'bolt', 0], ['Sprint', 'run', 1], ['Break blocks', 'pickaxe', 1],
    ['Place blocks', 'block', 1], ['Auto tool', 'wrench', 1], ['Inventory', 'backpack', 0], ['Water clutch', 'water', 1],
    ['Ladder clutch', 'ladder', 0], ['Smooth look', 'eye', 0], ['Show path', 'goto', 1]];
  function renderTiles() {
    const box = $('#tiles'); box.textContent = '';
    for (const [name, icon, on] of TILES) {
      const t = document.createElement('div');
      t.className = 'setting-tile' + (on ? ' on on-accent' : '');
      t.innerHTML = '<i data-icon="' + icon + '" data-size="24" class="glyph"></i><div class="name">' + name + '</div><div class="state">' + (on ? 'On' : 'Off') + '</div>';
      t.onclick = () => { const o = t.classList.toggle('on'); t.classList.toggle('on-accent', o); $('.state', t).textContent = o ? 'On' : 'Off'; };
      box.appendChild(t);
    }
    Icons.hydrate(box);
  }

  // ---- segmented thumbs ----
  function layoutSeg(seg, instant) {
    const on = $('.seg.on', seg); if (!on || !on.offsetWidth) return;
    if (instant) seg.style.setProperty('--tt', '0s');
    // fractional widths matter (46.27 vs 46), offsetWidth rounds. k undoes whatever the css zoom did to the rects
    const sr = seg.getBoundingClientRect(), or = on.getBoundingClientRect(), k = sr.width / seg.offsetWidth || 1;
    seg.style.setProperty('--x', (or.left - sr.left) / k + 'px'); seg.style.setProperty('--w', or.width / k + 'px');
    seg.classList.add('js');
    if (instant) { seg.getBoundingClientRect(); seg.style.removeProperty('--tt'); }
  }
  const layoutAllSeg = instant => $$('.segmented').forEach(s => layoutSeg(s, instant));
  $$('.segmented').forEach(seg => seg.addEventListener('click', e => {
    const b = e.target.closest('.seg'); if (!b) return;
    $$('.seg', seg).forEach(x => x.classList.toggle('on', x === b)); layoutSeg(seg);
  }));
  $$('.toggle').forEach(t => t.addEventListener('click', () => t.classList.toggle('on')));
  $$('.dropdown').forEach(d => d.addEventListener('click', () => d.classList.toggle('open')));
  $$('.cat-pill').forEach(p => p.addEventListener('click', () => { $$('.cat-pill', p.parentNode).forEach(x => x.classList.toggle('on', x === p)); }));
  // key capture: click, press a key, it becomes the label. Escape cancels, Backspace unbinds (KeyCapture.java)
  let cancelCap = null;
  $$('.keycap').forEach(k => k.addEventListener('click', () => {
    if (k.classList.contains('capturing')) return;
    if (cancelCap) cancelCap();   // only one cap listens at a time
    k.dataset.t = k.textContent; k.classList.add('capturing'); k.textContent = 'Press a key';
    const done = (txt) => { k.classList.remove('capturing'); k.textContent = txt; cancelCap = null; document.removeEventListener('keydown', onKey, true); };
    cancelCap = () => done(k.dataset.t);
    const onKey = e => {
      e.preventDefault(); e.stopPropagation();
      if (e.key === 'Escape') return done(k.dataset.t);
      if (e.key === 'Backspace') return done('None');
      const names = { Shift: e.code === 'ShiftRight' ? 'Right Shift' : 'Left Shift', Control: e.code === 'ControlRight' ? 'Right Control' : 'Left Control', Alt: e.code === 'AltRight' ? 'Right Alt' : 'Left Alt', ' ': 'Space' };
      done(names[e.key] || (e.key.length === 1 ? e.key.toUpperCase() : e.key));
    };
    document.addEventListener('keydown', onKey, true);
  }));


  // ---- scroll thumbs: 4 wide, 3 from the right edge, track inset 4, min 24, visible on hover and 600ms after a scroll ----
  function thumbFor(el) {
    const th = document.createElement('div'); th.className = 'scroll-thumb';
    el.parentNode.appendChild(th);
    let hide = 0, hover = false, drag = false;
    new ResizeObserver(() => place()).observe(el);   // box resized (content changes call placeThumbs themselves)
    const place = () => {
      const max = el.scrollHeight - el.clientHeight;
      if (max <= 0 || !el.clientHeight) { th.style.display = 'none'; return; }
      const track = el.clientHeight - 8, len = Math.max(24, Math.round(track * el.clientHeight / el.scrollHeight));
      th.style.display = '';
      th.style.height = len + 'px';
      th.style.left = (el.offsetLeft + el.offsetWidth - 3 - th.offsetWidth) + 'px';
      th.style.top = (el.offsetTop + 4 + (track - len) * el.scrollTop / max) + 'px';
    };
    const flash = () => { th.classList.add('show'); clearTimeout(hide); hide = setTimeout(() => { if (!hover && !drag) th.classList.remove('show'); }, 600); };
    el.addEventListener('scroll', () => { place(); flash(); });
    el.addEventListener('mouseenter', () => { hover = true; place(); th.classList.add('show'); });
    el.addEventListener('mouseleave', () => { hover = false; flash(); });
    th.addEventListener('mouseenter', () => { hover = true; });
    th.addEventListener('mouseleave', () => { hover = false; flash(); });
    th.addEventListener('mousedown', e => {
      drag = true; th.classList.add('drag'); const y0 = e.clientY, s0 = el.scrollTop, z = parseFloat(wrap.style.zoom || 1);
      const move = ev => { const track = el.clientHeight - 8 - th.offsetHeight; el.scrollTop = s0 + (ev.clientY - y0) / z / track * (el.scrollHeight - el.clientHeight); };
      const up = () => { drag = false; th.classList.remove('drag'); document.removeEventListener('mousemove', move); document.removeEventListener('mouseup', up); flash(); };
      document.addEventListener('mousemove', move); document.addEventListener('mouseup', up); e.preventDefault();
    });
    el._place = place;
  }
  $$('.scroll').forEach(thumbFor);
  const placeThumbs = () => $$('.scroll').forEach(e => e._place && e._place());
  new ResizeObserver(placeThumbs).observe($('#panel'));

  // ---- tabs ----
  function showTab(tab, instant) {
    state.tab = tab;
    $$('.content > section').forEach(s => s.classList.toggle('active', s.dataset.tab === tab));
    const sec = $('.content > section.active');
    $('#hdr-title').textContent = (sec && sec.dataset.title) || tab;
    const search = $('#hdr-search');
    search.style.visibility = sec && sec.dataset.search ? 'visible' : 'hidden';
    $('#hdr-input').placeholder = (sec && sec.dataset.search) || '';
    $$('.nav-row').forEach(r => { const a = r.dataset.go === tab; r.classList.toggle('active', a); r.classList.toggle('on-accent', a); });
    const row = $('.nav-row.active'), pill = $('#nav-pill');
    if (instant) pill.style.transition = 'none';
    pill.style.top = row.offsetTop + 'px';
    if (instant) { pill.getBoundingClientRect(); pill.style.transition = ''; }
    $('#dv-tab').value = tab;
    layoutAllSeg(instant);
    placeThumbs(); layoutGrid();
    updateOverlay();
    const u = new URL(location.href); u.searchParams.set('tab', tab); history.replaceState(null, '', u);
  }
  $$('.nav-row').forEach(r => r.addEventListener('click', () => showTab(r.dataset.go)));

  // ---- theme ----
  function setTheme(t, animate) {
    if (animate) { document.body.classList.add('theming'); setTimeout(() => document.body.classList.remove('theming'), 340); }
    state.theme = t; root.dataset.theme = t;
    $$('#theme-seg .seg').forEach(s => s.classList.toggle('on', s.dataset.themeSet === t)); layoutSeg($('#theme-seg'));
    $('#dv-theme').value = t;
    updateOverlay();
  }
  $$('#theme-seg .seg').forEach(s => s.addEventListener('click', () => setTheme(s.dataset.themeSet, true)));

  // ---- status strip: busy = accent dot + danger stop, idle = ring + ghost stop ----
  function setIcon(el, name) {
    const cls = el.getAttribute('class').split(' ').filter(c => c && c !== 'icon' && !/^ic(-|$)/.test(c)).join(' ');
    el.outerHTML = '<i data-icon="' + name + '" data-size="12" class="' + cls + '" id="' + el.id + '"></i>';
    Icons.hydrate($('.status'));
  }
  function setBusy(b) {
    const ic = $('#status-icon');
    setIcon(ic, b ? 'dot' : 'ring');
    $('#status-icon').classList.toggle('busy', b);
    $('#status-line').textContent = b ? 'Mining iron_ore' : 'Idle';
    $('.status-sep').style.display = $('#status-detail').style.display = b ? '' : 'none';
    const stop = $('#stop-btn'); stop.classList.toggle('danger', b); stop.classList.toggle('ghost', !b);
  }

  // ---- dev bar ----
  const tabSel = $('#dv-tab');
  TABS.forEach(t => tabSel.add(new Option(t, t)));
  tabSel.onchange = () => showTab(tabSel.value);
  $('#dv-theme').onchange = e => setTheme(e.target.value, true);
  const setScale = s => { wrap.style.zoom = s; root.style.setProperty('--zoom', s); $('#dv-scale').value = String(s); readout(); };
  $('#dv-scale').onchange = e => setScale(parseFloat(e.target.value));
  $('#dv-busy').onchange = e => setBusy(e.target.checked);

  const ov = $('#overlay');
  function updateOverlay() {
    const src = '../build/gui-preview/site/' + state.tab + '_' + state.theme + '.png';
    if (ov.dataset.src !== src) { ov.dataset.src = src; ov.src = src; }
  }
  ov.onerror = () => { if ($('#dv-ov').checked) $('#dv-size').textContent = 'overlay png missing, run the java preview tests'; };
  ov.onload = readout;
  const setOv = () => {
    document.body.classList.toggle('ov-on', $('#dv-ov').checked);
    document.body.classList.toggle('ov-diff', $('#dv-diff').checked);
    ov.style.opacity = $('#dv-op').value;
    // difference alone is near black when things line up, amp brightens the panel so small misses show
    wrap.style.filter = $('#dv-diff').checked && $('#dv-ov').checked ? 'brightness(' + $('#dv-amp').value + ')' : '';
  };
  ['#dv-ov', '#dv-diff', '#dv-amp'].forEach(s => $(s).addEventListener('change', setOv));
  $('#dv-op').addEventListener('input', setOv);

  function readout() {
    const p = $('#panel'), z = parseFloat(wrap.style.zoom || 1);
    const w = p.offsetWidth, h = p.offsetHeight;
    $('#dv-size').textContent = w + ' x ' + h + ' design px' + (z !== 1 ? '  (' + Math.round(w * z) + ' x ' + Math.round(h * z) + ' on screen)' : '');
  }
  document.addEventListener('keydown', e => {
    if (/input|select|textarea/i.test(e.target.tagName)) return;
    if (e.key === 'h' && !$('.keycap.capturing')) document.body.classList.toggle('nobar');
  });

  // hairlines are 1 device px, so they need the browser's own pixel ratio too
  const setDpr = () => { root.style.setProperty('--dpr', window.devicePixelRatio || 1); };
  window.addEventListener('resize', setDpr);

  // later tabs: build your dom, then call Mock.hydrate(el) / Mock.thumbFor(scrollEl) / Mock.layoutSeg(segEl)
  window.Mock = { hydrate: el => Icons.hydrate(el), thumbFor, layoutSeg, itemImg, title, showTab, setTheme, placeThumbs };

  // ---- boot ----
  setDpr();
  if (q.get('w')) wrap.style.setProperty('--panel-w', parseInt(q.get('w'), 10) + 'px');
  if (q.get('h')) wrap.style.setProperty('--panel-h', parseInt(q.get('h'), 10) + 'px');
  if (q.get('ref')) root.classList.add('ref');
  if (q.get('bare')) document.body.classList.add('nobar');
  if (q.get('ov')) { $('#dv-ov').checked = true; $('#dv-op').value = q.get('ov'); }
  if (q.get('diff')) $('#dv-diff').checked = true;
  setOv();

  root.classList.add('boot');
  Icons.hydrate(document);
  renderGrid(); renderBasket(); renderTiles();
  root.dataset.theme = state.theme;
  setTheme(state.theme, false); layoutAllSeg(true);
  setScale(parseFloat(q.get('scale')) || 1);
  showTab(state.tab, true);
  (document.fonts ? document.fonts.ready : Promise.resolve()).then(() => { layoutAllSeg(true); const r = $('.nav-row.active'); $('#nav-pill').style.top = r.offsetTop + 'px'; readout(); });
  window.addEventListener('load', () => { layoutAllSeg(true); requestAnimationFrame(() => root.classList.remove('boot')); });
})();
