<?php

namespace App\Services;

use App\Models\Campaign;
use App\Models\Delivery;
use App\Models\Installation;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Str;

class CampaignEngine
{
    public function audienceMatches(Campaign $campaign, Installation $device): bool
    {
        if ($campaign->delivery_scope === 'test' && (! $device->test_device || ! in_array($device->id, $campaign->test_installation_ids ?? [], true))) {
            return false;
        }
        $audience = $campaign->audience ?? [];
        if (in_array($device->id, $audience['exclude_installation_ids'] ?? [], true)) {
            return false;
        }
        $checks = [];
        foreach ($audience['rules'] ?? [] as $rule) {
            $field = $rule['field'];
            $expected = $rule['value'];
            $actual = match ($field) {
                'manufacturer','model','webview_version','push_available','low_ram','version_code','android_version','language','notifications_enabled','promotions_enabled','session_count','test_device' => $device->{$field},
                'installation_id' => $device->id,
                'sessions_7d' => DB::table('telemetry_events')->where('installation_id', $device->id)->where('type', 'app_open')->where('created_at', '>=', now()->subDays(7))->count(),
                'age_days' => (int) $device->created_at->diffInDays(now()),
                'inactive_days' => $device->last_seen_at ? (int) $device->last_seen_at->diffInDays(now()) : 9999,
                'displayed_campaign' => Delivery::where('installation_id', $device->id)->where('campaign_id', (int) $expected)->whereNotNull('displayed_at')->exists(),
                'clicked_campaign' => Delivery::where('installation_id', $device->id)->where('campaign_id', (int) $expected)->whereNotNull('clicked_at')->exists(),
                default => null,
            };
            if (in_array($field, ['displayed_campaign', 'clicked_campaign'], true)) {
                $expected = true;
            }
            $checks[] = match ($rule['op'] ?? 'eq') {
                'eq' => $actual == $expected, 'ne' => $actual != $expected,
                'gte' => is_numeric($actual) && is_numeric($expected) && $actual >= $expected,
                'lte' => is_numeric($actual) && is_numeric($expected) && $actual <= $expected,
                'in' => is_array($expected) && in_array($actual, $expected),
                'not_in' => is_array($expected) && ! in_array($actual, $expected),
                default => false,
            };
        }
        $matches = ! $checks || (($audience['mode'] ?? 'all') === 'any' ? in_array(true, $checks, true) : ! in_array(false, $checks, true));
        $percentage = (int) ($audience['percentage'] ?? 100);

        return $matches && (hexdec(substr(hash('sha256', $campaign->id.':'.$device->id), 0, 6)) % 100) < $percentage;
    }

    public function reason(Campaign $campaign, Installation $device, ?string $sessionId = null): ?string
    {
        if ($campaign->status !== 'active') {
            return 'not_active';
        }
        if ($campaign->starts_at && $campaign->starts_at->isFuture()) {
            return 'not_started';
        }
        if ($campaign->ends_at && $campaign->ends_at->isPast()) {
            return 'expired';
        }
        if (! $device->promotions_enabled) {
            return 'promotions_disabled';
        }
        if ($campaign->type === 'push' && (! $device->notifications_enabled || ! $device->push_token)) {
            return 'notifications_unavailable';
        }
        if (! $this->audienceMatches($campaign, $device)) {
            return 'audience_mismatch';
        }
        if (DB::table('campaign_opt_outs')->where('installation_id', $device->id)->where('campaign_id', $campaign->id)->exists()) {
            return 'opted_out';
        }
        if ($campaign->quiet_start && $campaign->quiet_end) {
            $time = now($campaign->timezone)->format('H:i');
            $start = $campaign->quiet_start;
            $end = $campaign->quiet_end;
            if ($start !== $end && ($start < $end ? $time >= $start && $time < $end : $time >= $start || $time < $end)) {
                return 'quiet_hours';
            }
        }
        $history = Delivery::where('installation_id', $device->id)->where('campaign_id', $campaign->id)->where('is_test', false)->whereNotIn('status', ['failed', 'expired', 'cancelled']);
        if ($campaign->max_per_installation > 0 && (clone $history)->count() >= $campaign->max_per_installation) {
            return 'lifetime_cap';
        }
        $startOfDay = now($campaign->timezone)->startOfDay()->utc();
        if ((clone $history)->where('created_at', '>=', $startOfDay)->count() >= $campaign->max_per_day) {
            return 'daily_cap';
        }
        if ($campaign->once_per_session && $sessionId && (clone $history)->where('session_id', $sessionId)->exists()) {
            return 'session_cap';
        }
        if ((clone $history)->where('created_at', '>', now()->subMinutes($campaign->cooldown_minutes))->exists()) {
            return 'cooldown';
        }
        if (Delivery::where('installation_id', $device->id)->where('is_test', false)->whereNotIn('status', ['failed', 'expired', 'cancelled'])->where('created_at', '>=', $startOfDay)->count() >= (ConfigurationService::selected($device)?->payload['campaigns']['daily_cap'] ?? config('mobile.global_campaign_cap'))) {
            return 'global_daily_cap';
        }

        return null;
    }

    public function reserve(Campaign $campaign, Installation $device, string $trigger, ?string $sessionId = null, ?int $runId = null): ?Delivery
    {
        return DB::transaction(function () use ($campaign, $device, $trigger, $sessionId, $runId): ?Delivery {
            $device = Installation::whereKey($device->id)->lockForUpdate()->firstOrFail();
            if ($runId && ($previous = Delivery::where('campaign_run_id', $runId)->where('installation_id', $device->id)->first())) {
                return $previous;
            }
            Delivery::where('installation_id', $device->id)->where('status', 'offered')->where('expires_at', '<', now())->update(['status' => 'expired']);
            $pending = Delivery::where('installation_id', $device->id)->where('campaign_id', $campaign->id)->where('is_test', false)->where('status', 'offered')->where('expires_at', '>', now())->first();
            if ($pending) {
                $this->log($device, $campaign, $trigger, 'pending_offer');

                return $pending;
            }
            $reason = $this->reason($campaign, $device, $sessionId);
            $this->log($device, $campaign, $trigger, $reason ?? 'eligible');
            if ($reason) {
                return null;
            }

            return Delivery::create([
                'id' => (string) Str::uuid(), 'campaign_id' => $campaign->id, 'installation_id' => $device->id, 'campaign_run_id' => $runId,
                'session_id' => $sessionId, 'is_test' => false, 'status' => $campaign->type === 'push' ? 'queued' : 'offered',
                'expires_at' => $campaign->type === 'push' ? min($campaign->ends_at ?? now()->addDay(), now()->addDay()) : now()->addMinutes(2),
            ]);
        });
    }

    public function log(Installation $device, ?Campaign $campaign, string $trigger, string $reason): void
    {
        DB::table('eligibility_checks')->insert(['installation_id' => $device->id, 'campaign_id' => $campaign?->id, 'trigger' => $trigger, 'reason' => $reason, 'created_at' => now()]);
    }

    public function content(Campaign $campaign, Delivery $delivery, Installation $device): array
    {
        $localized = ($campaign->translations ?? [])[$device->language] ?? [];

        return [
            'delivery_id' => $delivery->id, 'campaign_id' => $campaign->id, 'type' => $campaign->type,
            'title' => $localized['title'] ?? $campaign->title, 'body' => $localized['body'] ?? $campaign->body,
            'button_text' => $localized['button_text'] ?? $campaign->button_text, 'image_url' => $campaign->image_url,
            'action_url' => $campaign->action_url, 'dismissible' => $campaign->dismissible, 'allow_opt_out' => $campaign->allow_opt_out,
            'expires_at' => $delivery->expires_at->timestamp,
        ];
    }
}
