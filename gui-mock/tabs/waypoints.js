// waypoints tab: sorted rows, hover actions are css, delete confirm / pin / add / search are here (WaypointsTab.java, WaypointRules.java).
// ?wp=empty starts with no waypoints
(function () {
  const $ = (s, r) => (r || document).querySelector(s);
  const root = $('#wp'); if (!root) return;
  const rows = $('#wp-rows'), info = $('#wp-info'), err = $('#wp-err'), nameBox = $('#wp-name'), nameIn = $('input', nameBox);
  const PLAYER = { x: -212.5, y: 70, z: 418.5 };   // SitePreviewTest.java player
  let list = [
    { name: 'home', tag: 'home', x: 118, y: 64, z: -32 }, { name: 'portal', tag: 'user', x: 130, y: 63, z: -28 },
    { name: 'iron cave', tag: 'user', x: 402, y: 23, z: 1190 }, { name: 'village', tag: 'user', x: -1205, y: 71, z: 388 },
    { name: 'death', tag: 'death', x: 77, y: 12, z: -2048 }];
  if (new URLSearchParams(location.search).get('wp') === 'empty') list = [];
  let byName = false, query = '', confirming = null;

  const dist = w => Math.hypot(w.x + 0.5 - PLAYER.x, w.y - PLAYER.y, w.z + 0.5 - PLAYER.z);
  // Coords.formatDistance: "412 m" up to a kilometre, then "1.2 km"
  const fmt = d => { const m = Math.round(d); if (m < 1000) return m + ' m'; const km = m / 1000; return km < 100 ? km.toFixed(1) + ' km' : Math.round(km).toLocaleString('en-US') + ' km'; };
  // java compares lowercased code units, so no localeCompare
  const cmpStr = (a, b) => (a < b ? -1 : a > b ? 1 : 0);
  const cmpName = (a, b) => cmpStr(a.name.toLowerCase(), b.name.toLowerCase()) || cmpStr(a.tag.toLowerCase(), b.tag.toLowerCase());
  function arranged() {
    const q = query.trim().toLowerCase();
    const out = list.filter(w => !q || w.name.toLowerCase().includes(q) || w.tag.toLowerCase().includes(q));
    out.sort(byName ? cmpName : (a, b) => dist(a) - dist(b) || cmpName(a, b));
    // pinned float to the top, still in their own order
    return out.filter(w => w.pin).concat(out.filter(w => !w.pin));
  }

  function render() {
    const shown = arranged();
    rows.textContent = '';
    for (const w of shown) {
      const r = document.createElement('div');
      r.className = 'row' + (w.pin ? ' pin' : '') + (w === confirming ? ' confirm' : '');
      r.innerHTML =
        '<i data-icon="waypoints" class="wic"></i>' +
        '<div class="txt"><div class="top"><span class="name t-label"></span><span class="tag-chip"></span></div><div class="sub"></div></div>' +
        '<div class="dist t-body tnum"><i data-icon="pin" class="pinned"></i><span></span></div>' +
        '<div class="acts"><button class="act pin-b" aria-label="Pin"><i data-icon="pin"></i></button><button class="act go">Go</button>' +
        '<button class="act copy" aria-label="Copy"><i data-icon="copy"></i></button><button class="act del" aria-label="Delete"><i data-icon="trash"></i></button></div>' +
        '<div class="sure"><span class="q t-label">Delete?</span><button class="yes">Yes</button><button class="no">No</button></div>';
      $('.name', r).textContent = w.name; $('.tag-chip', r).textContent = w.tag;
      $('.sub', r).innerHTML = (w.x + ' ' + w.y + ' ' + w.z).replace(/-/g, '<span class="mn">-</span>');
      $('.dist span', r).textContent = fmt(dist(w));
      $('.pin-b', r).onclick = () => { w.pin = !w.pin; render(); };
      $('.copy', r).onclick = () => { try { navigator.clipboard.writeText(w.x + ' ' + w.y + ' ' + w.z); } catch (e) { /* file:// may refuse, the game toasts instead */ } };
      $('.del', r).onclick = () => { confirming = w; render(); arm(); };
      $('.no', r).onclick = () => { confirming = null; render(); };
      $('.yes', r).onclick = () => { list = list.filter(x => x !== w); confirming = null; render(); };
      rows.appendChild(r);
    }
    Mock.hydrate(rows);
    rows.style.display = shown.length ? '' : 'none';
    // WaypointRules.count: "5 waypoints", "2 of 5 waypoints"
    const noun = list.length === 1 ? ' waypoint' : ' waypoints';
    const counted = shown.length === list.length ? list.length + noun : shown.length + ' of ' + list.length + noun;
    info.innerHTML = '<span></span><span class="c-3"></span>';
    info.firstChild.textContent = 'overworld'; info.lastChild.textContent = ' · ' + counted;
    $('#wp-empty').textContent = list.length ? 'No waypoints match' : 'No waypoints yet. Add one here.';
    root.classList.toggle('empty', !shown.length);
  }

  function add() {
    const name = nameIn.value.trim();
    if (!name) { err.textContent = 'Give it a name'; err.classList.add('show'); nameBox.classList.add('invalid'); return; }
    // playerFeet block: floor(y + 0.1251), same as Coords.block
    list.push({ name, tag: $('#wp-tag span').textContent, x: Math.floor(PLAYER.x), y: Math.floor(PLAYER.y + 0.1251), z: Math.floor(PLAYER.z) });
    nameIn.value = ''; render();
  }
  nameIn.addEventListener('input', () => { err.classList.remove('show'); nameBox.classList.remove('invalid'); });
  nameIn.addEventListener('keydown', e => { if (e.key === 'Enter') add(); });
  $('#wp-add').onclick = add;

  $('#wp-sort').addEventListener('click', e => {
    const b = e.target.closest('.seg'); if (!b) return;
    byName = b.textContent === 'Name'; confirming = null; render();
  });
  // the header search is shared, it only filters while this tab is up
  const sec = root.closest('section');
  $('#hdr-input').addEventListener('input', e => {
    if (!sec.classList.contains('active')) return;
    query = e.target.value; confirming = null; render();
  });
  // an unanswered Delete? goes away on its own, on esc, on a click anywhere but Yes/No, and when the tab is left or shown again (onShown/onHidden)
  const cancel = () => { if (confirming) { confirming = null; render(); } };
  let lapse = 0;
  const arm = () => { clearTimeout(lapse); if (confirming) lapse = setTimeout(cancel, 5000); };
  document.addEventListener('keydown', e => { if (e.key === 'Escape' && sec.classList.contains('active')) cancel(); });
  // soft cancel on mousedown: a re-render here would eat the click that follows it
  document.addEventListener('mousedown', e => {
    if (!confirming || e.target.closest('#wp-rows .confirm .sure')) return;
    confirming = null; rows.querySelectorAll('.confirm').forEach(r => r.classList.remove('confirm'));
  }, true);
  // showing the tab again syncs the filter to what the shared box says
  new MutationObserver(() => { confirming = null; if (sec.classList.contains('active')) query = $('#hdr-input').value; render(); })
    .observe(sec, { attributes: true, attributeFilter: ['class'] });

  render();
  window.Mock.waypoints = { render, get list() { return list; } };
})();
