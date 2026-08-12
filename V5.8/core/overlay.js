if (typeof ZONE_OVERLAY_ID === 'undefined') {

var ZONE_OVERLAY_ID = 'tt-ffu-zone-overlay';
var FRAME_ID        = 'tt-ffu-encoder-frame';

var encoderFrame           = null;
var frameReady             = false;
var pendingEncodeResolvers = null;
var busy                   = false;

var ZONE_SELS = [
  '[data-e2e="select-video-container"]',
  '.before-upload-new-stage',
  '[class*="before-upload"]',
  '[class*="upload-card"]',
  '[class*="uploadCard"]',
  '[class*="UploadCard"]',
  '[class*="drag"]',
  '[class*="Drag"]',
  '[class*="drop"]',
  '[class*="Drop"]',
  'main',
];

function findZone() {
  for (const sel of ZONE_SELS) {
    const el = document.querySelector(sel);
    if (el && el.offsetParent !== null) return el;
  }
  return null;
}

function ensureZoneOverlayStyle() {
  let s = document.getElementById('tt-ffu-zone-overlay-style');
  if (!s) {
    s = document.createElement('style');
    s.id = 'tt-ffu-zone-overlay-style';
    (document.head || document.documentElement).appendChild(s);
  }
  s.textContent = `
    #${ZONE_OVERLAY_ID} {
      --white:     #f5f5f5;
      --silver:    #b8bec8;
      --dim:       #5a6070;
      --cyan:      #00E65B;
      --red:       #FE2C55;
      --green:     #00E65B;

      position: absolute; inset: 0; z-index: 999999;
      display: flex; align-items: center; justify-content: center;
      background: radial-gradient(circle at 50% 40%, rgba(28, 28, 32, 0.85) 0%, rgba(5, 5, 6, 0.92) 100%);
      border-radius: inherit;
      font: 14px/1.5 -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
      animation: ffu-scrim-in .2s ease;
    }
    @keyframes ffu-scrim-in { from { opacity: 0; } to { opacity: 1; } }

    #${ZONE_OVERLAY_ID} .ffu-card {
      display: flex; flex-direction: column; align-items: center; justify-content: center;
      gap: 18px;
      position: relative;
      animation: ffu-card-in .34s cubic-bezier(.34, 1.56, .64, 1);
    }
    @keyframes ffu-card-in {
      from { opacity: 0; transform: translateY(10px) scale(.92); }
      to   { opacity: 1; transform: translateY(0) scale(1); }
    }

    #${ZONE_OVERLAY_ID} .ffu-ring-wrap {
      position: relative; width: 80px; height: 80px;
      display: flex; align-items: center; justify-content: center;
    }
    #${ZONE_OVERLAY_ID} .ffu-neon-spinner {
      width: 64px; height: 64px;
      border-radius: 50%;
      border: 4px solid rgba(255,255,255,.08);
      border-top-color: var(--cyan);
      border-bottom-color: #00E65B;
      box-shadow: 0 0 20px var(--cyan);
      animation: ffu-neon-spin 1s linear infinite;
    }
    @keyframes ffu-neon-spin { to { transform: rotate(360deg); } }

    #${ZONE_OVERLAY_ID} .ffu-pct {
      position: absolute; inset: 0; display: flex; align-items: center; justify-content: center;
      font-weight: 600; font-size: 15px; letter-spacing: 0; color: var(--white);
      font-variant-numeric: tabular-nums;
      text-shadow: 0 0 0px rgba(37, 244, 238, 0);
    }
    #${ZONE_OVERLAY_ID} .ffu-pct.ffu-pop {
      animation: ffu-pct-fade-blink .4s ease-out;
    }
    @keyframes ffu-pct-fade-blink {
      0%   { opacity: 1; }
      50%  { opacity: 0.7; }
      100% { opacity: 1; }
    }

    #${ZONE_OVERLAY_ID} .ffu-status-row {
      display: flex; align-items: center; justify-content: center; gap: 7px;
    }
    #${ZONE_OVERLAY_ID} .ffu-dot {
      width: 6px; height: 6px; border-radius: 50%; flex-shrink: 0;
      background: var(--cyan);
      animation: ffu-dot-blink 1.3s ease-in-out infinite;
    }
    @keyframes ffu-dot-blink {
      0%, 100% { opacity: 1;  transform: scale(1); }
      50%      { opacity: .3; transform: scale(.7); }
    }

    #${ZONE_OVERLAY_ID} .ffu-status-text {
      font-size: .68rem; font-weight: 700; color: var(--silver); text-align: center;
      letter-spacing: 1.6px; text-transform: uppercase; max-width: 260px;
      transition: color .3s ease;
    }
    #${ZONE_OVERLAY_ID} .ffu-status-text.error   { color: var(--red); }
    #${ZONE_OVERLAY_ID} .ffu-status-text.success { color: var(--green); }

    #${ZONE_OVERLAY_ID} .ffu-icon-wrap { position: relative; width: 56px; height: 56px; }
    #${ZONE_OVERLAY_ID} .ffu-icon-circle { width: 56px; height: 56px; }
    #${ZONE_OVERLAY_ID} .ffu-icon-circle-bg {
      fill: none; stroke-width: 3;
      stroke-dasharray: 151; stroke-dashoffset: 151;
      animation: ffu-draw-circle .35s cubic-bezier(.65, 0, .35, 1) forwards;
    }
    #${ZONE_OVERLAY_ID} .ffu-icon-wrap.success .ffu-icon-circle-bg { stroke: var(--green); opacity: .35; }
    #${ZONE_OVERLAY_ID} .ffu-icon-wrap.error   .ffu-icon-circle-bg { stroke: var(--red);   opacity: .35; }
    @keyframes ffu-draw-circle { to { stroke-dashoffset: 0; } }

    #${ZONE_OVERLAY_ID} .ffu-icon-check,
    #${ZONE_OVERLAY_ID} .ffu-icon-cross {
      fill: none; stroke-width: 3.5; stroke-linecap: round; stroke-linejoin: round;
      stroke-dasharray: 40; stroke-dashoffset: 40;
      animation: ffu-draw-icon .35s .18s cubic-bezier(.65, 0, .35, 1) forwards;
    }
    #${ZONE_OVERLAY_ID} .ffu-icon-cross { stroke-dasharray: 58; stroke-dashoffset: 58; }
    @keyframes ffu-draw-icon { to { stroke-dashoffset: 0; } }
    #${ZONE_OVERLAY_ID} .ffu-icon-check { stroke: var(--green); }
    #${ZONE_OVERLAY_ID} .ffu-icon-cross { stroke: var(--red); }

    #${FRAME_ID} { position: fixed; width: 0; height: 0; border: 0; opacity: 0; pointer-events: none; }
  `;
}

function getOrCreateZoneOverlay() {
  const zone = findZone();
  if (!zone) return null;

  ensureZoneOverlayStyle();

  const computedPos = window.getComputedStyle(zone).position;
  if (computedPos === 'static') zone.style.position = 'relative';

  let overlay = document.getElementById(ZONE_OVERLAY_ID);
  if (!overlay || overlay.parentElement !== zone) {
    if (overlay) overlay.remove();
    overlay = document.createElement('div');
    overlay.id = ZONE_OVERLAY_ID;
    zone.appendChild(overlay);
  }
  return overlay;
}

function renderZoneOverlay(mode, { pct = 0, text = '', autoHideMs = 0 } = {}) {
  const overlay = getOrCreateZoneOverlay();
  if (!overlay) return;

  const prevMode = overlay.dataset.ffuMode;

  if (mode === 'progress') {
    if (prevMode === 'progress') {
      const pctEl = overlay.querySelector('.ffu-pct');
      const statusEl = overlay.querySelector('.ffu-status-text');
      if (pctEl) {
        pctEl.textContent = `${Math.round(pct)}%`;
        pctEl.classList.remove('ffu-pop');
        void pctEl.offsetWidth;
        pctEl.classList.add('ffu-pop');
      }
      if (statusEl) statusEl.textContent = text || 'Encoding video';
    } else {
      overlay.innerHTML = `
        <div class="ffu-card">
          <div class="ffu-ring-wrap">
            <div class="ffu-neon-spinner"></div>
            <div class="ffu-pct">${Math.round(pct)}%</div>
          </div>
          <div class="ffu-status-row">
            <span class="ffu-dot"></span>
            <div class="ffu-status-text">${text || 'Encoding video'}</div>
          </div>
        </div>
      `;
      overlay.dataset.ffuMode = 'progress';
    }
  } else if (mode === 'indeterminate') {
    if (prevMode === 'indeterminate') {
      const statusEl = overlay.querySelector('.ffu-status-text');
      if (statusEl && text) statusEl.textContent = text;
    } else {
      overlay.innerHTML = `
        <div class="ffu-card">
          <div class="ffu-ring-wrap">
            <div class="ffu-neon-spinner"></div>
          </div>
          <div class="ffu-status-row">
            <span class="ffu-dot"></span>
            <div class="ffu-status-text">${text || 'Processing'}</div>
          </div>
        </div>
      `;
      overlay.dataset.ffuMode = 'indeterminate';
    }
  } else if (mode === 'success') {
    overlay.innerHTML = `
      <div class="ffu-card">
        <div class="ffu-icon-wrap success">
          <svg class="ffu-icon-circle" viewBox="0 0 52 52">
            <circle class="ffu-icon-circle-bg" cx="26" cy="26" r="24"></circle>
            <path class="ffu-icon-check" d="M14 27l7 7 17-17"></path>
          </svg>
        </div>
        <div class="ffu-status-text success">${text || 'Complete'}</div>
      </div>
    `;
    overlay.dataset.ffuMode = 'success';
  } else if (mode === 'error') {
    overlay.innerHTML = `
      <div class="ffu-card">
        <div class="ffu-icon-wrap error">
          <svg class="ffu-icon-circle" viewBox="0 0 52 52">
            <circle class="ffu-icon-circle-bg" cx="26" cy="26" r="24"></circle>
            <path class="ffu-icon-cross" d="M18 18l16 16M34 18l-16 16"></path>
          </svg>
        </div>
        <div class="ffu-status-text error">${text || 'Failed'}</div>
      </div>
    `;
    overlay.dataset.ffuMode = 'error';
  }

  if (autoHideMs) {
    setTimeout(() => removeZoneOverlay(), autoHideMs);
  }
}

function removeZoneOverlay() {
  const el = document.getElementById(ZONE_OVERLAY_ID);
  if (el) el.remove();
}

}