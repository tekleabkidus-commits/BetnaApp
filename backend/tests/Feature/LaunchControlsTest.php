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
use App\Services\SiteSettings;
use App\Services\Totp;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Str;
use Tests\TestCase;

class LaunchControlsTest extends TestCase
{
    use RefreshDatabase;

    private function owner(): User
    {
        $owner = User::factory()->create(['role' => 'owner']);
        $this->actingAs($owner);

        return $owner;
    }

    private function device(?Installation $device = null): Installation
    {
        $device ??= Installation::factory()->create();
        $this->withToken($device->id.'.test-secret');

        return $device;
    }

    private function signing(): void
    {
        $key = openssl_pkey_new(['private_key_bits' => 2048]);
        openssl_pkey_export($key, $private);
        config(['mobile.signing_private_key' => base64_encode($private)]);
    }

    public function test_support_username_is_editable_and_never_an_arbitrary_redirect(): void
    {
        $this->owner();
        $settings = SiteSettings::get();
        $settings['telegram_username'] = '@betna_help';
        $this->post('/launch', $settings)->assertRedirect();
        $this->get('/support/telegram')->assertRedirect('https://t.me/betna_help');
        $settings['telegram_username'] = 'https://evil.invalid';
        $this->post('/launch', $settings)->assertSessionHasErrors('telegram_username');
    }

    public function test_public_download_only_redirects_to_verified_published_releases(): void
    {
        $release = Release::create(['version_code' => 2, 'version_name' => '2.0', 'apk_url' => 'https://cdn.invalid/betna.apk', 'sha256' => str_repeat('a', 64), 'size_bytes' => 42, 'published' => true, 'artifact_verified' => false, 'rollout_percent' => 100]);
        DB::table('site_settings')->insert(['id' => 1, 'payload' => json_encode(['download_enabled' => true])]);
        $this->get('/download')->assertOk()->assertSee('Download Betna App')->assertSee('not available yet');
        $this->get('/download/apk')->assertNotFound();
        $release->update(['artifact_verified' => true]);
        $this->get('/download/apk')->assertRedirect($release->apk_url);
        $release->update(['published' => false]);
        $this->get('/download/apk')->assertNotFound();
    }

    public function test_share_links_can_be_disabled_without_changing_the_public_page(): void
    {
        $this->owner();
        $this->post('/launch/links', ['slug' => 'telegram', 'label' => 'Telegram', 'destination' => 'page'])->assertRedirect();
        $id = DB::table('download_links')->value('id');
        $this->get('/get/telegram')->assertRedirect('/download');
        $this->assertDatabaseHas('download_links', ['requests' => 1]);
        $this->post('/launch/links/'.$id, ['enabled' => false, 'destination' => 'page', 'label' => 'Telegram'])->assertRedirect();
        $this->get('/get/telegram')->assertNotFound();
        $this->get('/download')->assertOk();
    }

    public function test_unprivileged_staff_cannot_change_public_links(): void
    {
        foreach (['viewer', 'operator'] as $role) {
            $this->actingAs(User::factory()->create(['role' => $role]));
            $this->post('/launch', SiteSettings::get())->assertForbidden();
        }
    }

    public function test_test_configuration_is_isolated_and_can_be_promoted(): void
    {
        $this->owner();
        $test = Installation::factory()->create(['test_device' => true]);
        $normal = Installation::factory()->create();
        $payload = ConfigurationService::defaults();
        $payload['website_url'] = 'https://betna.invalid';
        $payload['maintenance']['enabled'] = true;
        $this->post('/configuration', ['scope' => 'test', 'installation_ids' => [$test->id], 'payload' => json_encode($payload)])->assertRedirect();
        $revision = AppConfiguration::first();
        $this->assertSame($revision->id, ConfigurationService::selected($test)->id);
        $this->assertNull(ConfigurationService::selected($normal));
        $test->update(['test_device' => false]);
        $this->assertNull(ConfigurationService::selected($test));
        $this->post('/configuration/'.$revision->id.'/promote')->assertRedirect();
        $this->assertSame('https://betna.invalid', ConfigurationService::selected($normal)->payload['website_url']);
    }

    public function test_config_rejects_a_normal_device_as_a_test_target(): void
    {
        $this->owner();
        $normal = Installation::factory()->create();
        $this->post('/configuration', ['scope' => 'test', 'installation_ids' => [$normal->id], 'payload' => json_encode(ConfigurationService::defaults())])->assertSessionHasErrors('installation_ids.0');
        $this->assertDatabaseCount('app_configurations', 0);
    }

    public function test_configuration_revision_remains_monotonic_when_a_test_is_replaced(): void
    {
        $this->signing();
        $test = $this->device(Installation::factory()->create(['test_device' => true]));
        $payload = ConfigurationService::defaults();
        AppConfiguration::create(['payload' => $payload, 'published' => true, 'scope' => 'all']);
        AppConfiguration::create(['payload' => $payload, 'published' => true, 'scope' => 'test', 'installation_ids' => [$test->id]]);
        $first = json_decode(base64_decode($this->getJson('/api/v1/configuration')->assertOk()->json('payload')), true);
        AppConfiguration::create(['payload' => $payload, 'published' => true, 'scope' => 'all']);
        $next = json_decode(base64_decode($this->getJson('/api/v1/configuration')->assertOk()->json('payload')), true);
        $this->assertGreaterThan($first['revision'], $next['revision']);
    }

    public function test_opening_popup_never_appears_on_interval_or_after_opening_timeout(): void
    {
        $device = $this->device();
        Campaign::factory()->create(['trigger' => 'app_open']);
        $session = (string) Str::uuid();
        $this->postJson('/api/v1/opening', ['session_id' => $session])->assertOk();
        $this->getJson('/api/v1/messages?trigger=interval&session_trigger=app_open&elapsed_seconds=2&session_id='.$session)->assertOk()->assertJsonPath('message', null);
        $this->getJson('/api/v1/messages?trigger=app_open&elapsed_seconds=2&session_id='.$session)->assertOk()->assertJsonPath('message.opening_only', true);
        $device->update(['opening_started_at' => now()->subSeconds(10)]);
        $this->getJson('/api/v1/messages?trigger=app_open&elapsed_seconds=10&session_id='.$session)->assertOk()->assertJsonPath('message', null);
        $this->assertDatabaseHas('eligibility_checks', ['reason' => 'opening_window_expired']);
    }

    public function test_opening_request_retry_does_not_extend_the_window(): void
    {
        $device = $this->device();
        $session = (string) Str::uuid();
        $this->postJson('/api/v1/opening', ['session_id' => $session])->assertOk();
        $device->update(['opening_started_at' => now()->subMinute()]);
        $this->postJson('/api/v1/opening', ['session_id' => $session])->assertOk();
        $this->assertTrue($device->fresh()->opening_started_at->lt(now()->subSeconds(50)));
    }

    public function test_draft_popup_test_is_restricted_to_marked_devices(): void
    {
        $this->owner();
        $test = Installation::factory()->create(['test_device' => true]);
        $normal = Installation::factory()->create();
        $campaign = Campaign::factory()->create(['status' => 'draft']);
        $this->get('/campaigns/'.$campaign->id.'/preview')->assertOk();
        $this->post('/campaigns/'.$campaign->id.'/test', ['installation_ids' => [$normal->id]])->assertSessionHasErrors('installation_ids.0');
        $this->assertDatabaseCount('deliveries', 0);
        $this->post('/campaigns/'.$campaign->id.'/test', ['installation_ids' => [$test->id]])->assertRedirect();
        $this->assertDatabaseHas('deliveries', ['installation_id' => $test->id, 'is_test' => true]);
        $this->assertSame('draft', $campaign->fresh()->status);
    }

    public function test_connection_reports_are_idempotent_and_reject_sensitive_fields(): void
    {
        $device = $this->device();
        $report = ['id' => (string) Str::uuid(), 'network' => 'mobile', 'checks' => [['name' => 'dns', 'status' => 'ok', 'duration_ms' => 120]]];
        $this->postJson('/api/v1/diagnostics', $report)->assertOk();
        $this->postJson('/api/v1/diagnostics', $report)->assertOk();
        $this->assertDatabaseCount('connection_reports', 1);
        $report['checks'][0]['password'] = 'secret';
        $this->postJson('/api/v1/diagnostics', $report)->assertUnprocessable();
        $this->assertDatabaseHas('connection_reports', ['installation_id' => $device->id]);
    }

    public function test_a_production_campaign_does_not_reuse_a_preview_offer(): void
    {
        $device = Installation::factory()->create(['test_device' => true]);
        $campaign = Campaign::factory()->create();
        $preview = Delivery::create(['id' => (string) Str::uuid(), 'campaign_id' => $campaign->id, 'installation_id' => $device->id, 'status' => 'offered', 'is_test' => true, 'expires_at' => now()->addMinutes(10)]);
        $offer = app(CampaignEngine::class)->reserve($campaign, $device, 'app_open', (string) Str::uuid());
        $this->assertNotNull($offer);
        $this->assertNotSame($preview->id, $offer->id);
        $this->assertFalse($offer->is_test);
    }

    public function test_two_factor_setup_is_required_before_admin_access(): void
    {
        config(['mobile.require_two_factor' => true]);
        $this->owner();
        $this->withSession(['two_factor_started' => time()]);
        $this->get('/')->assertRedirect('/security/setup');
        $this->get('/security/setup')->assertOk();
    }

    public function test_two_factor_secret_is_encrypted_and_recovery_code_is_single_use(): void
    {
        $owner = $this->owner();
        $secret = 'GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ';
        $code = app(Totp::class)->code($secret, intdiv(time(), 30));
        $this->withSession(['two_factor_started' => time(), 'two_factor_setup_secret' => $secret]);
        $this->post('/security/setup', ['code' => $code])->assertRedirect('/security/recovery');
        $this->assertNotSame($secret, DB::table('users')->where('id', $owner->id)->value('two_factor_secret'));
        $codes = session('recovery_codes');
        $this->assertCount(10, $codes);
        $this->withSession(['two_factor_verified' => null, 'two_factor_started' => time()]);
        $this->get('/')->assertRedirect('/security/challenge');
        $this->post('/security/challenge', ['code' => $code])->assertSessionHasErrors('code');
        $this->post('/security/challenge', ['code' => $codes[0]])->assertRedirect('/');
        $this->assertCount(9, $owner->fresh()->two_factor_recovery_codes);
        $this->withSession(['two_factor_verified' => null, 'two_factor_started' => time()]);
        $this->post('/security/challenge', ['code' => $codes[0]])->assertSessionHasErrors('code');
    }

    public function test_revoked_installation_cannot_reconnect_to_management_api(): void
    {
        $this->owner();
        $device = Installation::factory()->create();
        $this->post('/installations/'.$device->id.'/revoke')->assertRedirect();
        $this->device($device);
        $this->getJson('/api/v1/configuration')->assertUnauthorized();
    }

    public function test_launch_pages_render_for_owner_and_escape_user_content(): void
    {
        $this->owner();
        $device = Installation::factory()->create(['test_device' => true]);
        foreach (['/', '/launch', '/configuration', '/installations', '/connections'] as $path) {
            $this->get($path)->assertOk();
        }
        DB::table('site_settings')->insert(['id' => 1, 'payload' => json_encode(['headline' => '<script>alert(1)</script>'])]);
        $this->get('/download')->assertOk()->assertDontSee('<script>alert(1)</script>', false)->assertSee('&lt;script&gt;', false);
    }

    public function test_totp_matches_rfc6238_sha1_reference_vectors(): void
    {
        $totp = app(Totp::class);
        $secret = 'GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ';
        foreach ([59 => '94287082', 1111111109 => '07081804', 1111111111 => '14050471', 1234567890 => '89005924', 2000000000 => '69279037', 20000000000 => '65353130'] as $time => $expected) {
            $this->assertSame($expected, $totp->code($secret, intdiv($time, 30), 8));
        }
    }
}
