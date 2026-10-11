// stats tab: nine fixed cards, ?stats=empty shows the empty line (StatsTab.java)
(function () {
  const root = document.getElementById('st'), grid = document.getElementById('st-grid');
  if (!root) return;
  const STATS = [['Distance travelled', '12.4 km'], ['Blocks mined', '1 234'], ['Blocks placed', '680'], ['Mobs killed', '42'],
    ['Jumps', '207'], ['Time played', '3h 12m'], ['Deaths', '2'], ['Items crafted', '360'], ['Damage dealt', '2 100']];
  const empty = new URLSearchParams(location.search).get('stats') === 'empty';
  root.classList.toggle('empty', empty);
  for (const [label, val] of (empty ? [] : STATS)) {
    const cell = document.createElement('div'); cell.className = 'st-cell';
    cell.innerHTML = '<div class="st-card card"><span class="label t-label"></span><span class="val t-body tnum"></span></div>';
    cell.querySelector('.label').textContent = label; cell.querySelector('.val').textContent = val;
    grid.appendChild(cell);
  }
})();
