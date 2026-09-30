<?php

namespace App\Providers;

use Illuminate\Cache\RateLimiting\Limit;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\RateLimiter;
use Illuminate\Support\ServiceProvider;

class AppServiceProvider extends ServiceProvider
{
    public function register(): void {}

    public function boot(): void
    {
        RateLimiter::for('register', fn (Request $r) => Limit::perMinute(20)->by($r->ip()));
        RateLimiter::for('installation', fn (Request $r) => Limit::perMinute(120)->by($r->attributes->get('installation')?->id ?? $r->ip()));
        RateLimiter::for('two-factor', fn (Request $r) => [Limit::perMinute(5)->by('2fa-user:'.($r->user()?->id ?? 'guest')), Limit::perMinute(20)->by('2fa-ip:'.$r->ip())]);
        RateLimiter::for('admin-login', fn (Request $r) => [Limit::perMinute(5)->by(strtolower((string) $r->input('email')).'|'.$r->ip()), Limit::perMinute(30)->by($r->ip())]);
    }
}
