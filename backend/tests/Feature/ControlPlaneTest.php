<?php

namespace Tests\Feature;

use App\Models\AppConfiguration;
use App\Models\Campaign;
use App\Models\Delivery;
use App\Models\Installation;
use App\Models\Release;
use App\Models\User;
use App\Services\CampaignEngine;
use App\Services\ConfigurationService;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\Queue;
use Illuminate\Support\Str;
use Tests\TestCase;

class ControlPlaneTest extends TestCase
{
    use RefreshDatabase;

    private function authDevice(?Installation $device = null): Installation
    {
        $device ??= Installation::factory()->create();
        $this->withToken($device->id.'.test-secret');

        return $device;
    }

    public function test_registration_returns_a_secret_once_and_stores_only_its_hash(): void
    {
        $response = $this->postJson('/api/v1/installations', ['version_code' => 1, 'version_name' => '1.0', 'android_version' => 35, 'language' => 'en', 'notifications_enabled' => false])->assertCreated();
        [$id,$secret] = explode('.', $response->json('token'));
        $device = Installation::findOrFail($id);
        $this->assertSame(hash('sha256', $secret), $device->token_hash);
        $this->assertArrayNotHasKey('token_hash', $device->toArray());
    }

    public function test_an_invalid_installation_token_cannot_read_settings(): void
    {
        $this->withToken(Str::uuid().'.wrong')->getJson('/api/v1/configuration')->assertUnauthorized();
    }

    public function test_configuration_is_signed_and_new_revision_restores_previous_settings(): void
    {
        $key = openssl_pkey_new(['private_key_bits' => 2048]);
        openssl_pkey_export($key, $private);
        config(['mobile.signing_private_key' => base64_encode($private)]);
        AppConfiguration::create(['payload' => ConfigurationService::defaults(), 'published' => true]);
        $this->authDevice();
        $data = $this->getJson('/api/v1/configuration')->assertOk()->json();
        $this->assertSame(1, openssl_verify(base64_decode($data['payload']), base64_decode($data['signature']), openssl_pkey_get_details($key)['key'], OPENSSL_ALGO_SHA256));
        $this->assertSame(60, json_decode(base64_decode($data['payload']), true)['tabs']['timeout_minutes']);
    }

    public function test_consecutive_eligibility_requests_reuse_an_offer_without_counting_a_display(): void
    {
        $device = $this->authDevice();
        $campaign = Campaign::factory()->create();
        $session = (string) Str::uuid();
        $url = '/api/v1/messages?trigger=app_open&elapsed_seconds=1&session_id='.$session;
        $first = $this->getJson($url)->assertOk()->json('message.delivery_id');
        $second = $this->getJson($url)->assertOk()->json('message.delivery_id');
        $this->assertSame($first, $second);
        $this->assertDatabaseCount('deliveries', 1);
        $this->assertNull(Delivery::first()->displayed_at);
        $this->assertDatabaseHas('eligibility_checks', ['installation_id' => $device->id, 'campaign_id' => $campaign->id, 'reason' => 'pending_offer']);
    }

    public function test_duplicate_events_do_not_inflate_displays_or_sessions(): void
    {
        $device = $this->authDevice();
        $campaign = Campaign::factory()->create();
        $delivery = app(CampaignEngine::class)->reserve($campaign, $device, 'app_open');
        $event = ['id' => (string) Str::uuid(), 'type' => 'popup_displayed', 'delivery_id' => $delivery->id, 'occurred_at' => now()->toIso8601String()];
        $this->postJson('/api/v1/events', ['events' => [$event]])->assertOk();
        $this->postJson('/api/v1/events', ['events' => [$event]])->assertOk();
        $this->assertDatabaseCount('telemetry_events', 1);
        $this->assertNotNull($delivery->fresh()->displayed_at);
    }

    public function test_an_installation_cannot_report_another_installations_delivery(): void
    {
        $victim = Installation::factory()->create();
        $campaign = Campaign::factory()->create();
        $delivery = app(CampaignEngine::class)->reserve($campaign, $victim, 'app_open');
        $this->authDevice();
        $this->postJson('/api/v1/events', ['events' => [['id' => (string) Str::uuid(), 'type' => 'campaign_clicked', 'delivery_id' => $delivery->id, 'occurred_at' => now()->toIso8601String()]]])->assertOk();
        $this->assertNull($delivery->fresh()->clicked_at);
        $this->assertDatabaseCount('telemetry_events', 0);
    }

    public function test_frequency_cap_applies_after_display(): void
    {
        $device = Installation::factory()->create();
        $campaign = Campaign::factory()->create();
        $engine = app(CampaignEngine::class);
        $first = $engine->reserve($campaign, $device, 'app_open');
        $first->update(['status' => 'displayed', 'displayed_at' => now()]);
        $this->assertNull($engine->reserve($campaign, $device, 'app_open'));
        $this->assertDatabaseHas('eligibility_checks', ['reason' => 'lifetime_cap']);
    }

    public function test_audience_and_overnight_quiet_hours_suppress_messages(): void
    {
        $this->travelTo(now()->setTimezone('Africa/Addis_Ababa')->setTime(12, 0));
        $device = Installation::factory()->create(['language' => 'en']);
        $campaign = Campaign::factory()->create(['audience' => ['mode' => 'all', 'rules' => [['field' => 'language', 'op' => 'eq', 'value' => 'am']]]]);
        $engine = app(CampaignEngine::class);
        $this->assertSame('audience_mismatch', $engine->reason($campaign, $device));
        $campaign->update(['audience' => [], 'quiet_start' => '22:00', 'quiet_end' => '06:00']);
        $this->travelTo(now()->setTimezone('Africa/Addis_Ababa')->setTime(23, 0));
        $this->assertSame('quiet_hours', $engine->reason($campaign, $device));
    }

    public function test_viewers_cannot_change_configuration_and_staff_pages_render(): void
    {
        $user = User::factory()->create();
        $this->actingAs($user)->post('/configuration', ['payload' => '{}'])->assertForbidden();
        foreach (['/', '/installations', '/campaigns', '/campaigns/new', '/configuration', '/releases', '/audit'] as $url) {
            $this->get($url)->assertOk();
        }
    }

    public function test_unverified_release_cannot_be_published(): void
    {
        $user = User::factory()->create();
        $user->role = 'owner';
        $user->save();
        $release = Release::create(['version_code' => 2, 'version_name' => '1.1', 'apk_url' => 'https://example.com/app.apk', 'sha256' => str_repeat('a', 64), 'size_bytes' => 100]);
        $this->actingAs($user)->post('/releases/'.$release->id.'/action', ['action' => 'publish'])->assertUnprocessable();
        $this->assertFalse($release->fresh()->published);
    }

    public function test_repeated_scheduler_runs_do_not_duplicate_push_recipients(): void
    {
        Queue::fake();
        $device = Installation::factory()->create(['push_token' => 'test-token']);
        Campaign::factory()->create(['type' => 'push', 'trigger' => 'schedule', 'next_run_at' => now()->subMinute()]);
        $this->artisan('campaigns:dispatch')->assertSuccessful();
        $this->artisan('campaigns:dispatch')->assertSuccessful();
        $this->assertDatabaseCount('campaign_runs', 1);
        $this->assertDatabaseCount('deliveries', 1);
    }

    public function test_telemetry_does_not_accept_full_urls_or_password_fields(): void
    {
        $this->authDevice();
        $this->postJson('/api/v1/events', ['events' => [['id' => (string) Str::uuid(), 'type' => 'page_loaded', 'host' => 'https://example.com/?password=secret', 'occurred_at' => now()->toIso8601String()]]])->assertUnprocessable();
        $this->postJson('/api/v1/events', ['events' => [['id' => (string) Str::uuid(), 'type' => 'page_loaded', 'password' => 'must-not-be-accepted', 'occurred_at' => now()->toIso8601String()]]])->assertUnprocessable();
        $this->assertDatabaseCount('telemetry_events', 0);
    }
}
