(() => {
    'use strict';
    const root = document.documentElement;
    const body = document.body;
    const sidebar = document.getElementById('workspace-navigation');
    const workspace = document.querySelector('.workspace-main');
    const quicknav = document.querySelector('.mobile-quicknav');
    const backdrop = document.querySelector('.sidebar-backdrop');
    const mobile = matchMedia('(max-width: 1024px)');
    let navReturnFocus;
    const focusable = element => [...element.querySelectorAll('a[href],button:not([disabled]),input:not([disabled]),select:not([disabled]),[tabindex="0"]')].filter(item => item.getClientRects().length);
    function setNavigation(open) {
        if (!sidebar) return;
        open = open && mobile.matches;
        body.dataset.navOpen = String(open);
        sidebar.inert = mobile.matches && !open;
        if (workspace) workspace.inert = open;
        if (quicknav) quicknav.inert = open;
        if (backdrop) backdrop.hidden = !open;
        document.querySelectorAll('[data-nav-open]').forEach(button => button.setAttribute('aria-expanded', String(open)));
        if (open) { navReturnFocus = document.activeElement; focusable(sidebar)[0]?.focus(); }
        else if (navReturnFocus && mobile.matches) { navReturnFocus.focus(); navReturnFocus = null; }
    }
    document.querySelectorAll('[data-nav-open]').forEach(button => button.addEventListener('click', () => setNavigation(true)));
    document.querySelectorAll('[data-nav-close]').forEach(button => button.addEventListener('click', () => setNavigation(false)));
    sidebar?.addEventListener('keydown', event => {
        if (body.dataset.navOpen !== 'true') return;
        if (event.key === 'Escape') { event.preventDefault(); setNavigation(false); }
        if (event.key === 'Tab') {
            const items = focusable(sidebar), first = items[0], last = items[items.length - 1];
            if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
            else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
        }
    });
    mobile.addEventListener('change', () => setNavigation(false));
    setNavigation(false);
    let toastTimer;
    function toast(message) {
        const target = document.querySelector('.ui-toast');
        if (!target) return;
        target.textContent = message; target.hidden = false;
        clearTimeout(toastTimer); toastTimer = setTimeout(() => { target.hidden = true; }, 3500);
    }
    function applyTheme(choice) {
        root.dataset.themeChoice = choice;
        root.dataset.theme = choice === 'system' ? (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light') : choice;
        try { localStorage.setItem('betna_admin_theme', choice); } catch { /* Appearance still works for this page. */ }
        document.querySelectorAll('[data-theme-toggle]').forEach(button => {
            button.setAttribute('aria-label', `Appearance: ${choice}. Change appearance`);
            button.title = `Appearance: ${choice}`;
        });
    }
    document.querySelectorAll('[data-theme-toggle]').forEach(button => button.addEventListener('click', () => {
        const choices = ['light', 'dark', 'system'], next = choices[(choices.indexOf(root.dataset.themeChoice || 'system') + 1) % choices.length];
        applyTheme(next); toast(`Appearance: ${next}`);
    }));
    applyTheme(root.dataset.themeChoice || 'system');
    matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => { if (root.dataset.themeChoice === 'system') applyTheme('system'); });
    document.querySelectorAll('[data-dismiss-notice]').forEach(button => button.addEventListener('click', () => button.closest('.notice')?.remove()));
    document.querySelectorAll('[data-password-toggle]').forEach(button => button.addEventListener('click', () => {
        const input = document.getElementById(button.dataset.passwordToggle);
        if (!input) return;
        const reveal = input.type === 'password'; input.type = reveal ? 'text' : 'password'; button.textContent = reveal ? 'Hide' : 'Show'; button.setAttribute('aria-pressed', String(reveal));
    }));
    const dialog = document.getElementById('page-search'), search = document.getElementById('page-search-input'), results = document.getElementById('page-search-results');
    const pages = [...document.querySelectorAll('.sidebar-nav [data-page-name]')];
    function renderPageSearch() {
        if (!results || !search) return;
        const query = search.value.trim().toLocaleLowerCase();
        results.replaceChildren();
        pages.filter(page => page.dataset.pageName.toLocaleLowerCase().includes(query)).forEach(page => {
            const link = document.createElement('a'); link.className = 'command-result'; link.href = page.href;
            const icon = page.querySelector('svg')?.cloneNode(true); if (icon) link.append(icon);
            const text = document.createElement('span'); text.textContent = page.dataset.pageName; link.append(text);
            results.append(link);
        });
        const empty = dialog.querySelector('.command-empty'); if (empty) empty.hidden = Boolean(results.childElementCount);
    }
    function openSearch() {
        if (!dialog || typeof dialog.showModal !== 'function') return;
        renderPageSearch(); dialog.showModal(); search?.focus();
    }
    document.querySelectorAll('[data-command-open]').forEach(button => button.addEventListener('click', openSearch));
    document.querySelectorAll('[data-command-close]').forEach(button => button.addEventListener('click', () => dialog?.close()));
    search?.addEventListener('input', renderPageSearch);
    search?.addEventListener('keydown', event => { if (event.key === 'ArrowDown') { event.preventDefault(); results?.querySelector('a')?.focus(); } });
    dialog?.addEventListener('click', event => { if (event.target === dialog) dialog.close(); });
    dialog?.addEventListener('keydown', event => {
        if (!['ArrowDown', 'ArrowUp'].includes(event.key) || document.activeElement === search) return;
        const links = [...results.querySelectorAll('a')], index = links.indexOf(document.activeElement);
        if (index < 0) return;
        event.preventDefault();
        const next = index + (event.key === 'ArrowDown' ? 1 : -1);
        if (next < 0) search.focus(); else links[Math.min(next, links.length - 1)]?.focus();
    });
    document.addEventListener('keydown', event => {
        const typing = ['INPUT', 'TEXTAREA', 'SELECT'].includes(document.activeElement?.tagName) || document.activeElement?.isContentEditable;
        if ((event.key.toLowerCase() === 'k' && (event.ctrlKey || event.metaKey)) || (event.key === '/' && !typing)) { event.preventDefault(); if (dialog?.open) dialog.close(); else openSearch(); }
    });
    document.querySelectorAll('.profile-menu').forEach(menu => document.addEventListener('click', event => { if (!menu.contains(event.target)) menu.open = false; }));
    document.querySelectorAll('form').forEach(form => form.addEventListener('submit', event => {
        if (form.dataset.submitting === 'true') { event.preventDefault(); return; }
        form.dataset.submitting = 'true';
        const button = event.submitter;
        if (button) { button.classList.add('button-loading'); button.setAttribute('aria-disabled', 'true'); }
    }));
    window.addEventListener('pageshow', () => {
        document.querySelectorAll('form[data-submitting]').forEach(form => { delete form.dataset.submitting; });
        document.querySelectorAll('.button-loading').forEach(button => { button.classList.remove('button-loading'); button.removeAttribute('aria-disabled'); });
        body.classList.remove('page-leaving');
    });
    document.addEventListener('click', event => {
        const link = event.target.closest('a[href]');
        if (!link || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey || link.target || link.download || event.defaultPrevented) return;
        const destination = new URL(link.href, location.href);
        if (destination.origin === location.origin && destination.pathname !== location.pathname && !destination.pathname.includes('export') && !destination.pathname.startsWith('/download')) body.classList.add('page-leaving');
    });
    const ns = 'http://www.w3.org/2000/svg';
    document.querySelectorAll('[data-activity-chart]').forEach(figure => {
        let series; try { series = JSON.parse(figure.dataset.series); } catch { return; }
        const svg = figure.querySelector('svg'), drawing = svg.querySelector('[data-chart-drawing]'), tooltip = figure.querySelector('.chart-tooltip');
        let displayed = series;
        let chartWidth = 740, windowDays = 30, lastWidth = 0;
        const element = (name, attributes, text) => { const node = document.createElementNS(ns, name); for (const [key, value] of Object.entries(attributes)) node.setAttribute(key, String(value)); if (text) node.textContent = text; return node; };
        function draw(days) {
            windowDays = days; chartWidth = Math.max(300, figure.clientWidth || 740);
            svg.setAttribute('viewBox', `0 0 ${chartWidth} 236`);
            svg.querySelectorAll(':scope > .chart-grid').forEach(line => line.setAttribute('x2', chartWidth - 20));
            displayed = series.slice(-days); const max = Math.max(1, ...displayed.map(p => Number(p.value)));
            const points = displayed.map((p, i) => `${38 + i * ((chartWidth - 58) / Math.max(1, displayed.length - 1))},${192 - Number(p.value) / max * 158}`).join(' ');
            const fill = svg.querySelector('linearGradient').id;
            drawing.replaceChildren(element('polygon', {points: `38,192 ${points} ${chartWidth - 20},192`, fill: `url(#${fill})`}), element('polyline', {points, class: 'chart-line'}));
            const labels = [...new Set([0, Math.floor((displayed.length - 1) / 2), displayed.length - 1])];
            labels.forEach(index => drawing.append(element('text', {class: 'chart-axis', x: 38 + index * (chartWidth - 58) / Math.max(1, displayed.length - 1), y: 223, 'text-anchor': index === 0 ? 'start' : (index === displayed.length - 1 ? 'end' : 'middle')}, new Date(displayed[index].date + 'T12:00:00Z').toLocaleDateString('en', {month: 'short', day: 'numeric', timeZone: 'UTC'}))));
            const axes = [...svg.querySelectorAll(':scope > .chart-axis')]; [0, .5, 1].forEach((level, index) => { if (axes[index]) axes[index].textContent = Math.round(max * level).toLocaleString(); });
            svg.setAttribute('aria-label', `Daily unique app-opening installations across ${days} days, UTC. Exact values are in the activity table.`);
        }
        figure.closest('.panel')?.querySelectorAll('[data-chart-days]').forEach(button => button.addEventListener('click', () => {
            figure.closest('.panel').querySelectorAll('[data-chart-days]').forEach(item => item.setAttribute('aria-pressed', String(item === button)));
            tooltip.hidden = true; draw(Number(button.dataset.chartDays));
        }));
        svg.addEventListener('pointermove', event => {
            const box = svg.getBoundingClientRect(), relative = (event.clientX - box.left) / box.width * chartWidth;
            const index = Math.max(0, Math.min(displayed.length - 1, Math.round((relative - 38) / (chartWidth - 58) * (displayed.length - 1)))), point = displayed[index];
            tooltip.textContent = `${point.date} · ${Number(point.value).toLocaleString()} installations`;
            tooltip.hidden = false;
            tooltip.style.left = `${Math.max(0, Math.min(box.width - tooltip.offsetWidth, event.clientX - box.left - tooltip.offsetWidth / 2))}px`;
            tooltip.style.top = `${Math.max(0, event.clientY - box.top - tooltip.offsetHeight - 12)}px`;
        });
        svg.addEventListener('pointerleave', () => { tooltip.hidden = true; });
        const resize = () => {
            if (lastWidth === figure.clientWidth) return;
            lastWidth = figure.clientWidth; tooltip.hidden = true; draw(windowDays);
        };
        if ('ResizeObserver' in window) new ResizeObserver(resize).observe(figure);
        else window.addEventListener('resize', resize);
        draw(30);
    });
    document.querySelectorAll('.panel table').forEach(table => {
        if (table.closest('.table-wrap')) return;
        const wrapper = document.createElement('div'); wrapper.className = 'table-wrap'; table.before(wrapper); wrapper.append(table);
    });
    document.querySelectorAll('.table-wrap').forEach(wrapper => {
        if (!wrapper.querySelector('table') || wrapper.parentElement.closest('.table-wrap')) return;
        wrapper.tabIndex = 0; wrapper.setAttribute('role', 'region');
        wrapper.setAttribute('aria-label', wrapper.closest('.panel')?.querySelector('h2')?.textContent || 'Data table');
    });
    document.querySelectorAll('.pagination nav > div').forEach((element, index) => {
        if (index > 0) element.style.display = 'flex';
    });
})();
