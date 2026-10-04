<?php

namespace Tests\Feature;

use App\Models\AppConfiguration;
use App\Models\Campaign;
use App\Models\Device;
use App\Models\Installation;
use App\Models\Release;
use App\Models\User;
use App\Services\ConfigurationService;
use App\Services\SiteSettings;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\Hash;
use Tests\TestCase;

class SuperAdminAccessTest extends TestCase
{
    use RefreshDatabase;

    private function signIn(string $role = 'super_admin'): User
    {
        config(['mobile.require_two_factor' => false]);
        $user = User::factory()->create(['role' => $role]);
        $this->actingAs($user);

        return $user;
    }

    public function test_super_admin_cannot_read_any_excluded_page_or_submit_its_actions(): void
    {
        $this->signIn();
        $configuration = AppConfiguration::create(['payload' => ConfigurationService::defaults(), 'scope' => 'all', 'published' => true]);
        $release = Release::create(['version_code' => 6, 'version_name' => '0.6.0', 'apk_url' => 'https://cdn.invalid/betna.apk', 'sha256' => str_repeat('a', 64), 'size_bytes' => 42]);
        foreach (['/configuration', '/releases', '/vpn', '/cache', '/connections', '/audit', '/staff'] as $url) {
            $this->get($url)->assertForbidden();
        }
        foreach (['/configuration', '/configuration/'.$configuration->id.'/promote', '/configuration/'.$configuration->id.'/rollback', '/releases', '/releases/'.$release->id.'/action', '/vpn', '/vpn/peers/1', '/cache', '/staff'] as $url) {
            $this->post($url, ['role' => 'owner', 'target' => 'all', 'timing' => 'immediate'])->assertForbidden();
        }
        $this->assertDatabaseCount('device_commands', 0);
        $this->assertDatabaseCount('users', 1);
        $this->assertDatabaseCount('app_configurations', 1);
        $this->assertDatabaseCount('releases', 1);
    }

    public function test_super_admin_can_use_every_other_page_and_allowed_owner_actions(): void
    {
        $this->signIn();
        $identity = Device::factory()->create();
        $installation = Installation::factory()->create(['device_id' => $identity->id]);
        $campaign = Campaign::factory()->create();
        foreach (['/', '/installations', '/campaigns', '/campaigns/new', '/campaigns/'.$campaign->id.'/edit', '/campaigns/'.$campaign->id.'/preview', '/campaigns/'.$campaign->id.'/report', '/launch', '/locations', '/locations/export', '/reports/export', '/devices/'.$installation->id, '/device-history/'.$identity->id, '/security/recovery'] as $url) {
            $this->get($url)->assertOk();
        }
        $this->post('/launch', SiteSettings::get())->assertRedirect()->assertSessionHasNoErrors();
        $this->post('/launch/links', ['slug' => 'super-admin', 'label' => 'Staff link', 'destination' => 'page'])->assertRedirect()->assertSessionHasNoErrors();
        $this->assertDatabaseHas('download_links', ['slug' => 'super-admin']);
        $this->post('/installations/'.$installation->id, ['label' => 'Test phone', 'test_device' => true])->assertRedirect()->assertSessionHasNoErrors();
        $this->assertDatabaseHas('installations', ['id' => $installation->id, 'label' => 'Test phone', 'test_device' => true]);
        $this->post('/installations/'.$installation->id.'/revoke')->assertRedirect()->assertSessionHasNoErrors();
        $this->assertNotNull($installation->fresh()->revoked_at);
        $this->post('/campaigns/'.$campaign->id.'/action', ['action' => 'pause'])->assertRedirect()->assertSessionHasNoErrors();
    }

    public function test_navigation_hides_excluded_pages_and_device_cache_details(): void
    {
        $this->signIn();
        $response = $this->get('/')->assertOk()->assertSee('SuperAdmin access');
        foreach (['Connection & tabs', 'App releases', 'Betna VPN', 'Cache controls', 'Connection reports', 'Activity log', 'Staff access'] as $label) {
            $response->assertDontSee('data-page-name="'.$label.'"', false);
        }
        $response->assertSee('data-page-name="Location insights"', false)->assertSee('data-page-name="Support &amp; downloads"', false)->assertDontSee('<div class="nav-label">App controls</div>', false);
        $this->get('/devices/'.Installation::factory()->create()->id)->assertOk()->assertDontSee('Cache commands');
    }

    public function test_owner_can_create_super_admin_without_losing_existing_access(): void
    {
        $owner = $this->signIn('owner');
        $this->get('/staff')->assertOk()->assertSee('SuperAdmin');
        $this->post('/staff', ['name' => 'Operations lead', 'email' => 'super@example.test', 'password' => 'Test password 2026!', 'role' => 'super_admin'])->assertRedirect()->assertSessionHasNoErrors();
        $new = User::where('email', 'super@example.test')->firstOrFail();
        $this->assertSame('super_admin', $new->role);
        $this->assertTrue(Hash::check('Test password 2026!', $new->password));
        $this->assertSame('owner', $owner->fresh()->role);
        foreach (['/configuration', '/releases', '/vpn', '/cache', '/connections', '/audit', '/staff'] as $url) {
            $this->get($url)->assertOk();
        }
        $this->post('/staff', ['name' => 'Invalid', 'email' => 'invalid@example.test', 'password' => 'Test password 2026!', 'role' => 'root'])->assertSessionHasErrors('role');
    }

    public function test_existing_roles_do_not_receive_super_admin_write_privileges(): void
    {
        foreach (['operator', 'viewer'] as $role) {
            $this->signIn($role);
            $this->post('/launch', SiteSettings::get())->assertForbidden();
            $this->post('/staff')->assertForbidden();
        }
        $this->signIn('invalid');
        $this->get('/')->assertForbidden();
    }
}
