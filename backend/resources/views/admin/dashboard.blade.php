@extends('admin.layout')
@section('title', 'Overview')
@section('heading', 'Your app, at a glance')
@section('page-actions')
    <a class="button secondary" href="{{ route('admin.export', request()->query()) }}"><x-admin.icon name="download" size="16" />Export report</a>
@endsection
@section('content')
@php
    $latestVersion = $versions->first();
    $latestShare = $total && $latestVersion ? round($latestVersion->total / $total * 100, 1) : 0;
    $canOperate = in_array(auth()->user()->role, ['owner', 'operator', 'super_admin']);
@endphp
<div class="stats metric-grid">
    <x-admin.stat label="Total installations" :value="number_format($total)" note="All registered app installations" icon="devices" />
    <x-admin.stat label="Online now" :value="number_format($online)" :note="'Foreground heartbeat within '.config('mobile.online_seconds').' seconds'" icon="activity" accent="green" />
    <x-admin.stat label="Active in 24 hours" :value="number_format($daily)" note="Installations with a recent heartbeat" icon="users" accent="violet" />
    <x-admin.stat label="Active in 30 days" :value="number_format($monthly)" note="Monthly activity from device heartbeats" icon="calendar" accent="blue" />
</div>
@include('admin.device-summary')
<div class="dashboard-grid">
    <section class="panel">
        <div class="panel-heading"><div><h2>Audience activity</h2><p>Unique app-opening installations each day · UTC</p></div><div class="chart-range" role="group" aria-label="Chart date window"><button type="button" data-chart-days="7" aria-pressed="false">7 days</button><button type="button" data-chart-days="30" aria-pressed="true">30 days</button></div></div>
        <x-admin.activity-chart :rows="$dailyActivity" />
        <div class="chart-summary"><span><strong>{{ number_format($weekly) }}</strong> active in 7 days</span><span><strong>{{ number_format($monthly) }}</strong> active in 30 days</span><span class="chart-legend"><i></i> App openings</span></div>
    </section>
    <section class="panel">
        <div class="panel-heading"><div><h2>Version adoption</h2><p>Latest version observed on a device</p></div>@if(auth()->user()->canAccessAdminRoute('admin.releases'))<a class="panel-link" href="{{ route('admin.releases') }}">Releases <x-admin.icon name="arrow" size="14" /></a>@endif</div>
        @if($latestVersion)
            <div class="version-highlight"><svg class="adoption-ring" viewBox="0 0 100 100" role="img" aria-label="{{ $latestShare }} percent on latest observed version"><circle class="ring-track" cx="50" cy="50" r="40" /><circle class="ring-progress" cx="50" cy="50" r="40" stroke-dasharray="{{ $latestShare * 2.51327 }} 251.327" /></svg><div><div class="version-share">{{ $latestShare }}<span style="font-size:17px">%</span></div><div class="version-caption">Using v{{ $latestVersion->version_name }}<br>{{ number_format($latestVersion->total) }} installations</div></div></div>
            <div class="version-list">@foreach($versions->take(3) as $row)@php($share = $total ? round($row->total / $total * 100, 1) : 0)<div class="version-item"><div class="version-row"><strong>v{{ $row->version_name }}</strong><span>{{ $share }}% · {{ number_format($row->total) }}</span></div><div class="progress-track" style="--progress:{{ $share }}%"><span></span></div></div>@endforeach</div>
        @else
            <div class="chart-empty"><x-admin.icon name="rocket" /><strong>No versions reported yet</strong><p>Version adoption appears when the first app registers.</p></div>
        @endif
    </section>
</div>
<div class="operations-grid">
    <a class="operation-card" href="{{ route('admin.campaigns') }}"><span class="operation-icon"><x-admin.icon name="campaign" /></span><span><strong>Engage your audience</strong><small>Notifications, popups and banners</small></span><x-admin.icon name="arrow" /></a>
    @if(auth()->user()->canAccessAdminRoute('admin.configuration'))<a class="operation-card" href="{{ route('admin.configuration') }}"><span class="operation-icon"><x-admin.icon name="globe" /></span><span><strong>Connection settings</strong><small>Website, DNS and browser tabs</small></span><x-admin.icon name="arrow" /></a>@endif
    <a class="operation-card" href="{{ route($canOperate ? 'admin.locations' : 'admin.installations') }}"><span class="operation-icon"><x-admin.icon :name="$canOperate ? 'pin' : 'devices'" /></span><span><strong>{{ $canOperate ? 'Explore location insights' : 'Explore your installations' }}</strong><small>{{ $canOperate ? 'Consented locations and device reports' : 'Versions, activity and preferences' }}</small></span><x-admin.icon name="arrow" /></a>
</div>
<section class="panel filter-panel"><div class="filter-heading"><x-admin.icon name="filter" size="16" />Report filters</div><form class="filter" method="get"><label>From<input type="date" name="from" value="{{ request('from') }}"></label><label>To<input type="date" name="to" value="{{ request('to') }}"></label><label>Version code<input type="number" name="version" min="1" value="{{ request('version') }}" placeholder="All versions"></label><label>Language<input name="language" placeholder="en / am" value="{{ request('language') }}"></label><label>Manufacturer<input name="manufacturer" value="{{ request('manufacturer') }}" placeholder="All brands"></label><label>Model<input name="model" value="{{ request('model') }}" placeholder="All models"></label><label>Android API<input name="android" type="number" min="26" max="100" value="{{ request('android') }}" placeholder="All versions"></label><button>Apply filters</button>@if(request()->query())<a class="button secondary" href="{{ route('admin.dashboard') }}">Reset</a>@endif</form><p class="table-caption">Filters apply to event activity and the app-opening chart. Heartbeat totals and version adoption cover all installations.</p></section>
<div class="grid">
    <section class="panel"><div class="panel-heading"><div><h2>Activity breakdown</h2><p>Events received in the selected report period</p></div><span class="badge badge-neutral">{{ $eventCounts->count() }} event types</span></div><div class="table-wrap"><table><thead><tr><th>Event</th><th>Total</th><th>Installations</th></tr></thead><tbody>@forelse($eventCounts as $row)<tr><td>{{ ucfirst(str_replace('_', ' ', $row->type)) }}</td><td>{{ number_format($row->total) }}</td><td>{{ number_format($row->unique_installations) }}</td></tr>@empty<tr><td colspan="3" class="empty">Events appear after an app connects. No sample activity is included.</td></tr>@endforelse</tbody></table></div></section>
    <section class="panel"><div class="panel-heading"><div><h2>Campaign delivery</h2><p>Reported delivery states · all time</p></div><x-admin.icon name="campaign" class="muted" /></div><div class="table-wrap"><table><tr><th>State</th><th>Deliveries</th></tr>@forelse($deliveryCounts as $row)<tr><td><span class="badge {{ in_array($row->status, ['displayed', 'clicked', 'accepted']) ? 'badge-success' : 'badge-neutral' }}">{{ ucfirst(str_replace('_', ' ', $row->status)) }}</span></td><td>{{ number_format($row->total) }}</td></tr>@empty<tr><td colspan="2" class="empty">Delivery results appear after your first campaign.</td></tr>@endforelse</table></div><p class="table-caption">Accepted means accepted by the push service, not displayed on a phone.</p></section>
</div>
<div class="grid">
    <section class="panel"><div class="panel-heading"><div><h2>Audience retention</h2><p>Return rate after installation · last 60 days</p></div><x-admin.icon name="users" class="muted" /></div><div class="table-wrap"><table><tr><th>Window</th><th>Eligible</th><th>Returned</th><th>Rate</th></tr>@foreach($retention as $row)<tr><td>Day {{ $row['day'] }}–{{ $row['day'] + 1 }}</td><td>{{ number_format($row['eligible']) }}</td><td>{{ number_format($row['returned']) }}</td><td><strong>{{ $row['percentage'] !== null ? $row['percentage'].'%' : '—' }}</strong></td></tr>@endforeach</table></div><p class="table-caption">A dash means there is not enough mature installation history.</p></section>
    <section class="panel"><div class="panel-heading"><div><h2>Campaign eligibility</h2><p>Decisions from the last 7 days</p></div><x-admin.icon name="check" class="muted" /></div><div class="table-wrap"><table><tr><th>Decision</th><th>Checks</th></tr>@forelse($eligibility as $row)<tr><td>{{ ucfirst(str_replace('_', ' ', $row->reason)) }}</td><td>{{ number_format($row->total) }}</td></tr>@empty<tr><td colspan="2" class="empty">Eligibility checks start when campaigns are evaluated.</td></tr>@endforelse</table></div></section>
</div>
<details class="panel"><summary>Detailed daily activity & version distribution</summary><div class="grid"><div><h2>App openings · UTC</h2><div class="table-wrap"><table><tr><th>Day</th><th>Installations</th></tr>@forelse($dailyActivity as $row)<tr><td>{{ $row->day }}</td><td>{{ number_format($row->total) }}</td></tr>@empty<tr><td colspan="2" class="empty">No app-opening reports for this period.</td></tr>@endforelse</table></div></div><div><h2>All observed versions</h2><div class="table-wrap"><table><tr><th>Version</th><th>Installations</th><th>Share</th></tr>@forelse($versions as $row)<tr><td>{{ $row->version_name }} <small>({{ $row->version_code }})</small></td><td>{{ number_format($row->total) }}</td><td>{{ $total ? round($row->total / $total * 100, 1) : 0 }}%</td></tr>@empty<tr><td colspan="3" class="empty">No versions reported.</td></tr>@endforelse</table></div></div></div></details>
<section class="panel"><div class="panel-heading"><div><h2>Device compatibility</h2><p>Most frequently reported device and WebView combinations</p></div><a class="panel-link" href="{{ route('admin.installations') }}">All devices <x-admin.icon name="arrow" size="14" /></a></div><div class="table-wrap"><table><tr><th>Manufacturer / model</th><th>Android API</th><th>WebView</th><th>Installations</th></tr>@forelse($compatibility as $row)<tr><td><strong>{{ $row->manufacturer ?: 'Unknown' }} {{ $row->model }}</strong></td><td><span class="badge badge-neutral">API {{ $row->android_version }}</span></td><td>{{ $row->webview_version ?: 'Unknown' }}</td><td>{{ number_format($row->total) }}</td></tr>@empty<tr><td colspan="4" class="empty">Device compatibility appears when installations report their details.</td></tr>@endforelse</table></div></section>
@endsection
