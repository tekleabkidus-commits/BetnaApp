(() => {
    try {
        const choice = localStorage.getItem('betna_admin_theme') || 'system';
        document.documentElement.dataset.theme = choice === 'system' ? (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light') : choice;
        document.documentElement.dataset.themeChoice = choice;
    } catch { document.documentElement.dataset.theme = 'light'; }
})();
