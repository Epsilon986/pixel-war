const colors = { EMPTY: '#080d16', RED: '#ef5350', BLUE: '#42a5f5', GREEN: '#66bb6a', YELLOW: '#ffee58' };
const $ = id => document.getElementById(id);
const number = value => new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 2 }).format(value);
let busy = false;
let currentState;
async function request(path, method = 'GET') {
  const response = await fetch('/api/' + path, { method });
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(body.error || `Erreur HTTP ${response.status}`);
  }
  return response.json();
}
function controls() {
  const allowed = { start: currentState === 'STOPPED', pause: currentState === 'RUNNING', resume: currentState === 'PAUSED', stop: currentState !== 'STOPPED', reset: true };
  document.querySelectorAll('button[data-action]').forEach(button => button.disabled = busy || !allowed[button.dataset.action]);
}
async function refresh() {
  const [state, scores, metrics, preview] = await Promise.all([
    request('simulation'), request('simulation/scores'), request('metrics'), request('board/preview')
  ]);
  currentState = state.state;
  $('state').textContent = state.state;
  controls();
  $('dimensions').textContent = `${state.configuration.width} × ${state.configuration.height} cellules · aperçu ${preview.width} × ${preview.height}`;
  const canvas = $('board');
  canvas.width = preview.width; canvas.height = preview.height;
  const context = canvas.getContext('2d');
  preview.cells.forEach((row, y) => row.forEach((color, x) => {
    context.fillStyle = colors[color]; context.fillRect(x, y, 1, 1);
  }));
  $('scores').replaceChildren(...scores.map(score => {
    const node = document.createElement('div'); node.className = 'score';
    node.style.setProperty('--color', colors[score.player.color]);
    node.textContent = `${score.player.name} · ${number(score.cells)} cellules · ${number(score.percentage)} %`;
    return node;
  }));
  const rows = {
    'Temps actif': `${number(state.elapsedMs / 1000)} s`,
    'Tentatives': number(metrics.simulation.attempts), 'Modifications': number(metrics.simulation.modifications),
    'Tentatives / s': number(metrics.attemptsPerSecond), 'Modifications / s': number(metrics.modificationsPerSecond),
    'Latence moyenne / min / max': `${[metrics.latency.averageNanos, metrics.latency.minimumNanos, metrics.latency.maximumNanos].map(n => number(n / 1e6)).join(' / ')} ms`,
    'Heap utilisée / maximale': `${number(metrics.jvm.heapUsedBytes / 1048576)} / ${number(metrics.jvm.heapMaxBytes / 1048576)} Mio`,
    'Threads / processeurs': `${metrics.jvm.activeThreads} / ${metrics.jvm.availableProcessors}`,
    'CPU processus': metrics.jvm.processCpuLoad === null ? 'Indisponible' : `${number(metrics.jvm.processCpuLoad * 100)} %`,
    'Cellules vides': number(metrics.cellsByColor.EMPTY)
  };
  metrics.players.forEach(p => rows[`${p.player.name} : tentatives / modifications`] = `${number(p.attempts)} / ${number(p.modifications)}`);
  $('metrics').replaceChildren(...Object.entries(rows).flatMap(([label, value]) => {
    const term = document.createElement('dt'); term.textContent = label;
    const definition = document.createElement('dd'); definition.textContent = value;
    return [term, definition];
  }));
}
document.querySelectorAll('button[data-action]').forEach(button => button.addEventListener('click', async () => {
  busy = true; controls();
  try { await request('simulation/' + button.dataset.action, 'POST'); await refresh(); $('error').textContent = ''; }
  catch (error) { $('error').textContent = error.message; }
  finally { busy = false; controls(); }
}));
async function poll() {
  if (!busy) {
    busy = true; controls();
    try { await refresh(); $('error').textContent = ''; }
    catch (error) { $('error').textContent = error.message; }
    finally { busy = false; controls(); }
  }
  setTimeout(poll, 1000);
}
poll();
