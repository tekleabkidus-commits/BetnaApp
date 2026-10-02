@extends('admin.layout')
@section('title', 'Device installation history')
@section('description', 'Review the installation records linked to one Android device profile.')
@section('page-actions')
    <a class="button secondary" href="{{ route('admin.installations', ['q' => $identity->id]) }}"><x-admin.icon name="devices" size="16" />Filter installations</a>
@endsection
@section('content')
    <section class="panel">
        <div class="panel-heading"><div><div class="eyebrow">Recognized device profile</div><h2 style="overflow-wrap:anywhere">{{ $identity->id }}</h2></div><span class="badge {{ $identity->installations_count > 1 ? 'badge-success' : 'badge-neutral' }}">{{ $identity->installations_count > 1 ? 'Repeat installations' : 'First installation' }}</span></div>
        <div class="stats">
            <x-admin.stat label="Installations" :value="number_format($identity->installations_count)" note="Separate registrations and security credentials" icon="devices" />
            <x-admin.stat label="Repeat installations" :value="number_format(max(0, $identity->installations_count - 1))" note="Additional records linked to this profile" icon="activity" accent="violet" />
            <x-admin.stat label="App openings" :value="number_format($sessions)" note="Reported across linked installations" icon="users" accent="blue" />
        </div>
        <p>First registered {{ $identity->installations_min_created_at ?: 'Unknown' }} UTC · Last active {{ $identity->installations_max_last_seen_at ?: 'Not yet reported' }} UTC</p>
        <p class="table-caption">This match uses a pseudonymous app-scoped identifier reported by the app. It is not proof of a person's identity or a security credential. A factory reset, different Android user profile or signing key can create a different identity. Passwords, cookies, permissions and installation tokens are not restored or shared.</p>
    </section>
    <section class="panel">
        <div class="panel-heading"><div><h2>Installation timeline</h2><p>Newest registration first · {{ number_format($installations->total()) }} records</p></div><x-admin.icon name="clock" class="muted" /></div>
        <div class="table-wrap"><table class="device-table"><thead><tr><th>Installation</th><th>App & device</th><th>Registered</th><th>Last active</th><th>Access</th></tr></thead><tbody>
            @forelse($installations as $installation)
                <tr><td data-label="Installation"><a class="row-title" href="{{ route('admin.device', $installation) }}">{{ $installation->label ?: ($installation->model ?: 'Android device') }}</a><div class="row-meta"><code>{{ $installation->id }}</code></div></td><td data-label="App & device"><strong>v{{ $installation->version_name }}</strong><div class="row-meta">{{ $installation->manufacturer }} {{ $installation->model }} · API {{ $installation->android_version }}</div></td><td data-label="Registered">{{ $installation->created_at }} UTC</td><td data-label="Last active">{{ $installation->last_seen_at?->diffForHumans() ?: 'Not yet reported' }}</td><td data-label="Access"><span class="badge {{ $installation->revoked_at ? 'badge-warning' : 'badge-neutral' }}">{{ $installation->revoked_at ? 'Revoked' : 'Enabled' }}</span></td></tr>
            @empty
                <tr><td colspan="5" class="empty">No linked installations.</td></tr>
            @endforelse
        </tbody></table></div>
        {{ $installations->links() }}
    </section>
@endsection
