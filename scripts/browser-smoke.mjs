// Optional smoke test using a locally launched Chrome DevTools endpoint; no npm dependency.
import { writeFile } from 'node:fs/promises';

const appUrl = process.argv[2] || 'http://localhost:8080';
const debugUrl = process.argv[3] || 'http://localhost:19222';
const targets = await (await fetch(debugUrl + '/json/list')).json();
const target = targets.find(t => t.type === 'page');
if (!target) throw new Error('No Chrome page target found');
const socket = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((resolve, reject) => { socket.addEventListener('open', resolve, { once: true }); socket.addEventListener('error', reject, { once: true }); });
let sequence = 0;
const pending = new Map();
const errors = [];
socket.addEventListener('message', event => {
  const message = JSON.parse(event.data);
  if (message.method === 'Runtime.exceptionThrown') errors.push(message.params.exceptionDetails.text);
  const entry = pending.get(message.id);
  if (entry) {
    pending.delete(message.id); clearTimeout(entry.timer);
    if (message.error) entry.reject(new Error(message.error.message)); else entry.resolve(message.result);
  }
});
function call(method, params = {}) {
  return new Promise((resolve, reject) => {
    const id = ++sequence;
    const timer = setTimeout(() => { pending.delete(id); reject(new Error('CDP timeout: ' + method)); }, 45_000);
    pending.set(id, { resolve, reject, timer }); socket.send(JSON.stringify({ id, method, params }));
  });
}
async function evaluate(expression) {
  const response = await call('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
  if (response.exceptionDetails) throw new Error(response.exceptionDetails.text);
  return response.result.value;
}
async function waitForState(state, action) {
  await evaluate(`(async () => {
    const until = Date.now() + 30000;
    while (Date.now() < until) {
      if (document.getElementById('state')?.textContent === ${JSON.stringify(state)} && !document.querySelector('[data-action="${action}"]').disabled) return true;
      await new Promise(resolve => setTimeout(resolve, 100));
    }
    throw new Error('UI state did not become ${state}');
  })()`);
}

try {
  await call('Runtime.enable');
  await call('Page.enable');
  await call('Emulation.setDeviceMetricsOverride', { width: 1280, height: 1200, deviceScaleFactor: 1, mobile: false });
  await call('Page.navigate', { url: appUrl });
  await waitForState('STOPPED', 'start');
  for (const [action, state, next] of [['start', 'RUNNING', 'pause'], ['pause', 'PAUSED', 'resume'], ['resume', 'RUNNING', 'stop'], ['stop', 'STOPPED', 'reset'], ['reset', 'STOPPED', 'start']]) {
    await evaluate(`document.querySelector('[data-action="${action}"]').click()`);
    await waitForState(state, next);
  }
  const snapshot = await evaluate(`({ state: document.getElementById('state').textContent,
    error: document.getElementById('error').textContent,
    scores: document.getElementById('scores').textContent,
    configuration: document.getElementById('configuration').textContent,
    canvasWidth: document.getElementById('board').width })`);
  if (snapshot.error || errors.length || !snapshot.scores.includes('Player 1')) throw new Error(JSON.stringify({ snapshot, errors }));
  const trial = await evaluate(`(async () => {
    if (document.querySelectorAll('.metric-card').length !== 7) throw new Error('Expected seven performance metrics');
    document.getElementById('trial-actions').value = '10003';
    document.getElementById('trial-seed').value = '42';
    document.getElementById('trial-form').requestSubmit();
    const until = Date.now() + 30000;
    while (Date.now() < until) {
      if (document.getElementById('trial-state').textContent.includes('Terminé') && !document.getElementById('trial-start').disabled) break;
      await new Promise(resolve => setTimeout(resolve, 100));
    }
    const data = await (await fetch('/api/performance')).json();
    if (!data.engine.completed || data.engine.measurements.actions !== 10003) throw new Error('Fixed workload did not finish at N actions');
    if (!document.querySelector('[data-action="resume"]').disabled) throw new Error('Completed trial must not resume');
    const text = document.getElementById('metrics').textContent;
    if (text.includes('NaN') || text.includes('undefined') || text.includes('Infinity')) throw new Error('Invalid metric value');
    return { label: document.getElementById('trial-state').textContent, metrics: text, samples: data.http.reads.samples };
  })()`);
  const screenshot = await call('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true });
  await writeFile('target/metrics-desktop.png', Buffer.from(screenshot.data, 'base64'));
  await call('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
  const overflow = await evaluate('document.documentElement.scrollWidth > window.innerWidth');
  if (overflow) throw new Error('Mobile layout overflows horizontally');
  const mobile = await call('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true });
  await writeFile('target/metrics-mobile.png', Buffer.from(mobile.data, 'base64'));
  console.log(JSON.stringify({ ...snapshot, trial, browserErrors: errors, desktopScreenshot: 'target/metrics-desktop.png', mobileScreenshot: 'target/metrics-mobile.png' }));
} finally {
  socket.close();
}
