const colors = { EMPTY: '#080d16', RED: '#ef5350', BLUE: '#42a5f5', GREEN: '#66bb6a', YELLOW: '#ffee58' };
const $ = id => document.getElementById(id);
const number = value => new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 2 }).format(value);
let busy = false;
let currentState;
let completedTrial = false;
let stockEnabled = true;

async function request(path, method = 'GET', body) {
  const response = await fetch('/api/' + path, { method,
    ...(body === undefined ? {} : { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }) });
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(body.error || `Erreur HTTP ${response.status}`);
  }
  return response.json();
}
function controls() {
  const allowed = { start: currentState === 'STOPPED' && !completedTrial, pause: currentState === 'RUNNING', resume: currentState === 'PAUSED' && !completedTrial, stop: currentState === 'RUNNING' || currentState === 'PAUSED', reset: !!currentState };
  document.querySelectorAll('button[data-action]').forEach(button => button.disabled = busy || !allowed[button.dataset.action]);
  $('trial-start').disabled = busy || !currentState || stockEnabled;
  $('trial-actions').disabled = busy;
  $('trial-seed').disabled = busy;
}
async function refresh() {
  const [state, scores, metrics, preview] = await Promise.all([
    request('simulation'), request('simulation/scores'), request('performance'), request('board/preview')
  ]);
  currentState = state.state;
  $('state').textContent = state.state;
  const config = state.configuration;
  stockEnabled = config.stock.enabled;
  completedTrial = metrics.engine.completed;
  const stockDescription = config.stock.enabled
    ? `recharge : ${number(config.stock.refillAmount)} pixels / ${number(config.stock.refillIntervalNs)} ns`
    : 'stock désactivé';
  const strategyDescription = config.strategy === 'frontier'
    ? `frontière · exploration ${number(config.explorationProbability * 100)} % · ${number(config.frontierSampleSize)} candidats · bonus adversaire faible ${number(config.weakOpponentBonus)} · cible conservée ${number(config.targetHoldActions)} actions`
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
  renderPerformance(metrics, state);
}
function duration(nanos) {
  if (nanos === null || nanos === undefined) return 'Indisponible';
  return nanos < 1e6 ? `${number(nanos / 1e3)} µs` : nanos < 1e9 ? `${number(nanos / 1e6)} ms` : `${number(nanos / 1e9)} s`;
}
function waitDuration(nanos) { return nanos === null || nanos === undefined ? 'Indisponible' : `${number(nanos / 1e6)} ms`; }
function percentiles(group) {
  return group.samples === 0 ? (completedTrial ? 'Aucune requête pendant l’essai' : 'En attente de requêtes') : `${duration(group.p95Nanos)} / ${duration(group.p99Nanos)} · ${number(group.samples)} req.`;
}
function renderPerformance(data, state) {
  const engine = data.engine, measures = engine.measurements, fixed = engine.targetActions !== null;
  $('trial-state').textContent = engine.failure ? `Échec : ${engine.failure}` : fixed
    ? `${engine.completed ? 'Terminé' : state.state === 'RUNNING' ? 'En cours' : 'Interrompu'} · ${number(measures.actions)} / ${number(engine.targetActions)} actions · graine ${engine.seed}`
    : `Simulation libre · ${number(measures.actions)} actions`;
  $('trial-progress').hidden = !fixed;
  $('trial-progress').max = engine.targetActions || 1;
  $('trial-progress').value = measures.actions;
  $('trial-help').textContent = stockEnabled
    ? 'Le stock doit être désactivé au démarrage pour comparer une charge reproductible. Les mesures de simulation libre restent disponibles.'
    : 'Un nouvel essai efface le plateau et les mesures, utilise la graine indiquée et se met en pause après exactement N actions. Le polling reste actif.';
  const rows = [
    [fixed ? 'Temps pour N actions' : 'Temps actif mesuré', duration(measures.elapsedNanos), fixed ? `${number(measures.actions)} / ${number(engine.targetActions)} actions${engine.completed ? ' · charge terminée' : ' · mesure partielle'}` : 'Lancer un essai pour définir une charge fixe'],
    ['Tentatives / s', number(measures.attemptsPerSecond), `${number(measures.attempts)} tentatives depuis le début de la mesure`],
    ['Octets alloués / action', measures.allocatedBytesPerAction === null ? 'Indisponible' : `${number(measures.allocatedBytesPerAction)} o`, 'Allocations du thread moteur, hors requêtes HTTP'],
    ['Temps CPU de la charge', duration(measures.cpuNanos), 'Thread moteur · sommeil et attente exclus'],
    ['Heap maximale observée', `${number(measures.peakObservedHeapBytes / 1048576)} Mio`, 'Heap utilisée par toute la JVM · maximum échantillonné'],
    ['Attente des verrous', waitDuration(measures.engineBlockedNanos), `Moteur · requêtes achevées : ${waitDuration(data.http.completedRequestsBlockedNanos)}`],
    ['Latence HTTP P95 / P99', `Lectures : ${percentiles(data.http.reads)}`, `Commandes : ${percentiles(data.http.commands)}`]
  ];
  $('metrics').replaceChildren(...rows.map(([label, value, detail]) => {
    const card = document.createElement('article'); card.className = 'metric-card';
    const title = document.createElement('h3'); title.textContent = label;
    const result = document.createElement('strong'); result.textContent = value;
    const note = document.createElement('p'); note.textContent = detail;
    card.append(title, result, note); return card;
  }));
}
$('trial-form').addEventListener('submit', async event => {
  event.preventDefault();
  if (busy || stockEnabled || !$('trial-form').reportValidity()) return;
  const actions = Number($('trial-actions').value), seed = Number($('trial-seed').value);
  if (!Number.isSafeInteger(actions) || !Number.isSafeInteger(seed)) return;
  busy = true; controls();
  try { await request('simulation/trial', 'POST', { actions, seed }); await refresh(); $('error').textContent = ''; }
  catch (error) { $('error').textContent = error.message; }
  finally { busy = false; controls(); }
});
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
