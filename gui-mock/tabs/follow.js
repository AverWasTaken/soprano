// follow tab behaviour (tabs/FollowContent.java, model/EntityGroups.java). data is FakeFacade.java:352-373.
// url: ?following=Steve (default, the hero) | ?following=none | ?following=every%20zombie
//      ?follow=empty (nothing nearby) | ?follow=full (all five players) | ?follow=none (search with no matches)
//      ?fq=zom (search text) | ?open=zombie,cow (expanded groups)
(function () {
  const $ = (s, r) => (r || document).querySelector(s);
  const q = new URLSearchParams(location.search);
  const root = $('#follow');
  if (!root) return;

  const mode = q.get('follow');
  const PLAYERS = [['aver', 0, true], ['Steve', 12.4], ['Alex', 48], ['xXx_Notch_xXx', -1], ['Herobrine', -1]]
    .map(([n, d, self]) => ({ n, d, self: !!self }));
  // type, type name, display name, distance. ids count up from 100 like the fixture
  const RAW = [['zombie', 'Zombie', '', 8.2], ['zombie', 'Zombie', '', 14.9], ['zombie', 'Zombie', '', 31.0], ['cow', 'Cow', '', 5.1], ['cow', 'Cow', '', 6.3],
    ['sheep', 'Sheep', '', 9.9], ['wolf', 'Wolf', 'Biscuit', 2.0], ['creeper', 'Creeper', '', 22.7], ['skeleton', 'Skeleton', '', 27.4], ['item', 'Item', 'Oak Log', 3.3],
    ['villager', 'Villager', '', 40.2], ['villager', 'Villager', '', 41.8], ['chicken', 'Chicken', '', 11.0], ['pig', 'Pig', '', 17.5], ['bat', 'Bat', '', 60.0]];
  const ENTITIES = RAW.map(([t, tn, dn, d], i) => ({ id: 100 + i, type: t, typeName: tn, name: dn, d, icon: t === 'item' ? 'oak_log' : t + '_spawn_egg' }));

  const players = mode === 'empty' ? [] : mode === 'full' ? PLAYERS : PLAYERS.slice(0, 3);   // the hero trims to three
  const entities = mode === 'empty' ? [] : ENTITIES;
  let query = q.get('fq') || (mode === 'none' ? 'qq' : '');
  let following = q.has('following') ? (q.get('following') === 'none' ? null : q.get('following')) : 'Steve';
  const expanded = new Set((q.get('open') || '').split(',').filter(Boolean));
  const closed = new Set();
  let toggled = null;

  const low = s => (s || '').toLowerCase();
  const meters = d => Math.max(0, Math.round(d)) + ' m';
  const label = e => e.name || e.typeName + ' #' + e.id;
  const typeMatches = (e, s) => low(e.typeName).includes(s) || low(e.type).includes(s);

  function groupsFor(list, qs) {
    const by = new Map();
    for (const e of list) {
      if (qs && !typeMatches(e, qs) && !(e.name && low(e.name).includes(qs))) continue;
      if (!by.has(e.type)) by.set(e.type, []);
      by.get(e.type).push(e);
    }
    const out = [];
    for (const [type, ms] of by) {
      ms.sort((a, b) => a.d - b.d || a.id - b.id);
      out.push({ type, typeName: ms[0].typeName, icon: type === 'item' ? 'oak_log' : ms[0].icon, count: ms.length, nearest: ms[0].d, members: ms, memberOnly: !!qs && !typeMatches(ms[0], qs) });
    }
    return out.sort((a, b) => a.nearest - b.nearest || (a.type < b.type ? -1 : 1));
  }
  // a search that found a mob by its own name opens the group unless they closed it
  const isOpen = g => expanded.has(g.type) || (g.memberOnly && !closed.has(g.type));
  const followsGroup = g => {
    const f = low(following);
    if (!f.startsWith('every ')) return false;
    const r = f.slice(6).trim();
    const hit = x => x === low(g.typeName) || x === low(g.type);
    return hit(r) || (r.endsWith('s') && hit(r.slice(0, -1)));
  };
  const followsOneOf = g => { const f = low(following); return !!f && (f === low(g.typeName) || g.members.some(m => m.name && f === low(m.name))); };
  const followsEntity = e => { const f = low(following); return !!f && (f === low(label(e)) || (!!e.name && f === low(e.name))); };

  // ---- dom helpers ----
  function el(tag, cls, html) { const n = document.createElement(tag); if (cls) n.className = cls; if (html != null) n.innerHTML = html; return n; }
  const act = (stop, text) => '<span class="fl-act ' + (stop ? 'stop' : 'go') + '">' + text + '</span>';
  const ico = (name, size, cls) => '<i data-icon="' + name + '"' + (size ? ' data-size="' + size + '"' : '') + (cls ? ' class="' + cls + '"' : '') + '></i>';

  function playerRow(p) {
    const near = p.d >= 0, followed = !!following && low(following) === low(p.n), far = !near && !p.self;
    const r = el('div', 'row' + (followed ? ' followed' : '') + (p.self ? ' self' : '') + (far ? ' far' : ''));
    r.innerHTML = '<span class="fl-face' + (far ? ' far' : '') + '">' + ico('player', 16.8) + '</span><span class="fl-name">' + p.n + '</span>' +
      (p.self ? '<span class="fl-chip">you</span>' : '<span class="fl-dist' + (near ? '' : ' far') + '">' + (near ? meters(p.d) : 'not nearby') + '</span>') +
      (near && !p.self ? act(followed, followed ? 'Stop' : 'Follow') : '');
    // the whole row follows (even someone not nearby), only the red Stop on the followed row stops
    r.addEventListener('click', e => {
      if (p.self) return;
      following = followed && e.target.closest('.fl-act') ? null : p.n;
      render();
    });
    return r;
  }

  function groupRow(g) {
    const open = isOpen(g), followed = followsGroup(g) || followsOneOf(g), stop = followsGroup(g);
    const r = el('div', 'row group' + (followed ? ' followed' : '') + (g.memberOnly ? ' nobtn' : ''));
    const prev = toggled === g.type ? !open : open;   // render the old state first so the cross fade has something to fade from
    r.innerHTML = '<span class="fl-chev' + (prev ? ' open' : '') + '">' + ico('chevron_right', 16, 'c-right') + ico('chevron_down', 16, 'c-down') + '</span>' +
      '<span class="fl-tile">' + (g.icon ? '<img src="assets/items/' + g.icon + '.png" alt="" draggable="false">' : ico('entity', 16)) + '</span>' +
      '<span class="fl-name">' + g.typeName + '</span><span class="fl-chip">x' + g.count + '</span>' +
      '<span class="fl-dist">' + meters(g.nearest) + '</span>' + (g.memberOnly ? '' : act(stop, stop ? 'Stop' : 'Follow all'));
    if (toggled === g.type) {
      const c = r.querySelector('.fl-chev');
      requestAnimationFrame(() => requestAnimationFrame(() => c.classList.toggle('open', open)));
    }
    r.addEventListener('click', e => {
      if (e.target.closest('.fl-act')) { following = stop ? null : 'every ' + low(g.typeName); render(); return; }
      toggled = g.type;
      if (open) { expanded.delete(g.type); closed.add(g.type); } else { expanded.add(g.type); closed.delete(g.type); }
      render();
    });
    return r;
  }

  function memberRow(m) {
    const followed = followsEntity(m), named = !!m.name;
    const r = el('div', 'row member' + (followed ? ' followed' : ''));
    r.innerHTML = '<span class="fl-name">' + label(m) + '</span>' + (named ? '<span class="fl-type">' + m.typeName + '</span>' : '') +
      '<span class="fl-dist">' + meters(m.d) + '</span>' + act(followed, followed ? 'Stop' : 'Follow');
    // the fixture follows "zombie #101" so the member itself lights up and gets Stop
    r.addEventListener('click', e => { following = followed && e.target.closest('.fl-act') ? null : label(m); render(); });
    return r;
  }

  // empty and no-match text, drawn only when the list box is at least 40 tall (drawEmpty)
  function setEmpty(listEl, empty, line, hint) {
    listEl.classList.toggle('empty', empty);
    let n = $('.fl-empty', listEl);
    if (n) n.remove();
    if (!empty) return;
    n = el('div', 'fl-empty', '<div class="l1">' + line + '</div><div class="l2">' + hint + '</div>');
    listEl.appendChild(n);
    n.style.display = listEl.offsetHeight < 40 ? 'none' : '';
  }

  const pl = $('#fl-plist'), elist = $('#fl-elist');
  const pCard = $('.fl-card', pl), eCard = $('.fl-card', elist);

  function render() {
    root.classList.toggle('idle', !following);
    $('#fl-name').textContent = following || '';

    const qs = low(query).trim();
    const shown = players.filter(p => !qs || low(p.n).includes(qs))
      .sort((a, b) => (a.d < 0) - (b.d < 0) || (!a.self) - (!b.self) || a.d - b.d || (low(a.n) < low(b.n) ? -1 : 1));
    const nearby = shown.filter(p => !p.self && p.d >= 0).length;
    pCard.textContent = '';
    shown.forEach(p => pCard.appendChild(playerRow(p)));
    $('#fl-phead').innerHTML = 'Players<span class="c-3"> · ' + nearby + ' nearby</span>';

    const groups = groupsFor(entities, qs);
    eCard.textContent = '';
    let n = 0;
    for (const g of groups) {
      n += g.count;
      eCard.appendChild(groupRow(g));
      if (isOpen(g)) g.members.forEach(m => eCard.appendChild(memberRow(m)));
    }
    $('#fl-ehead').innerHTML = 'Entities<span class="c-3"> · ' + n + ' nearby</span>';
    toggled = null;
    Mock.hydrate(root);
    layout();
    setEmpty(pl, !shown.length, qs ? 'No players match' : 'No players nearby', qs ? 'Try a different name.' : 'Anyone on the server shows up here.');
    setEmpty(elist, !groups.length, qs ? 'No entities match' : 'Nothing nearby', qs ? 'Try a mob name, like zombie.' : 'Walk somewhere livelier and it fills in.');
    [pl, elist].forEach(l => { const s = $('.fl-scroll', l); if (s._place) s._place(); });
  }

  // stacked layout: the players list gets whole rows, 2 to 4, half the room at most (FollowContent.layout)
  function layout() {
    const strip = following ? 52 : 0;
    const cy = 16 + strip + 30 + 24, bottom = root.clientHeight - 16;
    const avail = Math.max(0, bottom - cy - 24), hdr = 28;
    const fit = Math.floor((avail * 0.5 - hdr) / 36);
    const rows = Math.max(2, Math.min(Math.min(4, pCard.children.length), fit));
    pl.style.flex = 'none';
    pl.style.height = rows * 36 + 'px';
  }

  // the row button slot is ceil(measured "Follow all") + 20 wide
  function measureBtn() {
    const c = document.createElement('canvas').getContext('2d');
    c.font = '500 14px Inter';
    root.style.setProperty('--fl-bw', Math.ceil(c.measureText('Follow all').width) + 20 + 'px');
  }

  $('#fl-stop').onclick = () => { following = null; render(); };
  $('#fl-all-p').onclick = () => { following = 'all players'; render(); };
  $('#fl-all-e').onclick = () => { following = 'all entities'; render(); };
  $('#hdr-input').addEventListener('input', e => {
    const s = $('.content > section.active');
    if (s && s.dataset.tab === 'follow') { query = e.target.value; render(); }
  });

  if (query) $('#hdr-input').value = query;   // the shell search field shows what is being searched
  Mock.thumbFor($('.fl-scroll', pl));
  Mock.thumbFor($('.fl-scroll', elist));
  new ResizeObserver(layout).observe(root);
  measureBtn();
  render();
  if (document.fonts) document.fonts.load('500 14px Inter').then(() => { measureBtn(); render(); });
  window.addEventListener('load', () => { measureBtn(); render(); });
})();
