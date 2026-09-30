<?php

namespace App\Http\Controllers;

use App\Services\SiteSettings;
use Illuminate\Http\RedirectResponse;
use Illuminate\Support\Facades\DB;
use Illuminate\View\View;

class DownloadController extends Controller
{
    public function page(): View
    {
        return view('download', ['settings' => SiteSettings::get(), 'release' => SiteSettings::release(), 'support' => SiteSettings::supportUrl()]);
    }

    public function apk(): RedirectResponse
    {
        $release = SiteSettings::release();
        abort_unless($release, 404, 'No verified public release is available.');

        return redirect()->away($release->apk_url)->header('Cache-Control', 'no-store');
    }

    public function support(): RedirectResponse
    {
        $url = SiteSettings::supportUrl();
        abort_unless($url, 404, 'Support has not been configured.');

        return redirect()->away($url)->header('Cache-Control', 'no-store');
    }

    public function link(string $slug): RedirectResponse
    {
        $link = DB::table('download_links')->where('slug', $slug)->where('enabled', true)->first();
        abort_unless($link, 404);
        DB::table('download_links')->where('id', $link->id)->increment('requests');

        return redirect()->route($link->destination === 'apk' ? 'download.apk' : 'download.page')->header('Cache-Control', 'no-store');
    }
}
