<?php

namespace App\Services;

use App\Models\AppConfiguration;
use App\Models\Installation;
use App\Models\Release;
use Carbon\Carbon;
use Illuminate\Support\Facades\DB;

class ConfigurationService
{
    public static function defaults(): array
    {
        return [
            'website_url' => config('mobile.website_url'), 'backup_domains' => [], 'support_url' => config('mobile.support_url'),
            'vpn' => ['enabled' => false, 'apply' => 'next_open', 'servers' => [], 'included_gib' => 10000, 'base_cost_usd' => 96, 'excess_usd_per_gib' => 0.01, 'budget_usd' => 200],
            'location' => ['enabled' => true, 'interval_seconds' => 900, 'retention_days' => 90],
            'cache' => ['mode' => 'standard'],
            'tabs' => ['auto_close' => true, 'timeout_minutes' => 60, 'basis' => 'opened', 'max_tabs' => 8, 'preserve_session' => true],
            'dns' => ['enabled' => true, 'system_fallback' => false, 'resolvers' => [
                ['url' => 'https://cloudflare-dns.com/dns-query', 'bootstrap_ips' => ['1.1.1.1', '1.0.0.1']],
                ['url' => 'https://dns.google/dns-query', 'bootstrap_ips' => ['8.8.8.8', '8.8.4.4']],
            ], 'rules' => []],
            'maintenance' => ['enabled' => false, 'blocking' => false, 'title' => 'Service notice', 'message' => ''], 'campaigns' => ['daily_cap' => 5, 'opening_window_seconds' => 5], 'poll_seconds' => 60, 'heartbeat_seconds' => 30,
        ];
    }

    public static function selected(?Installation $installation = null): ?AppConfiguration
    {
        foreach (AppConfiguration::where('published', true)->latest('id')->cursor() as $configuration) {
            if ($configuration->scope === 'all' || ($installation?->test_device && in_array($installation->id, $configuration->installation_ids ?? [], true))) {
                return $configuration;
            }
        }

        return null;
    }

    public function envelope(?Installation $installation = null): array
    {
        $current = self::selected($installation);
        $payload = array_replace(self::defaults(), $current?->payload ?? []);
        $payload['support_url'] = SiteSettings::supportUrl();
        $payload['download_page_url'] = route('download.page');
        $payload['download_url'] = route('download.apk');
        $payload['configuration_id'] = $current?->id ?? 0;
        $payload['revision'] = AppConfiguration::max('id') ?? 0;
        $payload['issued_at'] = now()->timestamp;
        $payload['expires_at'] = now()->addHours(config('mobile.configuration_ttl_hours'))->timestamp;
        $payload['release'] = null;
        $payload['commands'] = [];
        if ($installation) {
            $payload['commands'] = DB::table('device_commands')->where('installation_id', $installation->id)->where('status', 'pending')->where('expires_at', '>', now())->orderBy('created_at')->limit(30)->get(['id', 'type', 'timing', 'expires_at'])->map(fn ($command) => ['id' => $command->id, 'type' => $command->type, 'timing' => $command->timing, 'expires_at' => Carbon::parse($command->expires_at)->timestamp])->all();
            $peer = DB::table('vpn_peers')->where('installation_id', $installation->id)->first();
            $payload['vpn']['provisioned'] = (bool) ($peer?->provisioned ?? false);
            $payload['vpn']['address'] = $peer ? '10.66.'.intdiv($peer->id + 1, 256).'.'.(($peer->id + 1) % 256).'/32' : null;
        }

        if ($installation) {
            $release = Release::where('published', true)->where('artifact_verified', true)->where('min_android', '<=', $installation->android_version)->orderByDesc('version_code')->first();
            if ($release && $installation->version_code < $release->version_code) {
                $required = $installation->version_code < $release->minimum_supported_version;
                $bucket = hexdec(substr(hash('sha256', $installation->id.':'.$release->id), 0, 6)) % 100;
                if ($required || $bucket < $release->rollout_percent) {
                    $payload['release'] = array_merge($release->only(['version_code', 'version_name', 'notes', 'apk_url', 'backup_urls', 'sha256', 'size_bytes', 'remind_hours']), ['required' => $required]);
                }
            }
        }
        $bytes = json_encode($payload, JSON_UNESCAPED_SLASHES | JSON_THROW_ON_ERROR);
        $key = base64_decode((string) config('mobile.signing_private_key'), true);
        if (! $key || ! openssl_sign($bytes, $signature, $key, OPENSSL_ALGO_SHA256)) {
            throw new \RuntimeException('Mobile configuration signing key is not configured.');
        }

        return ['payload' => base64_encode($bytes), 'signature' => base64_encode($signature), 'algorithm' => 'SHA256withRSA'];
    }
}
