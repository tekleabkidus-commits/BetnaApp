<?php

namespace App\Console\Commands;

use App\Jobs\SendPush;
use App\Models\Campaign;
use App\Models\Delivery;
use App\Models\Installation;
use App\Services\CampaignEngine;
use Illuminate\Console\Command;
use Illuminate\Support\Facades\DB;

class DispatchCampaigns extends Command
{
    protected $signature = 'campaigns:dispatch';

    protected $description = 'Create durable campaign runs and dispatch queued push deliveries';

    public function handle(CampaignEngine $engine): int
    {
        Campaign::where('type', 'push')->where('status', 'active')->where('next_run_at', '<=', now())->orderBy('id')->each(function (Campaign $campaign): void {
            DB::transaction(function () use ($campaign): void {
                $campaign = Campaign::whereKey($campaign->id)->lockForUpdate()->firstOrFail();
                if (! $campaign->next_run_at || $campaign->next_run_at->isFuture()) {
                    return;
                }
                DB::table('campaign_runs')->insertOrIgnore(['campaign_id' => $campaign->id, 'scheduled_at' => $campaign->next_run_at, 'status' => 'building', 'created_at' => now(), 'updated_at' => now()]);
                $next = $campaign->repeat_minutes ? $campaign->next_run_at->copy()->addMinutes($campaign->repeat_minutes) : null;
                if ($next && $next->isPast()) {
                    $next = now()->addMinutes($campaign->repeat_minutes);
                }
                $campaign->update(['next_run_at' => $next]);
            });
        });
        foreach (DB::table('campaign_runs')->where('status', 'building')->orderBy('id')->get() as $run) {
            $campaign = Campaign::find($run->campaign_id);
            if (! $campaign || $campaign->status !== 'active') {
                DB::table('campaign_runs')->where('id', $run->id)->update(['status' => 'cancelled']);

                continue;
            }
            Installation::whereNotNull('push_token')->orderBy('id')->chunkById(200, function ($devices) use ($campaign, $run, $engine): void {
                foreach ($devices as $device) {
                    $engine->reserve($campaign, $device, 'schedule', null, $run->id);
                }
            });
            DB::table('campaign_runs')->where('id', $run->id)->update(['status' => 'ready', 'updated_at' => now()]);
        }
        Delivery::where('status', 'queued')->where('expires_at', '>', now())->orderBy('id')->chunkById(200, function ($deliveries): void {
            foreach ($deliveries as $delivery) {
                SendPush::dispatch($delivery->id);
            }
        });
        Delivery::whereIn('status', ['queued', 'offered'])->where('expires_at', '<=', now())->update(['status' => 'expired']);

        return self::SUCCESS;
    }
}
