(function initIsland() {
      const island = document.getElementById('diIsland');
      if (!island) return;

      let collapseTimer = null;
      let currentPhase  = 'hidden';

      island.classList.remove('phase-compact', 'phase-expanded', 'di-enter');

      function setPhase(phase) {
        island.classList.remove('phase-compact', 'phase-expanded');
        if (phase) island.classList.add('phase-' + phase);
        currentPhase = phase;
      }

      function expandIsland(opts = {}) {
        clearTimeout(collapseTimer);

        if (currentPhase !== 'compact' && currentPhase !== 'expanded') return;

        setPhase('expanded');

        if (!opts.noAutoCollapse) {
          collapseTimer = setTimeout(
            () => setPhase('compact'),
            opts.holdMs ?? 2400
          );
        }
      }

      requestAnimationFrame(() => {
        setPhase('compact');

        requestAnimationFrame(() => {

          setTimeout(() => expandIsland({ holdMs: 4500 }), 800);
        });
      });

      island.addEventListener('mouseenter', () => {
        clearTimeout(collapseTimer);
        if (currentPhase === 'compact') {
          expandIsland({ noAutoCollapse: true });
        }
      });

      island.addEventListener('mouseleave', () => {
        if (currentPhase === 'expanded') {
          collapseTimer = setTimeout(() => setPhase('compact'), 300);
        }
      });

      window._diSetPhase  = setPhase;
      window._diExpand    = expandIsland;
    })();

document.addEventListener('DOMContentLoaded', () => {
  const sequence = [
    { el: document.getElementById('animDivider'),      delay: 340 },
    { el: document.getElementById('mainCard'),         delay: 420 },
    { el: document.getElementById('customCard2'),      delay: 500 },
    { el: document.getElementById('extraLinkCard'),    delay: 538 },
    { el: document.getElementById('fpsConverterCard'), delay: 575 },
    { el: document.getElementById('moreInfoDivider'),  delay: 615 },
    { el: document.getElementById('injectBadge'),      delay: 650 },
  ];

  sequence.forEach(({ el, delay }) => {
    if (!el) return;
    setTimeout(() => el.classList.add('visible'), delay);
  });

  document.querySelectorAll('.social-btn').forEach((btn, i) => {
    setTimeout(() => btn.classList.add('visible'), 660 + i * 85);
  });

  const toggle = document.getElementById('customToggle2');
  const card   = document.getElementById('customCard2');
  toggle.addEventListener('change', function () {
    card.classList.toggle('watermark-on', this.checked);
  });
});