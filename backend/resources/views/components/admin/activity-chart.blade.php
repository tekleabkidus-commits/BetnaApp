@props(['rows'])
@php
    $byDay = collect($rows)->keyBy('day');
    $end = request('to') ? \Carbon\Carbon::parse(request('to'))->endOfDay() : now();
    $series = collect(range(29, 0))->map(function ($offset) use ($byDay, $end) {
        $day = $end->copy()->subDays($offset)->format('Y-m-d');
        return ['date' => $day, 'value' => (int) ($byDay->get($day)?->total ?? 0)];
    });
    $max = max(1, $series->max('value'));
    $points = $series->map(fn ($point, $index) => round(38 + $index * (682 / 29), 2).','.round(192 - $point['value'] / $max * 158, 2))->implode(' ');
    $gradientId = 'chart-fill-'.\Illuminate\Support\Str::random(8);
@endphp
@if($byDay->isNotEmpty())
<figure class="activity-chart" data-activity-chart data-series="{{ $series->toJson() }}" aria-label="Daily unique app-opening installations, UTC">
    <svg viewBox="0 0 740 236" role="img" aria-label="{{ $series->sum('value') }} daily app-opening observations in the displayed 30-day window. A device can appear on several days.">
        <defs><linearGradient id="{{ $gradientId }}" x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stop-color="#d6314d" stop-opacity=".15" /><stop offset="100%" stop-color="#d6314d" stop-opacity="0" /></linearGradient></defs>
        @foreach([0, .5, 1] as $level)<line class="chart-grid" x1="38" x2="720" y1="{{ 192 - $level * 158 }}" y2="{{ 192 - $level * 158 }}" /><text class="chart-axis" x="0" y="{{ 196 - $level * 158 }}">{{ number_format($max * $level, 0) }}</text>@endforeach
        <g data-chart-drawing><polygon points="38,192 {{ $points }} 720,192" fill="url(#{{ $gradientId }})" /><polyline class="chart-line" points="{{ $points }}" />@foreach([0, 14, 29] as $index)<text class="chart-axis" x="{{ 38 + $index * (682 / 29) }}" y="223" text-anchor="{{ $index === 0 ? 'start' : ($index === 29 ? 'end' : 'middle') }}">{{ \Carbon\Carbon::parse($series[$index]['date'])->format('M j') }}</text>@endforeach</g>
    </svg>
    <div class="chart-tooltip" hidden></div>
</figure>
@else
<div class="chart-empty"><x-admin.icon name="chart" /><strong>Your activity chart starts here</strong><p>App-opening reports will appear when devices connect. Try another filter if you expected activity.</p></div>
@endif
