<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;
use Illuminate\Validation\Rule;

class DiagnosticsController extends Controller
{
    public function ping(): JsonResponse
    {
        return response()->json(['ok' => true])->header('Cache-Control', 'no-store');
    }

    public function report(Request $r): JsonResponse
    {
        $data = $r->validate(['id' => 'required|uuid', 'network' => 'required|in:mobile,wifi,ethernet,other,offline', 'checks' => 'required|array|min:1|max:8', 'checks.*' => 'array:name,status,duration_ms,http_status,code', 'checks.*.name' => ['required', Rule::in(['api', 'dns', 'website', 'webview', 'push'])], 'checks.*.status' => 'required|in:ok,failed,unavailable', 'checks.*.duration_ms' => 'nullable|integer|min:0|max:120000', 'checks.*.http_status' => 'nullable|integer|min:100|max:599', 'checks.*.code' => 'nullable|string|max:40|regex:/^[A-Z0-9_-]+$/']);
        DB::table('connection_reports')->insertOrIgnore(['id' => $data['id'], 'installation_id' => $r->attributes->get('installation')->id, 'network' => $data['network'], 'checks' => json_encode($data['checks']), 'created_at' => now()]);

        return response()->json(['reference' => $data['id']]);
    }

    public function opening(Request $r): JsonResponse
    {
        $data = $r->validate(['session_id' => 'required|uuid']);
        $device = $r->attributes->get('installation');
        DB::transaction(function () use ($device, $data): void {
            $locked = $device->newQuery()->whereKey($device->id)->lockForUpdate()->firstOrFail();
            if ($locked->last_open_session_id !== $data['session_id']) {
                $locked->update(['last_open_session_id' => $data['session_id'], 'opening_started_at' => now()]);
            }
        });

        return response()->json(['ok' => true]);
    }
}
