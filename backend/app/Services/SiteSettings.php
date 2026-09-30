<?php

namespace App\Services;

use App\Models\Release;
use Illuminate\Support\Facades\DB;

class SiteSettings
{
    public static function get(): array
    {
        $raw = DB::table('site_settings')->where('id', 1)->value('payload');

        return array_replace(['app_name' => 'Betna', 'telegram_username' => '', 'download_enabled' => false, 'headline' => 'Download Betna App', 'description' => 'Access Betna on your Android phone.', 'release_id' => null], $raw ? json_decode($raw, true, 512, JSON_THROW_ON_ERROR) : []);
    }

    public static function supportUrl(): string
    {
        $username = self::get()['telegram_username'];

        return $username ? 'https://t.me/'.$username : '';
    }

    public static function release(): ?Release
    {
        $s = self::get();
        if (! $s['download_enabled']) {
            return null;
        }
        $query = Release::where('published', true)->where('artifact_verified', true);

        return $s['release_id'] ? $query->whereKey($s['release_id'])->first() : $query->where('rollout_percent', 100)->orderByDesc('version_code')->first();
    }
}
