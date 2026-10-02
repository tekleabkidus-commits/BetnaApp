<?php

namespace Tests\Feature;

use App\Models\Campaign;
use App\Models\Installation;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Str;
use Tests\TestCase;

class WorkspaceDesignTest extends TestCase
{
    use RefreshDatabase;

    private function signIn(string $role = 'owner'): void
    {
        config(['mobile.require_two_factor' => false]);
        $this->actingAs(User::factory()->create(['role' => $role]));
    }

    public function test_dashboard_and_campaign_components_render_with_real_report_data(): void
    {
        $this->signIn();
        $device = Installation::factory()->create(['version_code' => 4, 'version_name' => '0.4.0']);
        DB::table('telemetry_events')->insert(['id' => (string) Str::uuid(), 'installation_id' => $device->id, 'type' => 'app_open', 'version_code' => 4, 'occurred_at' => now(), 'created_at' => now()]);
        $campaign = Campaign::factory()->create(['name' => '<script>alert(1)</script>']);
        $this->get('/')->assertOk()->assertSee('data-activity-chart', false)->assertSee('0.4.0');
        $this->get('/campaigns')->assertOk()->assertSee($campaign->name)->assertDontSee($campaign->name, false);
        $this->get('/campaigns/'.$campaign->id.'/preview')->assertOk();
        foreach (['/installations', '/configuration', '/releases', '/audit', '/locations', '/cache', '/vpn', '/launch', '/connections', '/staff', '/security/recovery'] as $url) {
            $this->get($url)->assertOk();
        }
    }

    public function test_device_search_combines_case_insensitive_model_version_group_and_online_filters(): void
    {
        $this->signIn();
        $expected = Installation::factory()->create(['label' => 'Staff phone', 'model' => 'Pixel 9', 'version_code' => 4, 'test_device' => true, 'foreground' => true, 'last_seen_at' => now()]);
        Installation::factory()->create(['model' => 'Pixel 9', 'version_code' => 3, 'test_device' => true, 'foreground' => true, 'last_seen_at' => now()]);
        Installation::factory()->create(['model' => 'Pixel 9', 'version_code' => 4, 'test_device' => true, 'foreground' => true, 'last_seen_at' => now()->subMinutes(5)]);
        $response = $this->get('/installations?q=pixel&version=4&test=1&online=1')->assertOk();
        $this->assertSame([$expected->id], $response->viewData('devices')->pluck('id')->all());
        $this->get('/installations?q='.$expected->id)->assertOk()->assertViewHas('devices', fn ($devices) => $devices->total() === 1);
        $this->get('/installations?test=invalid')->assertSessionHasErrors('test');
    }

    public function test_device_filters_survive_pagination_and_viewers_keep_read_only_navigation(): void
    {
        $this->signIn('viewer');
        Installation::factory()->count(32)->create(['label' => 'Staff phone', 'test_device' => true]);
        $response = $this->get('/installations?q=Staff&test=1')->assertOk();
        $this->assertStringContainsString('q=Staff', $response->viewData('devices')->nextPageUrl());
        $this->assertStringContainsString('test=1', $response->viewData('devices')->nextPageUrl());
        $response->assertDontSee('data-page-name="Betna VPN"', false)->assertDontSee('data-page-name="Cache controls"', false);
        $this->get('/vpn')->assertForbidden();
    }
}
