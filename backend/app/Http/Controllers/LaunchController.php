<?php

namespace App\Http\Controllers;

use App\Models\Installation;
use App\Models\Release;
use App\Services\SiteSettings;
use Illuminate\Http\RedirectResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;
use Illuminate\Validation\Rule;
use Illuminate\View\View;

class LaunchController extends Controller
{
    public function index(): View
    {
        return view('admin.launch', ['settings' => SiteSettings::get(), 'releases' => Release::where('published', true)->where('artifact_verified', true)->orderByDesc('version_code')->get(), 'links' => DB::table('download_links')->orderByDesc('id')->get()]);
    }

    public function save(Request $r): RedirectResponse
    {
        $r->merge(['telegram_username' => ltrim(trim((string) $r->input('telegram_username')), '@')]);
        $data = $r->validate(['app_name' => 'required|string|max:80', 'telegram_username' => ['nullable', 'regex:/^[A-Za-z][A-Za-z0-9_]{4,31}$/'], 'download_enabled' => 'required|boolean', 'headline' => 'required|string|max:160', 'description' => 'required|string|max:2000', 'release_id' => ['nullable', 'integer', Rule::exists('releases', 'id')->where('published', true)->where('artifact_verified', true)]]);
        $data['telegram_username'] = $data['telegram_username'] ?? '';
        $data['download_enabled'] = (bool) $data['download_enabled'];
        DB::transaction(function () use ($data, $r): void {
            DB::table('site_settings')->updateOrInsert(['id' => 1], ['payload' => json_encode($data), 'updated_at' => now(), 'created_at' => now()]);
            DB::table('audit_logs')->insert(['user_id' => $r->user()->id, 'action' => 'launch.settings', 'created_at' => now()]);
        });

        return back()->with('status', 'Telegram support and download settings saved.');
    }

    public function createLink(Request $r): RedirectResponse
    {
        $data = $r->validate(['slug' => ['required', 'max:80', 'regex:/^[a-z0-9]+(?:-[a-z0-9]+)*$/', 'unique:download_links,slug'], 'label' => 'required|string|max:120', 'destination' => 'required|in:page,apk']);
        DB::transaction(function () use ($data, $r): void {
            $id = DB::table('download_links')->insertGetId($data + ['enabled' => true, 'created_at' => now(), 'updated_at' => now()]);
            DB::table('audit_logs')->insert(['user_id' => $r->user()->id, 'action' => 'download_link.created', 'subject' => (string) $id, 'created_at' => now()]);
        });

        return back()->with('status', 'Share link created.');
    }

    public function updateLink(Request $r, int $id): RedirectResponse
    {
        $data = $r->validate(['enabled' => 'required|boolean', 'destination' => 'required|in:page,apk', 'label' => 'required|string|max:120']);
        abort_unless(DB::table('download_links')->where('id', $id)->exists(), 404);
        DB::transaction(function () use ($data, $r, $id): void {
            DB::table('download_links')->where('id', $id)->update($data + ['updated_at' => now()]);
            DB::table('audit_logs')->insert(['user_id' => $r->user()->id, 'action' => 'download_link.updated', 'subject' => (string) $id, 'created_at' => now()]);
        });

        return back()->with('status', 'Link updated.');
    }

    public function revoke(Request $r, Installation $installation): RedirectResponse
    {
        $installation->update(['revoked_at' => now(), 'push_token' => null]);
        DB::table('audit_logs')->insert(['user_id' => $r->user()->id, 'action' => 'installation.revoked', 'subject' => $installation->id, 'created_at' => now()]);

        return back()->with('status', 'Installation access revoked. Its website cookies were not changed.');
    }

    public function reports(): View
    {
        return view('admin.connections', ['reports' => DB::table('connection_reports')->orderByDesc('created_at')->paginate(30)]);
    }
}
