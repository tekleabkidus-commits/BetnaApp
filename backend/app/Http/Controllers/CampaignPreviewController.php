<?php

namespace App\Http\Controllers;

use App\Jobs\SendPush;
use App\Models\Campaign;
use App\Models\Delivery;
use App\Models\Installation;
use Illuminate\Http\RedirectResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Str;
use Illuminate\Validation\Rule;
use Illuminate\View\View;

class CampaignPreviewController extends Controller
{
    public function preview(Request $r, Campaign $campaign): View
    {
        $language = $r->validate(['language' => 'nullable|string|max:20'])['language'] ?? 'default';

        return view('admin.campaign-preview', ['campaign' => $campaign, 'language' => $language, 'localized' => ($campaign->translations ?? [])[$language] ?? [], 'testDevices' => Installation::where('test_device', true)->get()]);
    }

    public function send(Request $r, Campaign $campaign): RedirectResponse
    {
        $data = $r->validate(['installation_ids' => 'required|array|min:1|max:20', 'installation_ids.*' => ['required', 'uuid', Rule::exists('installations', 'id')->where('test_device', true)]]);
        $sent = 0;
        foreach (Installation::whereIn('id', $data['installation_ids'])->where('test_device', true)->get() as $device) {
            if (! $device->promotions_enabled || ($campaign->type === 'push' && (! $device->notifications_enabled || ! $device->push_token))) {
                continue;
            }
            $delivery = Delivery::create(['id' => (string) Str::uuid(), 'campaign_id' => $campaign->id, 'installation_id' => $device->id, 'is_test' => true, 'status' => $campaign->type === 'push' ? 'queued' : 'offered', 'expires_at' => now()->addMinutes(10)]);
            if ($campaign->type === 'push') {
                SendPush::dispatch($delivery->id);
            } $sent++;
        }
        DB::table('audit_logs')->insert(['user_id' => $r->user()->id, 'action' => 'campaign.test', 'subject' => (string) $campaign->id, 'details' => json_encode(['count' => $sent]), 'created_at' => now()]);

        return back()->with('status', "Test prepared for {$sent} devices. Open Betna within ten minutes to view a popup/banner. Device permissions still apply.");
    }
}
