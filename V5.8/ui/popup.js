(function () {

  let _statusText = 'SYSTEM READY';
  let _isActive   = false;

  function getEl(id) { return document.getElementById(id); }

  function applyState(badge) {
    if (!badge) return;
    badge.classList.toggle('is-active', _isActive);
    const label = badge.querySelector('.by-label');
    if (label) label.textContent = _statusText;
  }

  window._badge_update = function (status) {
    const badge = getEl('fps-badge');
    if (!badge) return;
    _isActive   = status === 'active';
    _statusText = _isActive ? 'SYSTEM ACTIVE' : 'SYSTEM OFF';
    badge.classList.remove('is-blocked');
    badge.dataset.status = status;
    applyState(badge);
  };

  window.setBadgeBlocked = function () {
    const badge = getEl('fps-badge');
    if (!badge) return;
    _isActive   = false;
    _statusText = 'UPDATE REQUIRED';
    badge.dataset.status = 'blocked';
    badge.classList.remove('is-active');
    badge.classList.add('is-blocked');
    applyState(badge);
  };

  window.showBadge = function () {
    const badge = getEl('fps-badge');
    if (!badge) return;
    applyState(badge);
    badge.style.display = '';
    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        badge.classList.add('by-visible');
      });
    });
  };

  window.hideBadge = function () {
    const badge = getEl('fps-badge');
    if (!badge) return;
    badge.classList.remove('by-visible');
  };

  window.setBadgeLanguage = function () {};

  window.addEventListener('senzeyn:blocked', function () {
    window.createBadge && window.createBadge();
    window.showBadge && window.showBadge();
    window.setBadgeBlocked && window.setBadgeBlocked();
  }, { once: true });

  window.createBadge = function () {
    if (getEl('fps-badge')) return;

    const style = document.createElement('style');
    style.id = 'senzeyn-badge-style';
    style.textContent = `
      @import url('https://fonts.googleapis.com/css2?family=Space+Grotesk:wght@500;600&display=swap');

      #fps-badge {
        position: fixed;
        top: 14px;
        left: 50%;
        transform: translateX(-50%) scale(0.75);
        z-index: 2147483647;
        font-family: 'Space Grotesk', sans-serif;
        user-select: none;
        pointer-events: auto;

        background: #000000;
        border: 1px solid rgba(0, 0, 0, 0);
        border-radius: 50px;
        height: 34px;
        padding: 0 16px;

        display: inline-flex;
        align-items: center;
        justify-content: center;

        opacity: 0;
        transition:
          opacity   0.4s ease,
          transform 0.4s cubic-bezier(0.34, 1.3, 0.64, 1);
      }

      #fps-badge.by-visible {
        opacity: 1;
        transform: translateX(-50%) scale(1);
      }

      #fps-badge.is-blocked .by-icon svg {
        fill: #fe2c55 !important;
        animation: by-pulse-red 1.2s ease-in-out infinite !important;
      }

      @keyframes by-pulse-red {
        0%, 100% { opacity: 1;    transform: scale(1);    }
        50%       { opacity: 0.5; transform: scale(0.88); }
      }

      #fps-badge.is-blocked .by-label {
        color: #fe2c55 !important;
      }

      #fps-badge .by-icon {
        flex-shrink: 0;
        display: flex;
        align-items: center;
        justify-content: center;
        width: 18px;
        height: 18px;
      }

      #fps-badge .by-icon svg {
        width: 15px;
        height: 15px;
        fill: rgba(255,255,255,0.5);
      }

      #fps-badge.is-active .by-icon svg {
        fill: #00E65B;
        animation: by-pulse 2.4s ease-in-out infinite;
      }

      @keyframes by-pulse {
        0%, 100% { opacity: 1;    transform: scale(1);    }
        50%       { opacity: 0.6; transform: scale(0.88); }
      }

      #fps-badge .by-label {
        overflow: hidden;
        white-space: nowrap;
        font-size: 11px;
        font-weight: 600;
        letter-spacing: 0.6px;
        text-transform: uppercase;
        color: rgba(255,255,255,0.65);
        max-width: 0;
        opacity: 0;
        margin-left: 0;
        transition:
          max-width   0.48s cubic-bezier(0.32, 0.72, 0, 1),
          opacity     0.32s ease 0.1s,
          margin-left 0.48s cubic-bezier(0.32, 0.72, 0, 1);
      }

      #fps-badge.by-visible .by-label {
        max-width: 160px;
        opacity: 1;
        margin-left: 8px;
      }

      #fps-badge.is-active .by-label { color: #00E65B; }
    `;
    document.head.appendChild(style);

    const badge = document.createElement('div');
    badge.id = 'fps-badge';
    badge.innerHTML = `
      <div class="by-icon">
        <svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
          <path d="M19.59 6.69a4.83 4.83 0 0 1-3.77-4.25V2h-3.45v13.67
                   a2.89 2.89 0 0 1-2.88 2.5 2.89 2.89 0 0 1-2.89-2.89
                   2.89 2.89 0 0 1 2.89-2.89c.28 0 .54.04.79.1V9.01
                   a6.33 6.33 0 0 0-.79-.05 6.34 6.34 0 0 0-6.34 6.34
                   6.34 6.34 0 0 0 6.34 6.34 6.34 6.34 0 0 0 6.33-6.34
                   V8.69a8.18 8.18 0 0 0 4.78 1.52V6.75a4.85 4.85 0 0 1-1.01-.06z"/>
        </svg>
      </div>
      <span class="by-label" id="by-status-text">SYSTEM READY</span>
    `;
    document.body.appendChild(badge);
  };

  if (document.body) {
    window.createBadge();
  } else {
    document.addEventListener('DOMContentLoaded', function () {
      window.createBadge();
    });
  }

})();