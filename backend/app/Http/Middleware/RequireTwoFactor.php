<?php

namespace App\Http\Middleware;

use Closure;
use Illuminate\Http\Request;
use Symfony\Component\HttpFoundation\Response;

class RequireTwoFactor
{
    public function handle(Request $request, Closure $next): Response
    {
        $user = $request->user()->fresh();
        if (! $user->two_factor_confirmed_at && config('mobile.require_two_factor')) {
            return redirect()->route('two-factor.setup');
        }
        if ($user->two_factor_confirmed_at && $request->session()->get('two_factor_verified') !== $user->id) {
            return redirect()->route('two-factor.challenge');
        }

        return $next($request);
    }
}
