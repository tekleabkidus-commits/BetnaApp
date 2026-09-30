<?php

namespace App\Jobs;

use App\Models\Campaign;
use App\Models\Delivery;
use App\Models\Installation;
use App\Services\CampaignEngine;
use App\Services\FirebaseMessaging;
use Illuminate\Contracts\Queue\ShouldBeUnique;
use Illuminate\Contracts\Queue\ShouldQueue;
use Illuminate\Foundation\Queue\Queueable;

class SendPush implements ShouldBeUnique, ShouldQueue
{
    use Queueable;

    public int $tries = 5;

    public int $timeout = 45;

    public int $uniqueFor = 3600;

    public function __construct(public string $deliveryId) {}

    public function uniqueId(): string
    {
        return $this->deliveryId;
    }

    public function backoff(): array
    {
        return [30, 120, 300, 600];
    }

    public function handle(FirebaseMessaging $firebase, CampaignEngine $engine): void
    {
        $delivery = Delivery::find($this->deliveryId);
        if (! $delivery || $delivery->status !== 'queued') {
            return;
        }
        $campaign = Campaign::find($delivery->campaign_id);
        $device = Installation::find($delivery->installation_id);
        if (! $campaign || ! $device || (! $delivery->is_test && $campaign->status !== 'active') || $delivery->expires_at->isPast() || ! $device->push_token || ! $device->promotions_enabled || ! $device->notifications_enabled || ($delivery->is_test ? ! $device->test_device : ! $engine->audienceMatches($campaign, $device))) {
            $delivery->update(['status' => 'cancelled', 'failure_code' => 'no_longer_eligible']);

            return;
        }
        $result = $firebase->send($device->push_token, $engine->content($campaign, $delivery, $device));
        if ($result === 'unregistered') {
            $device->update(['push_token' => null]);
            $delivery->update(['status' => 'failed', 'failure_code' => 'unregistered']);

            return;
        }
        $delivery->update(['status' => 'accepted', 'sent_at' => now()]);
    }

    public function failed(?\Throwable $exception): void
    {
        Delivery::whereKey($this->deliveryId)->where('status', 'queued')->update(['status' => 'failed', 'failure_code' => 'push_retries_exhausted']);
    }
}
