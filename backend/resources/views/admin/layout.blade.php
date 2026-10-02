@php
    $role = auth()->user()->role;
    $canOperate = in_array($role, ['owner', 'operator']);
    $groups = [
        'Workspace' => [
            ['dashboard', 'Overview', 'grid', 'admin.dashboard', true],
            ['installations', 'Devices', 'devices', 'admin.installation*', true],
            ['locations', 'Location insights', 'pin', 'admin.location*', $canOperate],
        ],
        'Engagement' => [
            ['campaigns', 'Campaigns', 'campaign', 'admin.campaign*', true],
            ['launch', 'Support & downloads', 'download', 'admin.launch*', true],
        ],
        'App controls' => [
            ['configuration', 'Connection & tabs', 'globe', 'admin.configuration*', true],
            ['releases', 'App releases', 'rocket', 'admin.release*', true],
            ['vpn', 'Betna VPN', 'shield', 'admin.vpn*', $role === 'owner'],
            ['cache', 'Cache controls', 'cache', 'admin.cache*', $role === 'owner'],
        ],
        'Administration' => [
            ['connections', 'Connection reports', 'activity', 'admin.connections*', true],
            ['audit', 'Activity log', 'clock', 'admin.audit*', true],
            ['staff', 'Staff access', 'users', 'admin.staff*', $role === 'owner'],
        ],
    ];
    $descriptions = [
        'admin.dashboard' => 'A clear view of your app, audience and operations.',
        'admin.installations' => 'Understand each installation and manage your test devices.',
        'admin.campaigns' => 'Reach your audience with notifications, popups and banners.',
        'admin.configuration' => 'Manage website connections, DNS, tabs and app behavior.',
        'admin.releases' => 'Verify, publish and roll out your next Android release.',
        'admin.locations' => 'Explore the last locations shared by consenting devices.',
        'admin.cache' => 'Refresh temporary files while keeping browsing sessions intact.',
        'admin.vpn' => 'Manage secure connections, server enrollment and reported usage.',
        'admin.launch' => 'One place for support, public downloads and share links.',
        'admin.connections' => 'Investigate connection checks reported by the Android app.',
        'admin.audit' => 'Review changes and actions across your workspace.',
        'admin.staff' => 'Give your team the access they need.',
    ];
    $userName = auth()->user()->name;
    $initials = \Illuminate\Support\Str::upper(\Illuminate\Support\Str::substr($userName, 0, 1));
    $assetVersion = '2026.10.02.2';
@endphp
<!doctype html>
<html lang="en" data-theme="light">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
    <meta name="color-scheme" content="light dark">
    <title>@yield('title', 'Overview') · Betna</title>
    <script src="{{ asset('admin-theme.js') }}?v={{ $assetVersion }}"></script>
    <link rel="stylesheet" href="{{ asset('admin.css') }}?v={{ $assetVersion }}">
    <script src="{{ asset('admin.js') }}?v={{ $assetVersion }}" defer></script>
    @stack('head')
</head>
<body class="admin-body">
<a class="skip-link" href="#main-content">Skip to content</a>
<div class="shell">
    <button class="sidebar-backdrop" data-nav-close aria-label="Close navigation" tabindex="-1" hidden></button>
    <aside class="sidebar" id="workspace-navigation" aria-label="Workspace navigation">
        <a class="brand" href="{{ route('admin.dashboard') }}"><span class="brand-icon"><img src="{{ asset('betna-logo.png') }}" alt=""></span><span>Betna<span class="brand-subtitle">CONTROL CENTER</span></span></a>
        <button class="icon-button sidebar-close" data-nav-close aria-label="Close navigation"><x-admin.icon name="close" /></button>
        <div class="workspace-label"><span class="workspace-dot"></span> Android workspace <span class="workspace-tag">APK</span></div>
        <nav class="sidebar-nav" aria-label="Main pages">
            @foreach($groups as $label => $links)
                <div class="nav-group"><div class="nav-label">{{ $label }}</div>
                @foreach($links as [$key, $name, $icon, $pattern, $allowed])
                    @if($allowed)
                        @php($active = request()->routeIs($pattern) || ($key === 'installations' && request()->routeIs('admin.device')))
                        <a class="nav-link {{ $active ? 'active' : '' }}" href="{{ route('admin.'.$key) }}" data-page-name="{{ $name }}" @if($active) aria-current="page" @endif><x-admin.icon :name="$icon" /><span>{{ $name }}</span>@if($active)<span class="active-dot"></span>@endif</a>
                    @endif
                @endforeach
                </div>
            @endforeach
        </nav>
        <div class="sidebar-bottom">
            <a class="security-link" href="{{ route('two-factor.recovery') }}"><x-admin.icon name="shield" /><span>Account security<small>Authenticator & recovery</small></span><x-admin.icon name="chevron" size="16" /></a>
            <div class="sidebar-account"><span class="avatar">{{ $initials }}</span><span><strong>{{ $userName }}</strong><small>{{ ucfirst($role) }} access</small></span><form method="post" action="{{ route('logout') }}">@csrf<button class="icon-button" title="Sign out" aria-label="Sign out"><x-admin.icon name="logout" size="18" /></button></form></div>
        </div>
    </aside>
    <div class="workspace-main">
        <div class="topbar">
            <div class="topbar-left"><button class="icon-button mobile-menu" data-nav-open aria-controls="workspace-navigation" aria-expanded="false" aria-label="Open navigation"><x-admin.icon name="menu" /></button><div class="breadcrumbs"><span>Workspace</span><x-admin.icon name="chevron" size="14" /><strong>@yield('title', 'Overview')</strong></div></div>
            <div class="topbar-tools"><button class="search-launch" data-command-open aria-label="Search workspace pages"><x-admin.icon name="search" size="18" /><span>Go to a page</span><kbd>⌘ K</kbd></button><button class="icon-button theme-toggle" data-theme-toggle aria-label="Change appearance"><span class="theme-light"><x-admin.icon name="sun" /></span><span class="theme-dark"><x-admin.icon name="moon" /></span></button><details class="profile-menu"><summary aria-label="Account menu"><span class="avatar avatar-small">{{ $initials }}</span></summary><div class="profile-popover"><strong>{{ $userName }}</strong><small>{{ ucfirst($role) }} access</small><a href="{{ route('two-factor.recovery') }}"><x-admin.icon name="key" size="18" />Account security</a><form method="post" action="{{ route('logout') }}">@csrf<button class="text-button"><x-admin.icon name="logout" size="18" />Sign out</button></form></div></details></div>
        </div>
        <main id="main-content" tabindex="-1">
            <header class="page-header"><div><div class="eyebrow">BETNA WORKSPACE</div><h1>@yield('heading', trim($__env->yieldContent('title', 'Overview')))</h1><p class="page-description">@yield('description', $descriptions[request()->route()?->getName()] ?? 'Manage your app with confidence.')</p></div><div class="page-header-actions">@hasSection('page-actions') @yield('page-actions') @else <span class="date-label"><x-admin.icon name="calendar" size="16" />{{ now()->timezone('Africa/Addis_Ababa')->format('d M Y') }}</span> @endif</div></header>
            @if(session('status'))<div class="notice" role="status"><x-admin.icon name="check" /><span>{{ session('status') }}</span><button class="icon-button" data-dismiss-notice aria-label="Dismiss message"><x-admin.icon name="close" size="18" /></button></div>@endif
            @if($errors->any())<div class="errors" role="alert"><strong>Check these fields</strong><ul>@foreach($errors->all() as $error)<li>{{ $error }}</li>@endforeach</ul></div>@endif
            @yield('content')
            <footer class="workspace-footer"><span><span class="footer-dot"></span> Betna control center</span><span>Reports count installations · Online uses recent foreground heartbeats</span></footer>
        </main>
    </div>
</div>
<nav class="mobile-quicknav" aria-label="Quick navigation"><a href="{{ route('admin.dashboard') }}" @if(request()->routeIs('admin.dashboard')) aria-current="page" @endif><x-admin.icon name="grid" /><span>Overview</span></a><a href="{{ route('admin.installations') }}" @if(request()->routeIs('admin.installation*', 'admin.device')) aria-current="page" @endif><x-admin.icon name="devices" /><span>Devices</span></a><a href="{{ route('admin.campaigns') }}" @if(request()->routeIs('admin.campaign*')) aria-current="page" @endif><x-admin.icon name="campaign" /><span>Campaigns</span></a><button data-nav-open aria-controls="workspace-navigation" aria-expanded="false"><x-admin.icon name="menu" /><span>More</span></button></nav>
<dialog class="command-dialog" id="page-search" aria-labelledby="page-search-title"><div class="command-search"><x-admin.icon name="search" /><label class="sr-only" id="page-search-title" for="page-search-input">Search workspace pages</label><input id="page-search-input" placeholder="Where would you like to go?" autocomplete="off"><button class="icon-button" data-command-close aria-label="Close page search"><x-admin.icon name="close" /></button></div><div class="command-results" id="page-search-results"></div><p class="command-empty" hidden>No matching pages.</p><div class="command-footer"><span>Search pages in your workspace</span><kbd>esc</kbd> to close</div></dialog>
<div class="ui-toast" role="status" aria-live="polite" hidden></div>
@stack('scripts')
</body>
</html>
