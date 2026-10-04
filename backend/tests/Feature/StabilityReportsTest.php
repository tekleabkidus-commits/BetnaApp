<?php

namespace Tests\Feature;

use App\Models\Installation;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Str;
use Tests\TestCase;

class StabilityReportsTest extends TestCase
{
    use RefreshDatabase;

    public function test_new_exit_and_form_events_are_accepted_without_credential_fields(): void
    {
        $device = Installation::factory()->create();
        $this->withToken($device->id.'.test-secret');
        $events = [];
        foreach (['app_exit' => 'LOW_MEMORY_0', 'app_crash' => 'IllegalStateException_MainActivity_281', 'login_detected' => 'FORM_RESOLVED'] as $type => $code) {
            $events[] = ['id' => (string) Str::uuid(), 'type' => $type, 'code' => $code, 'occurred_at' => now()->toIso8601String(), 'username' => 'private-user', 'password' => 'private-password', 'url' => 'https://private.invalid/session'];
        }
        $this->postJson('/api/v1/events', ['events' => $events])->assertUnprocessable();
        $this->assertDatabaseCount('telemetry_events', 0);
        foreach ($events as &$event) {
            unset($event['username'], $event['password'], $event['url']);
        }
        unset($event);
        $this->postJson('/api/v1/events', ['events' => $events])->assertOk();
        $this->postJson('/api/v1/events', ['events' => $events])->assertOk();
        $this->assertDatabaseCount('telemetry_events', 3);
        $stored = json_encode(DB::table('telemetry_events')->get());
        $this->assertStringNotContainsString('private-', $stored);
        $this->assertStringNotContainsString('private.invalid', $stored);
        $bad = $events[0];
        $bad['id'] = (string) Str::uuid();
        $bad['code'] = 'Private message with spaces';
        $this->postJson('/api/v1/events', ['events' => [$bad]])->assertUnprocessable();
    }

    public function test_stability_summary_is_scoped_to_device_and_available_to_super_admin(): void
    {
        config(['mobile.require_two_factor' => false]);
        $device = Installation::factory()->create();
        $other = Installation::factory()->create();
        foreach ([[$device, 'LOW_MEMORY_0'], [$other, 'NATIVE_CRASH_6']] as [$installation,$code]) {
            DB::table('telemetry_events')->insert(['id' => (string) Str::uuid(), 'installation_id' => $installation->id, 'type' => 'app_exit', 'code' => $code, 'version_code' => 6, 'occurred_at' => now(), 'created_at' => now()]);
        }
        $this->actingAs(User::factory()->create(['role' => 'super_admin']));
        $this->get('/devices/'.$device->id)->assertOk()->assertSee('App stability')->assertSee('LOW_MEMORY_0')->assertDontSee('NATIVE_CRASH_6')->assertDontSee('Cache commands');
        $this->get('/connections')->assertForbidden();
    }
}
