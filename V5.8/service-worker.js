const UPDATE_CACHE_KEY = atob('dXBkYXRlLmNhY2hl');
const UPDATE_CONFIG_URL = atob('aHR0cHM6Ly91cC5zZW56ZXluZmZ4LndvcmtlcnMuZGV2L3ZlcnNpb24uanNvbg==');
const CURRENT_VERSION = chrome.runtime.getManifest().version;

function _decryptConfig(enc) {
  const key = atob('dHJ1ZQ==');
  return JSON.parse(
    enc.map((code, i) =>
      String.fromCharCode(code ^ key.charCodeAt(i % key.length))
    ).join('')
  );
}

function _semverLt(a, b) {
  const pa = a.split('.').map(Number);
  const pb = b.split('.').map(Number);
  for (let i = 0; i < 3; i++) {
    if ((pa[i] || 0) < (pb[i] || 0)) return true;
    if ((pa[i] || 0) > (pb[i] || 0)) return false;
  }
  return false;
}

function _isBlocked(cfg) {
  if (!cfg) return false;
  if (cfg.update_required === true) return true;
  if (Array.isArray(cfg.blacklisted_versions) && cfg.blacklisted_versions.includes(CURRENT_VERSION)) return true;
  if (cfg.min_version && _semverLt(CURRENT_VERSION, cfg.min_version)) return true;
  return false;
}

function broadcastToAll(msg) {
  chrome.runtime.sendMessage(msg).catch(() => {});
  chrome.tabs.query({}, (tabs) => {
    for (const tab of tabs) {
      if (!tab.id) continue;
      chrome.tabs.sendMessage(tab.id, msg).catch(() => {});
    }
  });
}

function broadcastToPopup(msg) {
  chrome.runtime.sendMessage(msg).catch(() => {});
}

async function injectBlockedToAllTabs() {
  const tabs = await chrome.tabs.query({});
  for (const tab of tabs) {
    if (!tab.id || !tab.url || tab.url.startsWith('chrome://') || tab.url.startsWith('chrome-extension://')) continue;
    chrome.scripting.executeScript({
      target: { tabId: tab.id },
      world: 'MAIN',
      func: () => {
        window.__SENZEYN_BLOCKED__ = true;
        window.dispatchEvent(new CustomEvent('senzeyn:blocked'));
      },
    }).catch(() => {});
  }
}

let _swLastBlocked = null;

async function _swPollUpdate() {
  try {
    const res = await fetch(UPDATE_CONFIG_URL + '?t=' + Date.now(), {
      cache: 'no-cache',
      credentials: 'omit',
    });
    if (!res.ok) return;

    const enc = await res.json();
    const cfg = _decryptConfig(enc);
    const blocked  = _isBlocked(cfg);
    const enriched = { ...cfg, blocked, current_version: CURRENT_VERSION };

    chrome.storage.local.set({ 'update.result': { ts: Date.now(), data: enriched } });

    if (blocked !== _swLastBlocked) {
      _swLastBlocked = blocked;
      broadcastToAll({ type: 'UPDATE_STATUS', blocked, cfg: blocked ? enriched : null });
      if (blocked) await injectBlockedToAllTabs();
    }
  } catch (_) {}
}

chrome.alarms.create('szn-update-poll', { periodInMinutes: 10 });

chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === 'szn-update-poll') _swPollUpdate();
});

chrome.runtime.onInstalled.addListener(async ({ reason }) => {
  if (reason === 'install' || reason === 'update') {
    await _swPollUpdate();
  }
});

chrome.runtime.onStartup.addListener(() => {
  _swPollUpdate();
});

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message.type === 'UPDATE_CACHE_GET') {
    chrome.storage.local.get(UPDATE_CACHE_KEY, (res) => { sendResponse(res[UPDATE_CACHE_KEY] ?? null); }); return true;
  }
  if (message.type === 'UPDATE_CACHE_SET') {
    chrome.storage.local.set({ [UPDATE_CACHE_KEY]: { ts: Date.now(), data: message.data } }); return false;
  }
  if (message.type === 'GET_TAB_ID') {
    sendResponse({ tabId: sender.tab?.id }); return true;
  }
  if (message.type === 'INJECT_MAIN_WORLD') {
    chrome.scripting.executeScript({
      target: { tabId: message.tabId }, world: 'MAIN', func: (code) => { eval(code); }, args: [message.code],
    }).then(() => { sendResponse({ ok: true }); }).catch(err => { sendResponse({ ok: false, error: err.message }); }); return true;
  }
  if (message.type === 'RUN_V7_PATCH') {
    const tabId = sender.tab?.id;
    if (!tabId) { sendResponse({ ok: false, error: 'Tab ID tidak tersedia' }); return true; }
    const { objectUrl, fileName, fileType } = message;

    chrome.scripting.executeScript({
      target: { tabId, allFrames: false },
      world: 'MAIN',
      func: async (objectUrl, fileName, fileType) => {
        const POLL_MS  = 100; const MAX_WAIT = 15_000; let waited = 0;
        while (typeof V7Patch !== 'function') {
          if (waited >= MAX_WAIT) return { ok: false, error: 'V7Patch timeout' };
          await new Promise(r => setTimeout(r, POLL_MS)); waited += POLL_MS;
        }
        try {
          const resp = await fetch(objectUrl);
          if (!resp.ok) throw new Error('Fetch gagal');
          const blob = await resp.blob();
          const file = new File([blob], fileName, { type: fileType });
          const patchResult = await V7Patch(file);
          let patchedBlob;
          if (patchResult instanceof File || patchResult instanceof Blob) patchedBlob = patchResult;
          else if (patchResult instanceof ArrayBuffer || ArrayBuffer.isView(patchResult)) patchedBlob = new Blob([patchResult], { type: fileType });
          else patchedBlob = blob;
          const patchedFile = new File([patchedBlob], fileName, { type: fileType });
          const sels = ['input[type="file"][accept="video/*"]', 'input[type="file"][accept*="video"]', '[class*="hiddenInput"] input[type="file"]', 'input[type="file"]'];
          let input = null;
          for (const sel of sels) { const el = document.querySelector(sel); if (el) { input = el; break; } }
          if (!input) return { ok: false, error: 'input[type=file] tidak ada' };
          const dt = new DataTransfer(); dt.items.add(patchedFile);
          const nativeSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'files');
          if (nativeSetter?.set) { try { nativeSetter.set.call(input, dt.files); } catch (_) {} }
          else { try { Object.defineProperty(input, 'files', { value: dt.files, configurable: true }); } catch (_) {} }
          input.dispatchEvent(new Event('input',  { bubbles: true, cancelable: true }));
          input.dispatchEvent(new Event('change', { bubbles: true, cancelable: true }));
          return { ok: true };
        } catch (err) { return { ok: false, error: err.message }; }
      },
      args: [objectUrl, fileName, fileType],
    }).then((results) => {
      const res = results?.[0]?.result;
      if (res) sendResponse(res); else sendResponse({ ok: false, error: 'executeScript no result' });
    }).catch((err) => { sendResponse({ ok: false, error: err.message }); });
    return true;
  }
  if (message.type === 'PAGE_STATUS') { broadcastToPopup({ type: 'PAGE_STATUS', injected: message.injected }); sendResponse({ ok: true }); return true; }
  if (message.type === 'INJECT_SCRIPT' && message.code && sender.tab?.id) {
    chrome.scripting.executeScript({ target: { tabId: sender.tab.id, allFrames: false }, world: 'MAIN', func: (code) => { try { (new Function(code))(); } catch (e) {} }, args: [message.code], })
    .then(() => { sendResponse({ ok: true }); }).catch((err) => { sendResponse({ ok: false, error: err.message }); }); return true;
  }
});