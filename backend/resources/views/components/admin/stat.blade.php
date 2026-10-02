@props(['label', 'value', 'note' => '', 'icon' => 'chart', 'accent' => 'red'])
<article {{ $attributes->class(['stat', 'metric-card', 'metric-'.$accent]) }}>
    <div class="metric-top"><span>{{ $label }}</span><span class="metric-icon"><x-admin.icon :name="$icon" /></span></div>
    <strong class="metric-value">{{ $value }}</strong>
    @if($note)<div class="metric-note">{{ $note }}</div>@endif
</article>
