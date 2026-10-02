<?php

namespace Tests\Feature;

use App\Models\AppConfiguration;
use App\Models\Installation;
use App\Models\User;
use App\Services\ConfigurationService;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Str;
use Tests\TestCase;

class BrowserControlsTest extends TestCase
{
    use RefreshDatabase;

    private function owner(string $role = 'owner'): void
    {
        config(['mobile.require_two_factor' => false]);
        $this->actingAs(User::factory()->create(['role' => $role]));
    }

    private function authDevice(?Installation $device = null): Installation
    {
        $device ??= Installation::factory()->create();
        $this->withToken($device->id.'.test-secret');

        return $device;
    }

    private function locationData(): array
    {
        return ['id' => (string) Str::uuid(), 'latitude' => 9.03, 'longitude' => 38.75, 'accuracy_m' => 1500, 'precision' => 'approximate', 'observed_at' => now()->toIso8601String()];
    }

    public function test_location_requires_an_authenticated_installation_and_bounded_coordinates(): void
    {
        $this->postJson('/api/v1/location', $this->locationData())->assertUnauthorized();
        $d = $this->authDevice();
        $this->postJson('/api/v1/location', $this->locationData() + ['password' => 'private'])->assertOk();
        $this->assertDatabaseHas('device_locations', ['installation_id' => $d->id, 'precision' => 'approximate']);
        $bad = $this->locationData();
        $bad['latitude'] = 100;
        $this->postJson('/api/v1/location', $bad)->assertUnprocessable();
        $this->assertSame(1, DB::table('device_locations')->count());
    }

    public function test_disabled_location_policy_refuses_new_samples(): void
    {
        $this->authDevice();
        $payload = ConfigurationService::defaults();
        $payload['location']['enabled'] = false;
        AppConfiguration::create(['payload' => $payload, 'scope' => 'all', 'published' => true]);
        $this->postJson('/api/v1/location', $this->locationData())->assertForbidden();
    }

    public function test_cache_commands_are_targeted_and_cannot_be_acknowledged_by_another_device(): void
    {
        $this->owner();
        $a = Installation::factory()->create(['test_device' => true]);
        $b = Installation::factory()->create();
        $this->post('/cache', ['target' => 'test', 'timing' => 'next_open'])->assertRedirect();
        $this->assertDatabaseCount('device_commands', 1);
        $c = DB::table('device_commands')->first();
        $this->assertSame($a->id, $c->installation_id);
        $this->authDevice($b);
        $this->postJson('/api/v1/commands/ack', ['id' => $c->id, 'status' => 'completed'])->assertNotFound();
        $this->authDevice($a);
        $this->postJson('/api/v1/commands/ack', ['id' => $c->id, 'status' => 'completed'])->assertOk();
        $this->postJson('/api/v1/commands/ack', ['id' => $c->id, 'status' => 'failed'])->assertOk();
        $this->assertDatabaseHas('device_commands', ['id' => $c->id, 'status' => 'completed']);
    }

    public function test_sensitive_reports_and_commands_enforce_roles(): void
    {
        $device = Installation::factory()->create();
        $this->owner('viewer');
        foreach (['/locations', '/devices/'.$device->id, '/cache', '/vpn'] as $url) {
            $this->get($url)->assertForbidden();
        }
        $this->post('/cache', ['target' => 'all', 'timing' => 'immediate'])->assertForbidden();
        $this->owner('operator');
        $this->get('/locations')->assertOk();
        $this->get('/devices/'.$device->id)->assertOk();
        $this->get('/vpn')->assertForbidden();
    }

    public function test_vpn_keys_are_stable_per_device_and_need_explicit_server_provisioning(): void
    {
        $device = $this->authDevice();
        $key = base64_encode(random_bytes(32));
        $this->postJson('/api/v1/vpn/peer', ['public_key' => $key])->assertOk();
        $this->postJson('/api/v1/vpn/peer', ['public_key' => $key])->assertOk();
        $this->assertDatabaseCount('vpn_peers', 1);
        $this->assertDatabaseHas('vpn_peers', ['installation_id' => $device->id, 'provisioned' => false]);
        $this->postJson('/api/v1/vpn/peer', ['public_key' => base64_encode(random_bytes(32))])->assertConflict();
    }

    public function test_vpn_cannot_be_enabled_without_a_server_and_other_forms_cannot_override_it(): void
    {
        $this->owner();
        $this->post('/vpn', ['enabled' => 1, 'apply' => 'next_open', 'servers' => []])->assertSessionHasErrors('servers');
        $this->post('/vpn', ['enabled' => 0, 'apply' => 'next_open', 'servers' => [['name' => 'Primary', 'endpoint' => '', 'public_key' => '']]])->assertRedirect();
        $payload = ConfigurationService::defaults();
        $payload['vpn']['enabled'] = true;
        $this->post('/configuration', ['scope' => 'all', 'payload' => json_encode($payload)])->assertRedirect();
        $this->assertFalse(ConfigurationService::selected()->payload['vpn']['enabled']);
    }

    public function test_device_report_distinguishes_attempts_and_exports_audited_locations(): void
    {
        $device = $this->authDevice();
        $this->postJson('/api/v1/location', $this->locationData())->assertOk();
        $this->owner();
        $this->get('/devices/'.$device->id)->assertOk()->assertSee('Website login success is not available');
        $this->get('/locations/export')->assertOk()->assertDownload('betna-locations.csv');
        $this->assertDatabaseHas('audit_logs', ['action' => 'locations.exported']);
    }

    public function test_vpn_traffic_heartbeats_are_idempotent_and_handle_counter_resets(): void
    {
        $device = $this->authDevice();
        $data = ['version_code' => $device->version_code, 'version_name' => $device->version_name, 'android_version' => 35, 'language' => 'en', 'notifications_enabled' => false, 'foreground' => true, 'vpn_status' => 'connected', 'vpn_rx_bytes' => 1000, 'vpn_tx_bytes' => 200];
        $this->postJson('/api/v1/heartbeat', $data)->assertOk();
        $this->postJson('/api/v1/heartbeat', $data)->assertOk();
        $this->assertDatabaseHas('vpn_usage_days', ['installation_id' => $device->id, 'rx_bytes' => 1000, 'tx_bytes' => 200]);
        $data['vpn_rx_bytes'] = 50;
        $data['vpn_tx_bytes'] = 10;
        $this->postJson('/api/v1/heartbeat', $data)->assertOk();
        $data['vpn_rx_bytes'] = 70;
        $data['vpn_tx_bytes'] = 15;
        $this->postJson('/api/v1/heartbeat', $data)->assertOk();
        $this->assertDatabaseHas('vpn_usage_days', ['installation_id' => $device->id, 'rx_bytes' => 1020, 'tx_bytes' => 205]);
        $this->assertDatabaseCount('vpn_usage_days', 1);
    }

    public function test_signed_configuration_only_includes_own_pending_commands_and_peer_address(): void
    {
        $this->owner();
        $device = Installation::factory()->create();
        $other = Installation::factory()->create();
        $this->post('/cache', ['target' => 'device', 'device' => $device->id, 'timing' => 'immediate'])->assertRedirect();
        $key = openssl_pkey_new(['private_key_bits' => 2048]);
        openssl_pkey_export($key, $private);
        config(['mobile.signing_private_key' => base64_encode($private)]);
        $payload = json_decode(base64_decode((new ConfigurationService)->envelope($device)['payload']), true);
        $this->assertCount(1, $payload['commands']);
        $this->assertFalse($payload['vpn']['provisioned']);
        $otherPayload = json_decode(base64_decode((new ConfigurationService)->envelope($other)['payload']), true);
        $this->assertSame([], $otherPayload['commands']);
    }
}
