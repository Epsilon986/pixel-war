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
  const allowed = { start: currentState === 'STOPPED', pause: currentState === 'RUNNING', resume: currentState === 'PAUSED', stop: currentState === 'RUNNING' || currentState === 'PAUSED', reset: !!currentState };
  document.querySelectorAll('button[data-action]').forEach(button => button.disabled = busy || !allowed[button.dataset.action]);
}
async function refresh() {
  const [state, scores, metrics, preview] = await Promise.all([
    request('simulation'), request('simulation/scores'), request('metrics'), request('board/preview')
  ]);
  currentState = state.state;
  $('state').textContent = state.state;
  const config = state.configuration;
  const stockDescription = config.stock.enabled
    ? `recharge : ${number(config.stock.refillAmount)} pixels / ${number(config.stock.refillIntervalNs)} ns`
    : 'stock désactivé';
  const strategyDescription = config.strategy === 'frontier'
    ? `frontière · exploration ${number(config.explorationProbability * 100)} % · ${number(config.frontierSampleSize)} candidats`
    : 'aléatoire';
  $('configuration').textContent = `Stratégie : ${strategyDescription} · Action : ${number(config.intervalNs)} ns · maximum ${config.maxPixelsPerAction} pixels/action · ${stockDescription} · conversions ${config.conversionEnabled ? 'activées' : 'désactivées'}`;
  $('dimensions').textContent = `${config.width} × ${config.height} cellules · aperçu ${preview.width} × ${preview.height}`;
  const canvas = $('board'); canvas.width = preview.width; canvas.height = preview.height;
  const context = canvas.getContext('2d');
  preview.cells.forEach((row, y) => row.forEach((color, x) => {
    context.fillStyle = colors[color]; context.fillRect(x, y, 1, 1);
  }));
  $('scores').replaceChildren(...scores.map(score => {
    const playerState = state.players.find(p => p.player.id === score.player.id);
    const node = document.createElement('div'); node.className = 'score';
    node.style.setProperty('--color', colors[score.player.color]);
    const text = document.createElement('div');
    text.textContent = `${score.player.name} · ${number(score.cells)} cellules · ${number(score.percentage)} % · stock ${number(playerState.stock)} / ${number(playerState.stockCapacity)}`;
    const progress = document.createElement('progress'); progress.max = playerState.stockCapacity; progress.value = playerState.stock;
    progress.setAttribute('aria-label', `Stock de ${score.player.name}`);
    if (!config.stock.enabled) text.textContent = `${score.player.name} · ${number(score.cells)} cellules · ${number(score.percentage)} % · stock désactivé`;
    if (playerState.eliminated) text.textContent += ' · Éliminé';
    node.append(text);
    if (config.stock.enabled) node.append(progress);
    return node;
  }));
  const rows = {
    'Temps actif': `${number(metrics.simulation.elapsedMs / 1000)} s`,
    'Actions / s': number(metrics.actionsPerSecond), 'Actions sans stock': number(metrics.actionsWithoutStock),
    'Pixels moyens / action': number(metrics.averagePixelsPerAction),
    'Tentatives / modifications directes': `${number(metrics.simulation.attempts)} / ${number(metrics.simulation.modifications)}`,
    'Tentatives / s': number(metrics.attemptsPerSecond), 'Modifications directes / s': number(metrics.modificationsPerSecond),
    'Pixels consommés / rechargés': `${number(metrics.pixelsConsumed)} / ${number(metrics.pixelsRefilled)}`,
    'Crédits perdus à capacité': number(metrics.pixelsDiscardedAtCapacity),
    'Passes / conversions': `${number(metrics.conversionPasses)} / ${number(metrics.conversions)}`,
    'Conversions / s': number(metrics.conversionsPerSecond), 'Changements totaux / s': number(metrics.boardChangesPerSecond),
    'Passe moyenne / min / max': `${[metrics.conversionDurationNanos.averageNanos, metrics.conversionDurationNanos.minimumNanos, metrics.conversionDurationNanos.maximumNanos].map(n => number(n / 1e6)).join(' / ')} ms`,
    'Action moyenne / min / max': `${[metrics.latency.averageNanos, metrics.latency.minimumNanos, metrics.latency.maximumNanos].map(n => number(n / 1e6)).join(' / ')} ms`,
    'Heap utilisée / maximale': `${number(metrics.jvm.heapUsedBytes / 1048576)} / ${number(metrics.jvm.heapMaxBytes / 1048576)} Mio`,
    'Threads / processeurs': `${metrics.jvm.activeThreads} / ${metrics.jvm.availableProcessors}`,
    'CPU processus': metrics.jvm.processCpuLoad === null ? 'Indisponible' : `${number(metrics.jvm.processCpuLoad * 100)} %`,
    'Cellules vides': number(metrics.cellsByColor.EMPTY),
    'Frontières rouge / bleu / vert / jaune': ['RED', 'BLUE', 'GREEN', 'YELLOW'].map(color => number(metrics.frontiers.sizes[color])).join(' / '),
    'Maintenance des frontières moyenne': `${number(metrics.frontiers.averageNanos / 1e3)} µs / changement`,
    'Stockage des frontières estimé': `${number(metrics.frontiers.storageBytes / 1048576)} Mio`
  };
  metrics.players.forEach(p => rows[`${p.player.name} : actions / sans stock / conversions reçues`] = `${number(p.actions)} / ${number(p.actionsWithoutStock)} / ${number(p.conversionsReceived)}`);
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
