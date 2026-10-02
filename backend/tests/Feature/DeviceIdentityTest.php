<?php

namespace Tests\Feature;

use App\Models\Device;
use App\Models\Installation;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Testing\TestResponse;
use Tests\TestCase;

class DeviceIdentityTest extends TestCase
{
    use RefreshDatabase;

    /** @return array<string, int|string|bool> */
    private function metadata(): array
    {
        return ['version_code' => 5, 'version_name' => '0.5.0', 'android_version' => 35, 'language' => 'en', 'notifications_enabled' => false];
    }

    private function register(?string $identifier = null): TestResponse
    {
        return $this->postJson('/api/v1/installations', $this->metadata() + ['device_identifier' => $identifier])->assertCreated();
    }

    public function test_repeat_registration_links_the_device_but_keeps_fresh_credentials_and_preferences(): void
    {
        $identifier = hash('sha256', 'example-device-profile');
        $first = $this->register($identifier)->json();
        $previous = Installation::findOrFail($first['installation_id']);
        $originalTokenHash = $previous->token_hash;
        $previous->update(['test_device' => true, 'promotions_enabled' => false, 'label' => 'Private staff phone', 'revoked_at' => now(), 'push_token' => 'old-push-token']);
        $second = $this->register(strtoupper($identifier))->json();
        $current = Installation::findOrFail($second['installation_id']);
        $this->assertDatabaseCount('devices', 1);
        $this->assertDatabaseCount('installations', 2);
        $this->assertSame($previous->device_id, $current->device_id);
        $this->assertNotSame($first['installation_id'], $second['installation_id']);
        $this->assertNotSame($first['token'], $second['token']);
        $this->assertSame($originalTokenHash, $previous->fresh()->token_hash);
        $this->assertFalse($current->test_device);
        $this->assertTrue($current->promotions_enabled);
        $this->assertFalse($current->notifications_enabled);
        $this->assertNull($current->label);
        $this->assertNull($current->revoked_at);
        $this->assertNull($current->push_token);
        $this->assertNotSame($identifier, Device::firstOrFail()->identifier_hash);
        $this->assertArrayNotHasKey('identifier_hash', Device::firstOrFail()->toArray());
        $this->withToken($first['token'])->postJson('/api/v1/heartbeat', $this->metadata() + ['foreground' => true])->assertUnauthorized();
        $this->withToken($second['token'])->postJson('/api/v1/heartbeat', $this->metadata() + ['foreground' => true])->assertOk();
    }

    public function test_an_upgrade_links_an_existing_installation_without_replacing_its_token_or_history(): void
    {
        $legacy = Installation::factory()->create(['session_count' => 7]);
        $identifier = hash('sha256', 'upgrade-profile');
        $this->withToken($legacy->id.'.test-secret')->postJson('/api/v1/heartbeat', $this->metadata() + ['device_identifier' => $identifier, 'foreground' => true])->assertOk();
        $this->assertDatabaseCount('installations', 1);
        $this->assertNotNull($legacy->fresh()->device_id);
        $this->assertSame(7, $legacy->fresh()->session_count);
        $this->assertSame(hash('sha256', 'test-secret'), $legacy->fresh()->token_hash);
        $current = $this->register($identifier)->json('installation_id');
        $this->assertSame($legacy->fresh()->device_id, Installation::findOrFail($current)->device_id);
    }

    public function test_a_linked_installation_cannot_replace_its_device_association(): void
    {
        $registered = $this->register(hash('sha256', 'first-profile'))->json();
        $installation = Installation::findOrFail($registered['installation_id']);
        $this->withToken($registered['token'])->postJson('/api/v1/heartbeat', $this->metadata() + ['device_identifier' => hash('sha256', 'different-profile'), 'foreground' => true])->assertOk();
        $this->assertSame($installation->device_id, $installation->fresh()->device_id);
        $this->assertDatabaseCount('devices', 1);
    }

    public function test_different_profile_identifiers_stay_separate_and_older_clients_remain_supported(): void
    {
        $first = $this->register(hash('sha256', 'profile-a'))->json('installation_id');
        $second = $this->register(hash('sha256', 'profile-b'))->json('installation_id');
        $legacy = $this->register()->json('installation_id');
        $this->assertDatabaseCount('devices', 2);
        $this->assertNotSame(Installation::findOrFail($first)->device_id, Installation::findOrFail($second)->device_id);
        $this->assertNull(Installation::findOrFail($legacy)->device_id);
    }

    public function test_invalid_device_identifiers_are_rejected_before_creating_records(): void
    {
        foreach (['9774d56d682e549c', str_repeat('x', 64), str_repeat('a', 65), ['unexpected' => true]] as $identifier) {
            $this->postJson('/api/v1/installations', $this->metadata() + ['device_identifier' => $identifier])->assertUnprocessable()->assertJsonValidationErrors('device_identifier');
        }
        $this->assertDatabaseCount('installations', 0);
        $this->assertDatabaseCount('devices', 0);
    }

    public function test_device_counts_history_filters_and_report_permissions_are_correct(): void
    {
        $this->getJson('/api/v1/ping')->assertOk()->assertJsonPath('features.device_recognition', true);
        $device = Device::factory()->create();
        $first = Installation::factory()->create(['device_id' => $device->id, 'session_count' => 3]);
        $second = Installation::factory()->create(['device_id' => $device->id, 'session_count' => 4]);
        $other = Device::factory()->create();
        Installation::factory()->create(['device_id' => $other->id]);
        Installation::factory()->count(2)->create();
        config(['mobile.require_two_factor' => false]);
        $this->actingAs(User::factory()->create(['role' => 'owner']));
        $this->get('/')->assertOk()->assertViewHas('identityStats', ['devices' => 2, 'repeats' => 1, 'unlinked' => 2]);
        $filtered = $this->get('/installations?recognized=repeat')->assertOk();
        $this->assertEqualsCanonicalizing([$first->id, $second->id], $filtered->viewData('devices')->pluck('id')->all());
        $this->get('/installations?q='.$device->id)->assertOk()->assertViewHas('devices', fn ($devices) => $devices->total() === 2);
        $this->get('/installations?recognized=unlinked')->assertOk()->assertViewHas('devices', fn ($devices) => $devices->total() === 2);
        $this->get('/devices/'.$first->id)->assertOk()->assertSee('Installation history');
        $this->get('/device-history/'.$device->id)->assertOk()->assertViewHas('sessions', 7)->assertSee($first->id)->assertSee($second->id)->assertDontSee($device->identifier_hash);
        $this->assertDatabaseHas('audit_logs', ['action' => 'device.history.viewed', 'subject' => $device->id]);
        $this->actingAs(User::factory()->create(['role' => 'operator']));
        $this->get('/device-history/'.$device->id)->assertOk();
        $this->actingAs(User::factory()->create(['role' => 'viewer']));
        $this->get('/device-history/'.$device->id)->assertForbidden();
        $this->get('/installations')->assertOk()->assertDontSee(route('admin.device.identity', $device));
    }

    public function test_a_device_identifier_cannot_be_used_as_an_installation_credential(): void
    {
        $identifier = hash('sha256', 'not-an-authentication-secret');
        $this->register($identifier);
        $this->withToken($identifier.'.test-secret')->postJson('/api/v1/heartbeat', $this->metadata() + ['foreground' => true])->assertUnauthorized();
    }
}
