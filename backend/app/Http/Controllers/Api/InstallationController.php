<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use App\Models\Campaign;
use App\Models\Delivery;
use App\Models\Installation;
use App\Services\CampaignEngine;
use App\Services\ConfigurationService;
use Carbon\Carbon;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Str;
use Illuminate\Validation\Rule;

class InstallationController extends Controller
{
    private const EVENTS = ['startup', 'app_crash', 'renderer_failed', 'ui_stall', 'app_open', 'app_updated', 'page_failed', 'page_loaded', 'dns_failed', 'tab_opened', 'tab_closed', 'tab_expired', 'retry', 'notification_received', 'notification_opened', 'notification_suppressed', 'popup_displayed', 'banner_displayed', 'campaign_clicked', 'campaign_dismissed', 'campaign_failed', 'update_prompted', 'update_clicked', 'update_downloaded', 'update_failed'];

    private function metadata(Request $r): array
    {
        return $r->validate(['version_code' => 'required|integer|min:1', 'version_name' => 'required|string|max:40', 'android_version' => 'required|integer|min:26|max:100', 'language' => 'required|string|max:20', 'notifications_enabled' => 'required|boolean', 'promotions_enabled' => 'sometimes|boolean', 'manufacturer' => 'sometimes|nullable|string|max:80', 'model' => 'sometimes|nullable|string|max:120', 'webview_version' => 'sometimes|nullable|string|max:80', 'push_available' => 'sometimes|boolean', 'low_ram' => 'sometimes|boolean', 'capabilities' => 'sometimes|array:proxy_override,safe_browsing,debugger_attached,root_signal', 'capabilities.debugger_attached' => 'sometimes|boolean', 'capabilities.root_signal' => 'sometimes|boolean', 'capabilities.proxy_override' => 'sometimes|boolean', 'capabilities.safe_browsing' => 'sometimes|boolean']);
    }

    public function register(Request $request): JsonResponse
    {
        $values = $this->metadata($request);
        $secret = Str::random(64);
        $device = Installation::create(array_merge($values, ['id' => (string) Str::uuid(), 'token_hash' => hash('sha256', $secret)]));

        return response()->json(['installation_id' => $device->id, 'token' => $device->id.'.'.$secret], 201);
    }

    public function heartbeat(Request $request): JsonResponse
    {
        $values = $this->metadata($request);
        $extra = $request->validate(['foreground' => 'required|boolean', 'push_token' => 'nullable|string|max:4096']);
        $device = $request->attributes->get('installation');
        $oldVersion = $device->version_code;
        $device->update(array_merge($values, $extra, ['last_seen_at' => now()]));
        if ($oldVersion !== $device->version_code) {
            DB::table('telemetry_events')->insert(['id' => (string) Str::uuid(), 'installation_id' => $device->id, 'type' => 'app_updated', 'version_code' => $device->version_code, 'occurred_at' => now(), 'created_at' => now()]);
        }

        return response()->json(['ok' => true, 'server_time' => now()->timestamp]);
    }

    public function configuration(Request $request, ConfigurationService $service): JsonResponse
    {
        return response()->json($service->envelope($request->attributes->get('installation')))->header('Cache-Control', 'private, no-store');
    }

    public function messages(Request $request, CampaignEngine $engine): JsonResponse
    {
        $data = $request->validate(['trigger' => ['required', Rule::in(['app_open', 'foreground', 'interval', 'first_open', 'updated'])], 'session_id' => 'required|uuid', 'session_trigger' => ['sometimes', Rule::in(['app_open', 'first_open', 'updated'])], 'elapsed_seconds' => 'required|integer|min:0|max:86400']);
        $device = $request->attributes->get('installation');
        $openingTriggers = ['app_open', 'first_open', 'updated'];
        $isOpening = in_array($data['trigger'], $openingTriggers, true);
        $window = (int) (ConfigurationService::selected($device)?->payload['campaigns']['opening_window_seconds'] ?? 5);
        if ($isOpening && ! $device->last_open_session_id) {
            $device->update(['last_open_session_id' => $data['session_id'], 'opening_started_at' => now()]);
        }
        $openingValid = $isOpening && $device->last_open_session_id === $data['session_id'] && $device->opening_started_at && $device->opening_started_at->copy()->addSeconds($window)->isFuture() && $data['elapsed_seconds'] <= $window;
        if ($isOpening && ! $openingValid) {
            $engine->log($device, null, $data['trigger'], 'opening_window_expired');

            return response()->json(['message' => null]);
        }
        if ($openingValid && $device->test_device && $device->promotions_enabled) {
            $test = Delivery::where('installation_id', $device->id)->where('is_test', true)->where('status', 'offered')->where('expires_at', '>', now())->oldest()->first();
            if ($test) {
                $campaign = Campaign::find($test->campaign_id);
                if ($campaign) {
                    return response()->json(['message' => $engine->content($campaign, $test, $device) + ['opening_only' => true, 'opening_deadline' => now()->addSeconds(max(0, $window - $data['elapsed_seconds']))->timestamp]]);
                }
            }
        }
        $triggers = $isOpening ? [$data['trigger'], 'app_open', 'any'] : [$data['trigger'], 'any'];
        foreach (Campaign::where('status', 'active')->whereIn('type', ['popup', 'banner'])->whereIn('trigger', $triggers)->orderByDesc('priority')->orderBy('id')->get() as $campaign) {
            if ($data['elapsed_seconds'] < $campaign->delay_seconds) {
                $engine->log($device, $campaign, $data['trigger'], 'delay_not_reached');

                continue;
            }
            if ($delivery = $engine->reserve($campaign, $device, $data['trigger'], $data['session_id'])) {
                $content = $engine->content($campaign, $delivery, $device);
                $content['opening_only'] = in_array($campaign->trigger, $openingTriggers, true);
                if ($content['opening_only']) {
                    $content['opening_deadline'] = $device->opening_started_at->copy()->addSeconds($window)->timestamp;
                }

                return response()->json(['message' => $content]);
            }
        }
        $engine->log($device, null, $data['trigger'], 'no_message');

        return response()->json(['message' => null]);
    }

    public function events(Request $request): JsonResponse
    {
        $data = $request->validate([
            'events' => 'required|array|max:100', 'events.*' => 'required|array:id,type,session_id,delivery_id,host,duration_ms,code,occurred_at', 'events.*.id' => 'required|uuid', 'events.*.type' => ['required', Rule::in(self::EVENTS)],
            'events.*.session_id' => 'nullable|uuid', 'events.*.delivery_id' => 'nullable|uuid',
            'events.*.host' => ['nullable', 'string', 'max:253', 'regex:/^[a-zA-Z0-9.-]+$/'], 'events.*.duration_ms' => 'nullable|integer|min:0|max:3600000',
            'events.*.code' => ['nullable', 'string', 'max:80', 'regex:/^[a-zA-Z0-9_-]+$/'],
            'events.*.occurred_at' => 'required|date|after:'.now()->subDays(7)->toIso8601String().'|before:'.now()->addMinutes(5)->toIso8601String(),
        ]);
        $device = $request->attributes->get('installation');
        DB::transaction(function () use ($data, $device): void {
            foreach ($data['events'] as $event) {
                $delivery = isset($event['delivery_id']) ? Delivery::whereKey($event['delivery_id'])->where('installation_id', $device->id)->first() : null;
                if (isset($event['delivery_id']) && ! $delivery) {
                    continue;
                }
                $inserted = DB::table('telemetry_events')->insertOrIgnore(array_merge($event, ['installation_id' => $device->id, 'version_code' => $device->version_code, 'occurred_at' => Carbon::parse($event['occurred_at'])->utc(), 'created_at' => now()]));
                if (! $inserted) {
                    continue;
                }
                if ($event['type'] === 'app_open') {
                    $device->increment('session_count');
                }
                if ($delivery) {
                    $field = match ($event['type']) {
                        'popup_displayed','banner_displayed' => 'displayed_at',
                        'campaign_clicked','notification_opened' => 'clicked_at',
                        'campaign_dismissed' => 'dismissed_at', default => null,
                    };
                    if ($field && ! $delivery->{$field}) {
                        $delivery->{$field} = now();
                    }
                    if (in_array($event['type'], ['popup_displayed', 'banner_displayed'], true)) {
                        $delivery->status = 'displayed';
                    }
                    if ($event['type'] === 'campaign_failed' && ! $delivery->displayed_at) {
                        $delivery->status = 'failed';
                        $delivery->failure_code = $event['code'] ?? 'display_failed';
                    }
                    $delivery->save();
                }
            }
        });

        return response()->json(['ok' => true]);
    }

    public function optOut(Request $request): JsonResponse
    {
        $data = $request->validate(['delivery_id' => 'required|uuid']);
        $device = $request->attributes->get('installation');
        $delivery = Delivery::whereKey($data['delivery_id'])->where('installation_id', $device->id)->firstOrFail();
        abort_unless(Campaign::findOrFail($delivery->campaign_id)->allow_opt_out, 422);
        DB::table('campaign_opt_outs')->insertOrIgnore(['campaign_id' => $delivery->campaign_id, 'installation_id' => $device->id, 'created_at' => now(), 'updated_at' => now()]);

        return response()->json(['ok' => true]);
    }
}
