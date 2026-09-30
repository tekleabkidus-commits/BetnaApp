<?php

namespace App\Http\Middleware;

use App\Models\Installation;
use Closure;
use Illuminate\Http\Request;
use Symfony\Component\HttpFoundation\Response;

class InstallationToken
{
    public function handle(Request $request, Closure $next): Response
    {
        $token = $request->bearerToken();
        abort_unless($token && str_contains($token, '.'), 401);
        [$id,$secret] = explode('.', $token, 2);
        $installation = Installation::find($id);
        abort_unless($installation && ! $installation->revoked_at && hash_equals($installation->token_hash, hash('sha256', $secret)), 401);
        $request->attributes->set('installation', $installation);

        return $next($request);
    }
}
