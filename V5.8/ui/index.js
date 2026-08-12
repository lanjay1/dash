const STORAGE_KEY = 'senzeyn_fps_enabled';
const g = id => document.getElementById(id);

let _updateBlocked = false;

(function _initBlockGuard() {
  function _applyBlock(cfg) {
    _updateBlocked = true;
    if (document.body) showUpdateOverlay(cfg);
    else document.addEventListener('DOMContentLoaded', () => showUpdateOverlay(cfg));
  }

  chrome.storage.local.get('update.result', (res) => {
    const cfg = res['update.result']?.data ?? null;
    if (cfg?.blocked === true) _applyBlock(cfg);
  });

  chrome.storage.onChanged.addListener((changes, area) => {
    if (area !== 'local' || !changes['update.result']) return;
    const cfg = changes['update.result'].newValue?.data ?? null;
    if (cfg?.blocked === true && !_updateBlocked) {
      _applyBlock(cfg);
    } else if (cfg?.blocked === false && _updateBlocked) {
      _updateBlocked = false;
      removeUpdateOverlay();
    }
  });
})();

function showUpdateOverlay(cfg) {
  if (document.getElementById('szn-update-overlay')) return;

  const updateUrl = cfg?.update_url;
  const message   = cfg?.update_message;
  const info      = cfg?.info_message;
  const toggle    = cfg?.toggle_message;

  const overlay = document.createElement('div');
  overlay.id = 'szn-update-overlay';
  overlay.innerHTML = `
    <div class="upd-card">

      <div class="upd-icon-wrap">
        <svg width="26" height="26" viewBox="0 0 24 24" fill="none"
             stroke="currentColor" stroke-width="1.7"
             stroke-linecap="round" stroke-linejoin="round">
          <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
          <polyline points="17 8 12 3 7 8"/>
          <line x1="12" y1="3" x2="12" y2="15"/>
        </svg>
      </div>

      <div class="upd-badge">${info}</div>
      <p class="upd-msg">${message}</p>
      <div class="upd-ver">
      <a href="${updateUrl}" target="_blank" class="upd-btn">
        <svg width="13" height="13" viewBox="0 0 24 24" fill="none"
             stroke="currentColor" stroke-width="2.2"
             stroke-linecap="round" stroke-linejoin="round">
          <path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/>
          <polyline points="15 3 21 3 21 9"/>
          <line x1="10" y1="14" x2="21" y2="3"/>
        </svg>
        ${toggle}
      </a>
    </div>
  `;

  document.body.appendChild(overlay);
  requestAnimationFrame(() => {
    requestAnimationFrame(() => overlay.classList.add('upd-visible'));
  });
}

function removeUpdateOverlay() {
  const el = document.getElementById('szn-update-overlay');
  if (el) el.remove();
}

(function injectUpdateStyles() {
  if (document.getElementById('szn-update-style')) return;
  const s = document.createElement('style');
  s.id = 'szn-update-style';
  s.textContent = `
    #szn-update-overlay {
      position: fixed; inset: 0; z-index: 99999;
      display: flex; align-items: center; justify-content: center;
      background: rgba(10, 10, 11, 0.0);
      backdrop-filter: blur(0px);
      -webkit-backdrop-filter: blur(0px);
      transition: background 0.4s ease, backdrop-filter 0.4s ease;
    }
    #szn-update-overlay.upd-visible {
      background: rgba(10, 10, 11, 0.96);
      backdrop-filter: blur(12px);
      -webkit-backdrop-filter: blur(12px);
    }
    .upd-card {
      position: relative;
      background: #111214;
      border: 1px solid rgba(37,244,238,0.15);
      border-radius: 20px;
      padding: 28px 24px 22px;
      width: 252px;
      text-align: center;
      display: flex; flex-direction: column; align-items: center; gap: 10px;
      transform: translateY(12px) scale(0.96);
      opacity: 0;
      transition: transform 0.42s cubic-bezier(0.34,1.3,0.64,1), opacity 0.32s ease;
      overflow: hidden;
    }
    #szn-update-overlay.upd-visible .upd-card {
      transform: translateY(0) scale(1);
      opacity: 1;
    }
    .upd-icon-wrap {
      width: 52px; height: 52px; border-radius: 14px;
      background: rgba(37,244,238,0.07);
      border: 1px solid rgba(37,244,238,0.18);
      display: flex; align-items: center; justify-content: center;
      color: #25F4EE;
    }
    .upd-badge {
      font-size: 9.5px; font-weight: 700; letter-spacing: 1px;
      color: #25F4EE;
      background: rgba(37,244,238,0.08);
      border: 1px solid rgba(37,244,238,0.2);
      border-radius: 6px; padding: 4px 10px;
    }
    .upd-title {
      font-size: 15px; font-weight: 700;
      color: #f5f5f5; margin: 0;
    }
    .upd-msg {
      font-size: 11.5px; color: rgba(255,255,255,0.45);
      line-height: 1.65; margin: 0;
    }
    .upd-ver {
      display: flex; align-items: center; gap: 8px;
      margin: 2px 0;
    }
    .upd-ver-current {
      font-size: 10.5px; font-weight: 600;
      color: rgba(255,255,255,0.3);
      background: rgba(255,255,255,0.05);
      border-radius: 5px; padding: 3px 8px;
      text-decoration: line-through;
    }
    .upd-ver-new {
      font-size: 10.5px; font-weight: 700;
      color: #25F4EE;
      background: rgba(37,244,238,0.08);
      border-radius: 5px; padding: 3px 8px;
    }
    .upd-btn {
      display: inline-flex; align-items: center; gap: 7px;
      margin-top: 4px;
      background: #25F4EE;
      color: #0a0a0b;
      font-size: 12px; font-weight: 700;
      border-radius: 10px; padding: 9px 20px;
      text-decoration: none;
      transition: opacity 0.2s;
    }
    .upd-btn:hover { opacity: 0.88; }
    .upd-note {
      font-size: 9.5px; color: rgba(255,255,255,0.2); margin: 0;
    }
  `;
  document.head.appendChild(s);
})();

function renderTitle() {
  const word = 'Senzeyn X Tiktok';
  const el = document.getElementById('h1Top');
  if (!el) return;
  el.innerHTML = '';
  word.split('').forEach((ch, i) => {
    const span = document.createElement('span');
    span.className = 'h1-char';
    span.textContent = ch;
    if (ch === 'X') span.style.margin = '0 5px';
    span.style.animationDelay = renderTitle._first ? (0.18 + i * 0.07) + 's' : '0s';
    el.appendChild(span);
  });
  renderTitle._first = false;
}
renderTitle._first = true;
renderTitle();



document.addEventListener('DOMContentLoaded', () => {
  const featToggle = g('featToggle');
  const featBody   = g('featBody');
  if (featToggle && featBody) {
    featToggle.addEventListener('click', () => {
      const open = featBody.classList.toggle('open');
      featToggle.classList.toggle('open', open);
    });
  }
});

let isOnTargetPage = false;

function setStatusUI(onTarget) {
  if (typeof window._diExpand === 'function') {
    window._diExpand({ holdMs: 2200 });
  }

  if (onTarget) {
    setInjectBadge('active');
  } else {
    setInjectBadge('standby');
  }
}

function setInjectBadge(state) {
  const label         = g('injectText');
  const expandedTitle = g('diExpandedTitle');
  const expandedSub   = g('diExpandedSub');
  const island        = g('diIsland');

  if (island) island.classList.remove('injected', 'standby');

  if (state === 'active') {
    if (label)         label.textContent = 'Active';
    if (expandedTitle) expandedTitle.textContent = 'Bypass Active';
    if (expandedSub)   expandedSub.textContent   = 'Ready to Upload ↗';
    if (island)        island.classList.add('injected');

  } else if (state === 'standby') {
    if (label)         label.textContent = 'Standby';
    if (expandedTitle) expandedTitle.textContent = 'Standby Mode';
    if (expandedSub)   expandedSub.textContent   = 'Open TikTok Upload Page ↗';
    if (island)        island.classList.add('standby');
  }
}

function getActiveTab() {
  return new Promise(resolve => {
    chrome.tabs.query({ active: true, currentWindow: true }, tabs => resolve(tabs[0] || null));
  });
}

let _lastClick = 0;
document.addEventListener('click', (e) => {
  const now = Date.now();
  const throttled = now - _lastClick < 80;
  _lastClick = now;

  const wave = document.createElement('div');
  wave.className = 'shockwave';
  wave.style.left = e.clientX + 'px';
  wave.style.top  = e.clientY + 'px';
  document.body.appendChild(wave);
  setTimeout(() => wave.remove(), 500);

  if (throttled) return;
  const count = 3;
  for (let i = 0; i < count; i++) {
    const spark = document.createElement('div');
    spark.className = 'spark-particle';
    spark.style.left = e.clientX + 'px';
    spark.style.top  = e.clientY + 'px';
    document.body.appendChild(spark);
    const angle = Math.random() * Math.PI * 2;
    const vel   = Math.random() * 40 + 15;
    spark.animate([
      { transform: 'translate(0,0) scale(1)', opacity: 1 },
      { transform: `translate(${Math.cos(angle)*vel}px,${Math.sin(angle)*vel}px) scale(0)`, opacity: 0 }
    ], { duration: 360 + Math.random()*140, easing: 'cubic-bezier(0,.9,.57,1)' })
      .onfinish = () => spark.remove();
  }
});

document.addEventListener('DOMContentLoaded', async () => {

  document.querySelectorAll('.header, .card, .footer').forEach((el, i) => {
    el.style.animationDelay = (0.15 + i * 0.09) + 's';
    el.classList.add('slide-in-anim');
  });

  chrome.storage.local.set({ [STORAGE_KEY]: true });

  const tab = await getActiveTab();
  if (tab) {
    isOnTargetPage = !!(tab.url && (tab.url.includes('/upload') || tab.url.includes('/creator-center')));
    setInjectBadge(isOnTargetPage ? 'active' : 'standby');

    if (isOnTargetPage) {
      chrome.scripting.executeScript({
        target: { tabId: tab.id },
        world: 'MAIN',
        func: () => { if (window.onBypass) window.onBypass(); }
      }).catch(() => {});
    }
  }

  setStatusUI(isOnTargetPage);

  const wmToggle = g('customToggle2');
  const wmSub    = g('customCard2Sub');

  if (wmToggle) {
    const wmResult = await chrome.storage.local.get(['wm_enabled']);
    const wmOn = wmResult['wm_enabled'] !== false;
    wmToggle.checked = wmOn;
    g('customCard2')?.classList.toggle('watermark-on', wmOn);
    if (wmSub) wmSub.textContent = wmOn ? 'Enabled' : 'Disabled';

    wmToggle.addEventListener('change', async () => {
      const next = wmToggle.checked;
      chrome.storage.local.set({ 'wm_enabled': next });
      if (wmSub) wmSub.textContent = next ? 'Enabled' : 'Disabled';

      const tab = await getActiveTab();
      if (tab && tab.url && (tab.url.includes('/upload') || tab.url.includes('/creator-center'))) {
        chrome.tabs.sendMessage(tab.id, { type: 'WM_SET_ACTIVE', value: next }).catch(() => {});
      }
    });
  }
});

chrome.runtime.onMessage.addListener((message) => {
  if (message.type === 'PAGE_STATUS') {
    const injected = message.injected;
    isOnTargetPage = injected;
    setInjectBadge(injected ? 'active' : 'standby');
    setStatusUI(injected);
  }

  if (message.type === 'UPDATE_STATUS') {
    if (message.blocked && !_updateBlocked) {
      _updateBlocked = true;
      showUpdateOverlay(message.cfg);
    } else if (!message.blocked && _updateBlocked) {
      _updateBlocked = false;
      removeUpdateOverlay();
    }
  }
});