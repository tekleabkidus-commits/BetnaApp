<?php

namespace App\Http\Controllers;

use App\Models\AppConfiguration;
use App\Models\Campaign;
use App\Models\Delivery;
use App\Models\Device;
use App\Models\Installation;
use App\Models\Release;
use App\Models\User;
use App\Services\CampaignEngine;
use App\Services\ConfigurationService;
use Carbon\Carbon;
use Illuminate\Database\Query\Builder;
use Illuminate\Http\RedirectResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Auth;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Storage;
use Illuminate\Support\Facades\Validator;
use Illuminate\Support\Str;
use Illuminate\Validation\Rule;
use Illuminate\Validation\ValidationException;
use Illuminate\View\View;
use Symfony\Component\HttpFoundation\StreamedResponse;

class AdminController extends Controller
{
    public function loginForm(): View
    {
        return view('admin.login');
    }

    public function login(Request $r): RedirectResponse
    {
        $data = $r->validate(['email' => 'required|email', 'password' => 'required|string']);
        if (! Auth::attempt($data)) {
            throw ValidationException::withMessages(['email' => 'The login details were not accepted.']);
        }
        $r->session()->regenerate();
        $r->session()->forget('two_factor_verified');
        $r->session()->put('two_factor_started', time());
        $this->audit('login');

        return redirect()->route('admin.dashboard');
    }

    public function logout(Request $r): RedirectResponse
    {
        Auth::logout();
        $r->session()->invalidate();
        $r->session()->regenerateToken();

        return redirect()->route('login');
    }

    private function audit(string $action, ?string $subject = null, array $details = []): void
    {
        DB::table('audit_logs')->insert(['user_id' => Auth::id(), 'action' => $action, 'subject' => $subject, 'details' => json_encode($details), 'created_at' => now()]);
    }

    private function filteredEvents(Request $r): Builder
    {
        $data = $r->validate(['from' => 'nullable|date', 'to' => 'nullable|date|after_or_equal:from', 'version' => 'nullable|integer|min:1', 'language' => 'nullable|string|max:20', 'manufacturer' => 'nullable|string|max:80', 'model' => 'nullable|string|max:120', 'android' => 'nullable|integer|min:26|max:100']);

        return DB::table('telemetry_events as e')->join('installations as i', 'i.id', '=', 'e.installation_id')
            ->where('e.created_at', '>=', isset($data['from']) ? Carbon::parse($data['from'], 'Africa/Addis_Ababa')->startOfDay()->utc() : now()->subDays(30))
            ->when(isset($data['to']), fn ($q) => $q->where('e.created_at', '<=', Carbon::parse($data['to'], 'Africa/Addis_Ababa')->endOfDay()->utc()))
            ->when($data['version'] ?? null, fn ($q, $value) => $q->where('e.version_code', $value))
            ->when($data['language'] ?? null, fn ($q, $value) => $q->where('i.language', $value))->when($data['manufacturer'] ?? null, fn ($q, $v) => $q->where('i.manufacturer', $v))->when($data['model'] ?? null, fn ($q, $v) => $q->where('i.model', $v))->when($data['android'] ?? null, fn ($q, $v) => $q->where('i.android_version', $v));
    }

    public function dashboard(Request $r): View
    {
        $events = $this->filteredEvents($r);

        return view('admin.dashboard', [
            'identityStats' => $this->identityStats(),
            'compatibility' => Installation::select('manufacturer', 'model', 'android_version', 'webview_version', DB::raw('count(*) as total'))->groupBy('manufacturer', 'model', 'android_version', 'webview_version')->orderByDesc('total')->limit(30)->get(), 'retention' => $this->retention(), 'total' => Installation::count(), 'online' => Installation::where('foreground', true)->where('last_seen_at', '>=', now()->subSeconds(config('mobile.online_seconds')))->count(),
            'daily' => Installation::where('last_seen_at', '>=', now()->subDay())->count(), 'weekly' => Installation::where('last_seen_at', '>=', now()->subDays(7))->count(),
            'monthly' => Installation::where('last_seen_at', '>=', now()->subDays(30))->count(),
            'versions' => Installation::select('version_code', 'version_name', DB::raw('count(*) as total'))->groupBy('version_code', 'version_name')->orderByDesc('version_code')->get(),
            'eventCounts' => (clone $events)->select('e.type', DB::raw('count(*) as total'), DB::raw('count(distinct e.installation_id) as unique_installations'))->groupBy('e.type')->orderByDesc('total')->get(),
            'eligibility' => DB::table('eligibility_checks')->select('reason', DB::raw('count(*) as total'))->where('created_at', '>=', now()->subDays(7))->groupBy('reason')->get(),
            'dailyActivity' => (clone $events)->where('e.type', 'app_open')->selectRaw('DATE(e.created_at) as day, COUNT(DISTINCT e.installation_id) as total')->groupByRaw('DATE(e.created_at)')->orderByDesc('day')->limit(30)->get(),
            'deliveryCounts' => DB::table('deliveries')->select('status', DB::raw('count(*) as total'))->groupBy('status')->get(),
        ]);
    }

    public function export(Request $r): StreamedResponse
    {
        $query = $this->filteredEvents($r)->select('e.id', 'e.installation_id', 'e.type', 'e.version_code', 'e.host', 'e.code', 'e.duration_ms', 'e.occurred_at', 'e.created_at')->orderBy('e.created_at');
        $this->audit('export.events');

        return response()->streamDownload(function () use ($query): void {
            $out = fopen('php://output', 'w');
            fputcsv($out, ['Event ID', 'Installation ID', 'Type', 'Version', 'Host', 'Code', 'Duration ms', 'Occurred UTC', 'Received UTC'], ',', '"', '');
            foreach ($query->cursor() as $row) {
                fputcsv($out, array_map(fn ($v) => is_string($v) && preg_match('/^[=+@-]/', $v) ? "'".$v : $v, (array) $row), ',', '"', '');
            }
            fclose($out);
        }, 'app-events.csv', ['Content-Type' => 'text/csv']);
    }

    public function installations(Request $request): View
    {
        $filters = $request->validate([
            'q' => 'nullable|string|max:120',
            'version' => 'nullable|integer|min:1',
            'test' => 'nullable|in:0,1',
            'online' => 'nullable|in:1',
            'recognized' => 'nullable|in:linked,repeat,unlinked',
        ]);
        $devices = Installation::query();
        if ($search = trim($filters['q'] ?? '')) {
            $devices->where(function ($query) use ($search): void {
                $query->whereLike('label', '%'.$search.'%')
                    ->orWhereLike('model', '%'.$search.'%')
                    ->orWhereLike('manufacturer', '%'.$search.'%');
                if (Str::isUuid($search)) {
                    $query->orWhere('id', $search)->orWhere('device_id', $search);
                }
            });
        }
        if ($filters['version'] ?? null) {
            $devices->where('version_code', $filters['version']);
        }
        if (isset($filters['test'])) {
            $devices->where('test_device', $filters['test'] === '1');
        }
        if ($filters['online'] ?? false) {
            $devices->where('foreground', true)->where('last_seen_at', '>=', now()->subSeconds(config('mobile.online_seconds')));
        }
        if (($filters['recognized'] ?? null) === 'linked') {
            $devices->whereNotNull('device_id');
        } elseif (($filters['recognized'] ?? null) === 'unlinked') {
            $devices->whereNull('device_id');
        } elseif (($filters['recognized'] ?? null) === 'repeat') {
            $devices->whereHas('deviceIdentity', fn ($query) => $query->has('installations', '>', 1));
        }

        return view('admin.installations', ['identityStats' => $this->identityStats(), 'devices' => $devices->with(['deviceIdentity' => fn ($query) => $query->withCount('installations')])->orderByDesc('last_seen_at')->paginate(30)->withQueryString()]);
    }

    /** @return array{devices: int, repeats: int, unlinked: int} */
    private function identityStats(): array
    {
        $recognized = Device::has('installations')->count();

        return ['devices' => $recognized, 'repeats' => Installation::whereNotNull('device_id')->count() - $recognized, 'unlinked' => Installation::whereNull('device_id')->count()];
    }

    public function updateInstallation(Request $r, Installation $installation): RedirectResponse
    {
        $data = $r->validate(['label' => 'nullable|string|max:80', 'test_device' => 'required|boolean']);
        $installation->update($data);
        $this->audit('installation.updated', $installation->id, $data);

        return back()->with('status', 'Installation updated.');
    }

    public function campaigns(): View
    {
        return view('admin.campaigns', ['campaigns' => Campaign::latest()->paginate(30)]);
    }

    public function campaignForm(?Campaign $campaign = null): View
    {
        return view('admin.campaign-form', ['campaign' => $campaign ?? new Campaign, 'testDevices' => Installation::where('test_device', true)->get()]);
    }

    private function jsonField(Request $r, string $key, array $fallback = []): array
    {
        try {
            $value = json_decode($r->input($key) ?: json_encode($fallback), true, 64, JSON_THROW_ON_ERROR);
            if (! is_array($value)) {
                throw new \RuntimeException;
            }

            return $value;
        } catch (\Throwable) {
            throw ValidationException::withMessages([$key => 'Enter a valid JSON object.']);
        }
    }

    public function saveCampaign(Request $r, ?Campaign $campaign = null): RedirectResponse
    {
        $data = $r->validate([
            'name' => 'required|string|max:120', 'type' => ['required', Rule::in(['push', 'popup', 'banner'])], 'status' => ['required', Rule::in(['draft', 'active', 'paused', 'completed', 'cancelled'])],
            'delivery_scope' => 'required|in:all,test', 'test_installation_ids' => 'required_if:delivery_scope,test|array|max:100', 'test_installation_ids.*' => ['uuid', Rule::exists('installations', 'id')->where('test_device', true)],
            'trigger' => ['required', Rule::in(['any', 'app_open', 'foreground', 'interval', 'first_open', 'updated', 'schedule'])],
            'title' => 'required|string|max:150', 'body' => 'required|string|max:3000', 'image_url' => ['nullable', 'url:https', 'max:2048'], 'action_url' => ['nullable', 'url:https,http', 'max:2048'],
            'button_text' => 'required|string|max:40', 'timezone' => 'required|timezone', 'starts_at' => 'nullable|date', 'ends_at' => 'nullable|date|after:starts_at',
            'repeat_minutes' => 'nullable|integer|min:15|max:525600', 'priority' => 'required|integer|min:0|max:1000',
            'max_per_installation' => 'required|integer|min:0|max:10000', 'max_per_day' => 'required|integer|min:1|max:100', 'cooldown_minutes' => 'required|integer|min:0|max:525600',
            'delay_seconds' => 'required|integer|min:0|max:3600', 'once_per_session' => 'required|boolean', 'dismissible' => 'required|boolean', 'allow_opt_out' => 'required|boolean',
            'quiet_start' => 'nullable|date_format:H:i', 'quiet_end' => 'nullable|date_format:H:i|required_with:quiet_start',
        ]);
        if ($data['delivery_scope'] === 'test' && empty($data['test_installation_ids'])) {
            throw ValidationException::withMessages(['test_installation_ids' => 'Choose at least one test device.']);
        }
        $data['test_installation_ids'] = $data['delivery_scope'] === 'test' ? $data['test_installation_ids'] : [];
        $data['audience'] = $this->jsonField($r, 'audience', ['mode' => 'all', 'percentage' => 100, 'rules' => []]);
        Validator::make($data['audience'], [
            'mode' => ['required', Rule::in(['all', 'any'])], 'percentage' => 'required|integer|min:0|max:100', 'exclude_installation_ids' => 'sometimes|array', 'exclude_installation_ids.*' => 'uuid',
            'rules' => 'present|array|max:30', 'rules.*.field' => ['required', Rule::in(['manufacturer', 'model', 'webview_version', 'push_available', 'low_ram', 'version_code', 'android_version', 'language', 'notifications_enabled', 'promotions_enabled', 'session_count', 'sessions_7d', 'test_device', 'installation_id', 'age_days', 'inactive_days', 'displayed_campaign', 'clicked_campaign'])],
            'rules.*.op' => ['required', Rule::in(['eq', 'ne', 'gte', 'lte', 'in', 'not_in'])], 'rules.*.value' => 'present',
        ])->validate();
        $data['translations'] = $this->jsonField($r, 'translations');
        Validator::make($data['translations'], ['*' => 'array', '*.title' => 'sometimes|string|max:150', '*.body' => 'sometimes|string|max:3000', '*.button_text' => 'sometimes|string|max:40'])->validate();
        foreach (['starts_at', 'ends_at'] as $key) {
            $data[$key] = empty($data[$key]) ? null : Carbon::parse($data[$key], $data['timezone'])->utc();
        }
        if (! $data['dismissible']) {
            throw ValidationException::withMessages(['dismissible' => 'Marketing messages must remain dismissible. Use the release policy for required updates.']);
        }
        if ($data['type'] === 'push') {
            $data['trigger'] = 'schedule';
        }
        $campaign ??= new Campaign;
        if ($data['type'] === 'push' && $data['status'] === 'active' && (! $campaign->exists || $campaign->status !== 'active')) {
            $data['next_run_at'] = $data['starts_at'] ?? now();
        }
        $campaign->fill($data)->save();
        $this->audit('campaign.saved', (string) $campaign->id, ['status' => $campaign->status]);

        return redirect()->route('admin.campaigns')->with('status', 'Campaign saved.');
    }

    public function campaignAction(Request $r, Campaign $campaign, CampaignEngine $engine): RedirectResponse
    {
        $action = $r->validate(['action' => ['required', Rule::in(['pause', 'activate', 'duplicate', 'estimate', 'send-now', 'promote'])]])['action'];
        if ($action === 'estimate') {
            $count = 0;
            Installation::chunk(200, function ($items) use ($engine, $campaign, &$count): void {
                foreach ($items as $device) {
                    if ($engine->audienceMatches($campaign, $device)) {
                        $count++;
                    }
                }
            });

            return back()->with('status', "Audience matches {$count} installations. Delivery permissions and frequency caps may reduce this.");
        }
        if ($action === 'promote') {
            $campaign->update(['delivery_scope' => 'all', 'test_installation_ids' => [], 'status' => 'active', 'next_run_at' => $campaign->type === 'push' ? ($campaign->starts_at?->isFuture() ? $campaign->starts_at : now()) : null]);
        } elseif ($action === 'duplicate') {
            $copy = $campaign->replicate();
            $copy->name = mb_substr($campaign->name.' (copy)', 0, 120);
            $copy->status = 'draft';
            $copy->next_run_at = null;
            $copy->save();
        } elseif ($action === 'send-now') {
            abort_unless($campaign->type === 'push', 422);
            $campaign->update(['status' => 'active', 'starts_at' => now(), 'next_run_at' => now()]);
        } else {
            $changes = ['status' => $action === 'pause' ? 'paused' : 'active'];
            if ($action === 'activate' && $campaign->type === 'push') {
                $changes['next_run_at'] = $campaign->starts_at?->isFuture() ? $campaign->starts_at : now();
            } $campaign->update($changes);
        }
        $this->audit('campaign.'.$action, (string) $campaign->id);

        return back()->with('status', 'Campaign updated.');
    }

    public function configuration(): View
    {
        return view('admin.configuration', ['configuration' => array_replace(ConfigurationService::defaults(), ConfigurationService::selected()?->payload ?? []), 'history' => AppConfiguration::latest()->limit(10)->get(), 'testDevices' => Installation::where('test_device', true)->get()]);
    }

    public function saveConfiguration(Request $r): RedirectResponse
    {
        $data = $this->jsonField($r, 'payload');
        Validator::make($data, [
            'location.enabled' => 'sometimes|boolean', 'location.interval_seconds' => 'sometimes|integer|min:300|max:86400', 'location.retention_days' => 'sometimes|integer|min:7|max:365', 'cache.mode' => 'sometimes|in:standard',
            'maintenance.enabled' => 'sometimes|boolean', 'maintenance.blocking' => 'sometimes|boolean', 'maintenance.title' => 'nullable|string|max:120', 'maintenance.message' => 'nullable|string|max:2000',
            'website_url' => 'required|url:https|max:2048', 'backup_domains' => 'present|array|max:10', 'backup_domains.*' => 'url:https|max:2048', 'support_url' => 'nullable|url:https|max:2048',
            'tabs.preserve_session' => 'sometimes|boolean', 'campaigns.opening_window_seconds' => 'sometimes|integer|min:2|max:30', 'tabs' => 'required|array', 'tabs.auto_close' => 'required|boolean', 'tabs.timeout_minutes' => 'required|integer|min:1|max:10080', 'tabs.basis' => ['required', Rule::in(['opened', 'activity'])], 'tabs.max_tabs' => 'required|integer|min:2|max:20',
            'dns' => 'required|array', 'dns.enabled' => 'required|boolean', 'dns.system_fallback' => 'required|boolean', 'dns.resolvers' => 'required|array|min:1|max:5', 'dns.resolvers.*.url' => 'required|url:https|max:2048',
            'dns.resolvers.*.bootstrap_ips' => 'required|array|min:1|max:4', 'dns.resolvers.*.bootstrap_ips.*' => 'required|ip', 'dns.rules' => 'present|array|max:100', 'dns.rules.*.host' => ['required', 'regex:/^[a-zA-Z0-9.-]+$/'],
            'dns.rules.*.resolver_index' => 'required|integer|min:0|max:4', 'campaigns.daily_cap' => 'sometimes|integer|min:1|max:100', 'poll_seconds' => 'required|integer|min:30|max:3600', 'heartbeat_seconds' => 'required|integer|min:15|max:60',
        ])->validate();
        foreach ($data['dns']['rules'] as $rule) {
            if (! isset($data['dns']['resolvers'][$rule['resolver_index']])) {
                throw ValidationException::withMessages(['payload' => 'DNS rule refers to a resolver that does not exist.']);
            }
        }
        foreach ($data['dns']['resolvers'] as $resolver) {
            foreach ($resolver['bootstrap_ips'] as $ip) {
                if (! filter_var($ip, FILTER_VALIDATE_IP, FILTER_FLAG_NO_PRIV_RANGE | FILTER_FLAG_NO_RES_RANGE)) {
                    throw ValidationException::withMessages(['payload' => 'Resolver bootstrap addresses must be public IP addresses.']);
                }
            }
        }
        $data['vpn'] = array_replace(ConfigurationService::defaults(), ConfigurationService::selected()?->payload ?? [])['vpn'];
        $target = $r->validate(['scope' => 'required|in:all,test', 'installation_ids' => 'required_if:scope,test|array|max:100', 'installation_ids.*' => ['uuid', Rule::exists('installations', 'id')->where('test_device', true)]]);
        if ($target['scope'] === 'test' && empty($target['installation_ids'])) {
            throw ValidationException::withMessages(['installation_ids' => 'Choose at least one test device.']);
        }
        $configuration = AppConfiguration::create(['payload' => $data, 'published' => true, 'scope' => $target['scope'], 'installation_ids' => $target['scope'] === 'test' ? $target['installation_ids'] : [], 'created_by' => Auth::id()]);
        $this->audit('configuration.published', (string) $configuration->id);

        return back()->with('status', 'Configuration published. Apps receive it on their next successful refresh.');
    }

    public function rollback(AppConfiguration $configuration): RedirectResponse
    {
        $copy = AppConfiguration::create(['payload' => $configuration->payload, 'published' => true, 'scope' => $configuration->scope, 'installation_ids' => $configuration->installation_ids, 'created_by' => Auth::id()]);
        $this->audit('configuration.rollback', (string) $copy->id, ['source' => $configuration->id]);

        return back()->with('status', 'Previous settings republished as a new revision.');
    }

    public function promoteConfiguration(AppConfiguration $configuration): RedirectResponse
    {
        $copy = AppConfiguration::create(['payload' => $configuration->payload, 'published' => true, 'scope' => 'all', 'installation_ids' => [], 'created_by' => Auth::id()]);
        $this->audit('configuration.promoted', (string) $copy->id, ['source' => $configuration->id]);

        return back()->with('status', 'This exact configuration is now published to everyone.');
    }

    public function releases(): View
    {
        return view('admin.releases', ['releases' => Release::orderByDesc('version_code')->get()]);
    }

    public function saveRelease(Request $r): RedirectResponse
    {
        $data = $r->validate(['version_code' => 'required|integer|min:1|unique:releases', 'version_name' => 'required|string|max:40', 'notes' => 'nullable|string|max:3000', 'apk' => 'nullable|file|extensions:apk|max:100000', 'apk_url' => 'nullable|required_without:apk|url:https|max:2048', 'sha256' => 'nullable|required_without:apk|regex:/^[a-fA-F0-9]{64}$/', 'size_bytes' => 'nullable|required_without:apk|integer|min:1|max:500000000', 'min_android' => 'required|integer|min:26|max:100', 'minimum_supported_version' => 'required|integer|min:1|lte:version_code', 'rollout_percent' => 'required|integer|min:0|max:100', 'remind_hours' => 'required|integer|min:1|max:720']);
        if ($r->hasFile('apk')) {
            $file = $r->file('apk');
            $path = $file->storeAs('releases', (string) Str::uuid().'.apk', 'public');
            $data['apk_url'] = Storage::disk('public')->url($path);
            $data['sha256'] = hash_file('sha256', $file->getRealPath());
            $data['size_bytes'] = $file->getSize();
        }
        unset($data['apk']);
        $data['backup_urls'] = $this->jsonField($r, 'backup_urls');
        Validator::make(['urls' => $data['backup_urls']], ['urls' => 'array|max:5', 'urls.*' => 'url:https|max:2048'])->validate();
        $data['sha256'] = strtolower($data['sha256']);
        $release = Release::create($data);
        $this->audit('release.created', (string) $release->id);

        return back()->with('status', 'Release saved as a draft. Verify the APK before publishing.');
    }

    public function releaseAction(Request $r, Release $release): RedirectResponse
    {
        $action = $r->validate(['action' => ['required', Rule::in(['publish', 'withdraw'])]])['action'];
        if ($action === 'publish') {
            abort_unless($release->artifact_verified, 422, 'Verify the APK with releases:verify before publishing.');
        }
        $release->update(['published' => $action === 'publish']);
        $this->audit('release.'.$action, (string) $release->id);

        return back()->with('status', 'Release updated.');
    }

    public function campaignReport(Campaign $campaign): View
    {
        return view('admin.campaign-report', [
            'campaign' => $campaign,
            'testDeliveries' => Delivery::where('campaign_id', $campaign->id)->where('is_test', true)->count(),
            'states' => DB::table('deliveries')->where('campaign_id', $campaign->id)->select('status', DB::raw('count(*) as total'), DB::raw('count(distinct installation_id) as unique_installations'))->groupBy('status')->get(),
            'displayed' => DB::table('deliveries')->where('campaign_id', $campaign->id)->whereNotNull('displayed_at')->count(),
            'clicked' => DB::table('deliveries')->where('campaign_id', $campaign->id)->whereNotNull('clicked_at')->count(),
            'dismissed' => DB::table('deliveries')->where('campaign_id', $campaign->id)->whereNotNull('dismissed_at')->count(),
            'reasons' => DB::table('eligibility_checks')->where('campaign_id', $campaign->id)->select('reason', DB::raw('count(*) as total'))->groupBy('reason')->get(),
        ]);
    }

    private function retention(): array
    {
        $rows = [];
        foreach ([1, 7, 30] as $day) {
            $next = $day + 1;
            $eligible = Installation::where('created_at', '<=', now()->subDays($next))->where('created_at', '>=', now()->subDays(60))->count();
            $query = DB::table('installations as i')->join('telemetry_events as e', 'e.installation_id', '=', 'i.id')->where('e.type', 'app_open')->where('i.created_at', '<=', now()->subDays($next))->where('i.created_at', '>=', now()->subDays(60));
            if (DB::getDriverName() === 'sqlite') {
                $query->whereRaw("e.occurred_at >= datetime(i.created_at, '+{$day} days') AND e.occurred_at < datetime(i.created_at, '+{$next} days')");
            } else {
                $query->whereRaw("e.occurred_at >= i.created_at + INTERVAL '{$day} days' AND e.occurred_at < i.created_at + INTERVAL '{$next} days'");
            }
            $returned = $query->distinct()->count('i.id');
            $rows[] = ['day' => $day, 'eligible' => $eligible, 'returned' => $returned, 'percentage' => $eligible ? round(100 * $returned / $eligible, 1) : null];
        }

        return $rows;
    }

    public function auditLogs(): View
    {
        return view('admin.audit', ['logs' => DB::table('audit_logs')->leftJoin('users', 'users.id', '=', 'audit_logs.user_id')->select('audit_logs.*', 'users.email')->orderByDesc('audit_logs.id')->paginate(50)]);
    }

    public function staff(): View
    {
        return view('admin.staff', ['users' => User::orderBy('name')->get()]);
    }

    public function saveStaff(Request $r): RedirectResponse
    {
        $data = $r->validate(['name' => 'required|string|max:100', 'email' => 'required|email|unique:users', 'password' => 'required|string|min:14|max:200', 'role' => ['required', Rule::in(User::ROLES)]]);
        $role = $data['role'];
        unset($data['role']);
        $user = User::create($data);
        $user->role = $role;
        $user->save();
        $this->audit('staff.created', (string) $user->id, ['role' => $role]);

        return back()->with('status', 'Staff account created.');
    }
}
