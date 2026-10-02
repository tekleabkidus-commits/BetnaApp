<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use App\Services\ConfigurationService;
use Carbon\Carbon;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;
use Illuminate\Validation\Rule;

class BrowserControlController extends Controller
{
    public function location(Request $r): JsonResponse
    {
        $d = $r->validate(['id' => 'required|uuid', 'latitude' => 'required|numeric|between:-90,90', 'longitude' => 'required|numeric|between:-180,180', 'accuracy_m' => 'required|numeric|between:0,1000000', 'precision' => ['required', Rule::in(['approximate', 'precise'])], 'observed_at' => 'required|date|after:'.now()->subDay()->toIso8601String().'|before:'.now()->addMinutes(5)->toIso8601String()]);
        $device = $r->attributes->get('installation');
        $settings = array_replace(ConfigurationService::defaults(), ConfigurationService::selected($device)?->payload ?? []);
        abort_unless($settings['location']['enabled'] ?? true, 403);
        $latest = DB::table('device_locations')->where('installation_id', $device->id)->latest('created_at')->first();
        if ($latest && now()->diffInSeconds($latest->created_at, true) < 60) {
            return response()->json(['ok' => true, 'throttled' => true]);
        }
        $d['observed_at'] = Carbon::parse($d['observed_at'])->utc();
        DB::table('device_locations')->insertOrIgnore($d + ['installation_id' => $device->id, 'created_at' => now()]);

        return response()->json(['ok' => true]);
    }

    public function acknowledge(Request $r): JsonResponse
    {
        $d = $r->validate(['id' => 'required|uuid', 'status' => ['required', Rule::in(['completed', 'failed'])], 'failure_code' => 'nullable|regex:/^[A-Z0-9_]{1,80}$/']);
        $device = $r->attributes->get('installation');
        $command = DB::table('device_commands')->where('id', $d['id'])->where('installation_id', $device->id)->first();
        abort_unless($command, 404);
        DB::table('device_commands')->where('id', $d['id'])->where('status', 'pending')->update(['status' => $d['status'], 'failure_code' => $d['failure_code'] ?? null, 'completed_at' => now(), 'updated_at' => now()]);

        return response()->json(['ok' => true]);
    }

    public function peer(Request $r): JsonResponse
    {
        $d = $r->validate(['public_key' => 'required|string|size:44']);
        abort_unless(strlen((string) base64_decode($d['public_key'], true)) === 32, 422);
        $device = $r->attributes->get('installation');
        DB::transaction(function () use ($device, $d): void {
            $device->newQuery()->whereKey($device->id)->lockForUpdate()->firstOrFail();
            $old = DB::table('vpn_peers')->where('installation_id', $device->id)->first();
            if ($old) {
                abort_unless(hash_equals($old->public_key, $d['public_key']), 409);

                return;
            }
            $id = DB::table('vpn_peers')->insertGetId(['installation_id' => $device->id, 'public_key' => $d['public_key'], 'created_at' => now(), 'updated_at' => now()]);
            abort_if($id > 65533, 503);
        });

        return response()->json(['ok' => true]);
    }
}
