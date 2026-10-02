@extends('admin.layout')
@section('title', 'Campaigns')
@section('page-actions')
@if(in_array(auth()->user()->role, ['owner', 'operator']))<a class="button" href="{{ route('admin.campaign.new') }}"><x-admin.icon name="plus" size="16" />New campaign</a>@endif
@endsection
@section('content')
<div class="campaign-grid">
@forelse($campaigns as $campaign)
    <article class="campaign-card">
        <div class="campaign-card-header"><span class="campaign-type"><x-admin.icon :name="$campaign->type === 'push' ? 'campaign' : ($campaign->type === 'popup' ? 'grid' : 'monitor')" /></span><span class="badge {{ $campaign->status === 'active' ? 'badge-success' : ($campaign->status === 'paused' ? 'badge-warning' : 'badge-neutral') }}"><span class="status-dot"></span>{{ ucfirst($campaign->status) }}</span></div>
        <h2><a href="{{ route('admin.campaign.edit', $campaign) }}">{{ $campaign->name }}</a></h2><p>{{ \Illuminate\Support\Str::limit($campaign->title, 100) }}</p>
        <div class="campaign-meta"><x-admin.icon name="users" size="15" /><span>{{ ucfirst($campaign->type) }} · {{ $campaign->delivery_scope === 'test' ? 'Test devices' : 'Audience rules' }}</span></div>
        <div class="campaign-meta"><x-admin.icon name="clock" size="15" /><span>{{ $campaign->starts_at?->timezone($campaign->timezone)->format('M j, H:i') ?? 'When eligible' }} · {{ $campaign->timezone }}</span></div>
        <div class="campaign-card-actions"><a href="{{ route('admin.campaign.preview', $campaign) }}">Preview & test <x-admin.icon name="arrow" size="14" /></a><a href="{{ route('admin.campaign.report', $campaign) }}">View report <x-admin.icon name="chart" size="14" /></a></div>
        @if(in_array(auth()->user()->role, ['owner', 'operator']))<form method="post" action="{{ route('admin.campaign.action', $campaign) }}" class="actions">@csrf<label class="sr-only" for="campaign-action-{{ $campaign->id }}">Action for {{ $campaign->name }}</label><select id="campaign-action-{{ $campaign->id }}" name="action"><option value="estimate">Estimate audience</option><option value="pause">Pause campaign</option><option value="activate">Activate campaign</option>@if($campaign->delivery_scope === 'test')<option value="promote">Publish to everyone</option>@endif<option value="duplicate">Duplicate as draft</option>@if($campaign->type === 'push')<option value="send-now">Send now</option>@endif</select><button class="secondary">Apply</button></form>@endif
    </article>
@empty
    <div class="panel large-empty" style="grid-column:1/-1"><div class="empty-icon"><x-admin.icon name="campaign" size="28" /></div><h2>Start a conversation with your audience</h2><p>Create a notification, popup or banner. Save it as a draft and preview it on a test device before publishing.</p>@if(in_array(auth()->user()->role, ['owner', 'operator']))<a class="button" href="{{ route('admin.campaign.new') }}"><x-admin.icon name="plus" size="16" />Create your first campaign</a>@endif</div>
@endforelse
</div>
<div class="pagination">{{ $campaigns->links() }}</div>
@endsection
