import { normalizeSettings } from './storage.mjs';
const $ = id => document.getElementById(id);
const saved = await chrome.storage.local.get('settings');
for (const key of ['url', 'name', 'accessToken']) $(key).value = saved.settings?.[key] || '';
function render(s) { if (!s) return; $('status').textContent = s.status; $('error').textContent = s.error || ''; $('backup').textContent = s.backupError ? `会话备份异常：${s.backupError}` : ''; }
render((await chrome.storage.session.get('status')).status);
chrome.storage.onChanged.addListener((changes, area) => { if (area === 'session' && changes.status) render(changes.status.newValue); });
$('settings').addEventListener('submit', async event => {
  event.preventDefault(); $('save').disabled = true; const timer = setTimeout(() => $('save').textContent = '正在连接…', 250);
  try {
    const settings = normalizeSettings(Object.fromEntries(['url', 'name', 'accessToken'].map(k => [k, $(k).value])));
    const result = await chrome.runtime.sendMessage({ type: 'save', settings }); if (!result.ok) throw new Error(result.error);
  } catch (e) { $('error').textContent = e.message; }
  finally { clearTimeout(timer); $('save').disabled = false; $('save').textContent = '保存并连接'; }
});
$('stop').addEventListener('click', () => chrome.runtime.sendMessage({ type: 'stop' }));
$('open').addEventListener('click', () => { try { const s = normalizeSettings({ url: $('url').value, name: $('name').value }); chrome.tabs.create({ url: s.url }); } catch (e) { $('error').textContent = e.message; } });
