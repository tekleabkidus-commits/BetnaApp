@props(['name' => 'grid', 'size' => 20])
@php
    $paths = [
        'grid' => ['M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z'],
        'devices' => ['M7 2h10a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z', 'M10 5h4 M11 19h2'],
        'campaign' => ['M3 11v4h4l10 5V5L7 10H4a1 1 0 0 0-1 1z', 'M7 15l2 6h3l-2-5 M20 9a6 6 0 0 1 0 7'],
        'globe' => ['M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0z', 'M3 12h18 M12 3c4 5 4 13 0 18-4-5-4-13 0-18z'],
        'pin' => ['M20 10c0 6-8 12-8 12S4 16 4 10a8 8 0 0 1 16 0z', 'M15 10a3 3 0 1 1-6 0 3 3 0 0 1 6 0z'],
        'download' => ['M12 3v12 M7 10l5 5 5-5 M4 16v4a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-4'],
        'shield' => ['M12 3l8 3v6c0 5-8 9-8 9s-8-4-8-9V6z', 'M8 12l3 3 5-6'],
        'cache' => ['M20 7H4 M9 3h6 M6 7l1 14h10l1-14 M10 11v6 M14 11v6'],
        'activity' => ['M3 12h4l3-8 4 16 3-8h4'],
        'users' => ['M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2 M22 21v-2a4 4 0 0 0-3-3.9', 'M13 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0z M17 3a4 4 0 0 1 0 8'],
        'settings' => ['M12 8a4 4 0 1 1 0 8 4 4 0 0 1 0-8z', 'M9 3h6l1 3 3 1 2 5-2 5-3 1-1 3H9l-1-3-3-1-2-5 2-5 3-1z'],
        'search' => ['M18 10a7 7 0 1 1-14 0 7 7 0 0 1 14 0z M15 15l6 6'],
        'chevron' => ['M9 5l7 7-7 7'],
        'menu' => ['M4 6h16 M4 12h16 M4 18h16'],
        'close' => ['M6 6l12 12 M18 6L6 18'],
        'arrow' => ['M7 17L17 7 M7 7h10v10'],
        'sun' => ['M16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0z M12 2v2 M12 20v2 M2 12h2 M20 12h2 M5 5l1.5 1.5 M17.5 17.5L19 19 M19 5l-1.5 1.5 M6.5 17.5L5 19'],
        'moon' => ['M21 13a9 9 0 1 1-10-10 7 7 0 0 0 10 10z'],
        'monitor' => ['M3 3h18v14H3z M8 21h8 M12 17v4'],
        'logout' => ['M9 3H4v18h5 M10 12h12 M18 8l4 4-4 4'],
        'key' => ['M15 8a5 5 0 1 1-10 0 5 5 0 0 1 10 0z M14 12l8 8 M18 16l-3 3 M21 19l-3 3'],
        'plus' => ['M12 5v14 M5 12h14'],
        'check' => ['M5 12l4 4L19 6'],
        'copy' => ['M8 8h13v13H8z M16 8V3H3v13h5'],
        'clock' => ['M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0z M12 7v5l3 2'],
        'calendar' => ['M4 5h16v16H4z M16 3v4 M8 3v4 M4 11h16'],
        'filter' => ['M3 4h18l-7 8v7l-4 2v-9z'],
        'inbox' => ['M4 3h16l2 13v5H2v-5z M2 16h6l2 3h4l2-3h6'],
        'rocket' => ['M14 5c4-3 7-2 7-2s1 3-2 7l-7 7-5-5z M7 12H3l3-5h5 M12 17v4l5-3v-5 M4 16c-2 1-2 4-2 4s3 0 4-2'],
        'chart' => ['M4 3v18h17 M8 16v-5 M13 16V7 M18 16v-8'],
    ];
@endphp
<svg {{ $attributes->class(['icon']) }} width="{{ $size }}" height="{{ $size }}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false">
    @foreach($paths[$name] ?? $paths['grid'] as $path)<path d="{{ $path }}" />@endforeach
</svg>
