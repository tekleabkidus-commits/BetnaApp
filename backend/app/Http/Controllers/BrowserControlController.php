<?php

namespace App\Http\Controllers;

use App\Models\AppConfiguration;
use App\Models\Device;
use App\Models\Installation;
use App\Services\ConfigurationService;
use Carbon\Carbon;
use Illuminate\Database\Query\Builder;
use Illuminate\Http\RedirectResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Str;
use Illuminate\Validation\Rule;
use Illuminate\View\View;
use Symfony\Component\HttpFoundation\StreamedResponse;

class BrowserControlController extends Controller
{
    private function audit(string $action, ?string $subject = null): void
    {
        DB::table('audit_logs')->insert(['user_id' => auth()->id(), 'action' => $action, 'subject' => $subject, 'created_at' => now()]);
    }

    private function points(Request $r): Builder
    {
        $d = $r->validate(['version' => 'nullable|integer|min:1', 'android' => 'nullable|integer|min:26', 'model' => 'nullable|string|max:120', 'online' => 'nullable|in:1', 'from' => 'nullable|date', 'to' => 'nullable|date|after_or_equal:from', 'vpn' => 'nullable|in:off,connecting,connected,failed,permission_required']);
        $query = DB::table('device_locations as l')->join('installations as i', 'i.id', '=', 'l.installation_id')->whereRaw('NOT EXISTS (SELECT 1 FROM device_locations newer WHERE newer.installation_id = l.installation_id AND (newer.observed_at > l.observed_at OR (newer.observed_at = l.observed_at AND newer.id > l.id)))')
            ->when($d['version'] ?? null, fn ($q, $v) => $q->where('i.version_code', $v))->when($d['android'] ?? null, fn ($q, $v) => $q->where('i.android_version', $v))->when($d['model'] ?? null, fn ($q, $v) => $q->where('i.model', $v))->when($d['vpn'] ?? null, fn ($q, $v) => $q->where('i.vpn_status', $v));
        if ($d['online'] ?? false) {
            $query->where('i.foreground', true)->where('i.last_seen_at', '>=', now()->subSeconds(90));
        }
        if ($d['from'] ?? null) {
            $query->where('l.observed_at', '>=', Carbon::parse($d['from'], 'Africa/Addis_Ababa')->startOfDay()->utc());
        }
        if ($d['to'] ?? null) {
            $query->where('l.observed_at', '<=', Carbon::parse($d['to'], 'Africa/Addis_Ababa')->endOfDay()->utc());
        }

        return $query->select('l.*', 'i.label', 'i.model', 'i.version_name', 'i.last_seen_at', 'i.foreground', 'i.vpn_status');
    }

    public function locations(Request $r): View
    {
        $this->audit('locations.viewed');
        $query = $this->points($r);

        return view('admin.locations', ['points' => (clone $query)->orderByDesc('l.observed_at')->limit(5000)->get(), 'count' => $query->count(), 'total' => Installation::count()]);
    }

    public function export(Request $r): StreamedResponse
    {
        $query = $this->points($r);
        $this->audit('locations.exported');

        return response()->streamDownload(function () use ($query): void {
            $out = fopen('php://output', 'w');
            fputcsv($out, ['Installation', 'Latitude', 'Longitude', 'Accuracy metres', 'Precision', 'Observed UTC', 'Version', 'VPN']);
            foreach ($query->orderBy('l.id')->cursor() as $p) {
                fputcsv($out, [$p->installation_id, $p->latitude, $p->longitude, $p->accuracy_m, $p->precision, $p->observed_at, $p->version_name, $p->vpn_status]);
            }fclose($out);
        }, 'betna-locations.csv', ['Content-Type' => 'text/csv']);
    }

    public function device(Installation $installation): View
    {
        $this->audit('device.report.viewed', $installation->id);
        $installation->load(['deviceIdentity' => fn ($query) => $query->withCount('installations')]);

        return view('admin.device', ['device' => $installation, 'locations' => DB::table('device_locations')->where('installation_id', $installation->id)->latest('observed_at')->limit(100)->get(), 'events' => DB::table('telemetry_events')->where('installation_id', $installation->id)->latest('occurred_at')->paginate(50), 'commands' => DB::table('device_commands')->where('installation_id', $installation->id)->latest()->limit(30)->get()]);
    }

    public function deviceHistory(Device $device): View
    {
        $this->audit('device.history.viewed', $device->id);
        $device->loadCount('installations')->loadMin('installations', 'created_at')->loadMax('installations', 'last_seen_at');

        return view('admin.device-history', ['identity' => $device, 'installations' => $device->installations()->latest()->orderByDesc('id')->paginate(30), 'sessions' => $device->installations()->sum('session_count')]);
    }

    public function cache(): View
    {
        return view('admin.cache', ['commands' => DB::table('device_commands')->join('installations', 'installations.id', '=', 'device_commands.installation_id')->select('device_commands.*', 'installations.label')->latest('device_commands.created_at')->paginate(50)]);
    }

    public function clearCache(Request $r): RedirectResponse
    {
        $d = $r->validate(['target' => 'required|in:all,test,device,version,model', 'device' => 'nullable|required_if:target,device|uuid|exists:installations,id', 'version' => 'nullable|required_if:target,version|integer|min:1', 'model' => 'nullable|required_if:target,model|string|max:120', 'timing' => 'required|in:immediate,next_open']);
        $query = Installation::query();
        if ($d['target'] === 'test') {
            $query->where('test_device', true);
        } elseif ($d['target'] === 'device') {
            $query->whereKey($d['device']);
        } elseif ($d['target'] === 'version') {
            $query->where('version_code', $d['version']);
        } elseif ($d['target'] === 'model') {
            $query->where('model', $d['model']);
        }
        $count = 0;
        foreach ($query->cursor() as $device) {
            DB::table('device_commands')->insert(['id' => (string) Str::uuid(), 'installation_id' => $device->id, 'type' => 'clear_cache', 'timing' => $d['timing'], 'status' => 'pending', 'created_by' => auth()->id(), 'expires_at' => now()->addDays(7), 'created_at' => now(), 'updated_at' => now()]);
            $count++;
        }
        $this->audit('cache.clear.requested');

        return back()->with('status', "Cache command queued for {$count} installations. Login cookies and passwords are preserved.");
    }

    public function vpn(): View
    {
        $configuration = array_replace(ConfigurationService::defaults(), ConfigurationService::selected()?->payload ?? []);
        $usage = DB::table('vpn_usage_days')->where('day', '>=', now()->startOfMonth()->toDateString());
        $monthRx = (clone $usage)->sum('rx_bytes');
        $monthTx = (clone $usage)->sum('tx_bytes');
        $gib = ($monthRx + $monthTx) / 1073741824;
        $cost = ($configuration['vpn']['base_cost_usd'] ?? 96) + max(0, $gib - ($configuration['vpn']['included_gib'] ?? 10000)) * ($configuration['vpn']['excess_usd_per_gib'] ?? .01);

        return view('admin.vpn', ['configuration' => $configuration, 'peers' => DB::table('vpn_peers')->join('installations', 'installations.id', '=', 'vpn_peers.installation_id')->select('vpn_peers.*', 'installations.label', 'installations.vpn_status')->orderBy('vpn_peers.id')->paginate(100), 'connected' => Installation::where('vpn_status', 'connected')->where('last_seen_at', '>=', now()->subSeconds(90))->count(), 'rx' => $monthRx, 'tx' => $monthTx, 'estimatedCost' => $cost, 'budgetAlert' => $cost >= .8 * ($configuration['vpn']['budget_usd'] ?? 200)]);
    }

    public function saveVpn(Request $r): RedirectResponse
    {
        $r->validate(['servers' => 'nullable|array|max:3']);
        $r->merge(['servers' => array_values(array_filter($r->input('servers', []) ?? [], fn ($s) => ! is_array($s) || ! empty($s['endpoint']) || ! empty($s['public_key'])))]);
        $d = $r->validate(['included_gib' => 'sometimes|numeric|min:0|max:10000000', 'base_cost_usd' => 'sometimes|numeric|min:0|max:1000000', 'excess_usd_per_gib' => 'sometimes|numeric|min:0|max:100', 'budget_usd' => 'sometimes|numeric|min:1|max:1000000', 'scope' => 'sometimes|in:all,test', 'installation_ids' => 'required_if:scope,test|array|max:100', 'installation_ids.*' => ['uuid', Rule::exists('installations', 'id')->where('test_device', true)], 'enabled' => 'required|boolean', 'apply' => 'required|in:next_open,immediate', 'servers' => 'nullable|array|max:3', 'servers.*.name' => 'required_with:servers|string|max:60', 'servers.*.endpoint' => ['required_with:servers', 'regex:/^(?:[a-zA-Z0-9.-]+|\[[a-fA-F0-9:]+\]):[0-9]{1,5}$/'], 'servers.*.public_key' => 'required_with:servers|string|size:44']);
        $servers = array_values(array_filter($d['servers'] ?? [], fn ($s) => ! empty($s['endpoint'])));
        foreach ($servers as $s) {
            abort_unless(strlen((string) base64_decode($s['public_key'], true)) === 32, 422);
            $port = (int) substr($s['endpoint'], strrpos($s['endpoint'], ':') + 1);
            abort_unless($port > 0 && $port <= 65535, 422);
        }
        if ($d['enabled'] && ! count($servers)) {
            return back()->withErrors(['servers' => 'Add at least one configured WireGuard server before enabling VPN.']);
        }
        $payload = array_replace(ConfigurationService::defaults(), ConfigurationService::selected()?->payload ?? []);
        $payload['vpn'] = array_merge(ConfigurationService::defaults()['vpn'], array_intersect_key($d, array_flip(['included_gib', 'base_cost_usd', 'excess_usd_per_gib', 'budget_usd'])), ['enabled' => (bool) $d['enabled'], 'apply' => $d['apply'], 'servers' => $servers]);
        if (($d['scope'] ?? 'all') === 'test' && empty($d['installation_ids'])) {
            return back()->withErrors(['installation_ids' => 'Select at least one test installation.']);
        }
        $revision = AppConfiguration::create(['payload' => $payload, 'published' => true, 'scope' => $d['scope'] ?? 'all', 'installation_ids' => ($d['scope'] ?? 'all') === 'test' ? ($d['installation_ids'] ?? []) : [], 'created_by' => auth()->id()]);
        $this->audit('vpn.configuration.published', (string) $revision->id);

        return back()->with('status', 'VPN policy published. Required mode blocks website access until the tunnel is verified.');
    }

    public function provisionPeer(Request $r, int $id): RedirectResponse
    {
        $d = $r->validate(['provisioned' => 'required|boolean']);
        abort_unless(DB::table('vpn_peers')->where('id', $id)->exists(), 404);
        DB::table('vpn_peers')->where('id', $id)->update(['provisioned' => (bool) $d['provisioned'], 'updated_at' => now()]);
        $this->audit('vpn.peer.provisioned', (string) $id);

        return back();
    }
}
