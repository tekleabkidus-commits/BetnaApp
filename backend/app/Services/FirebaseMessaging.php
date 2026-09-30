<?php

namespace App\Services;

use Illuminate\Support\Facades\Cache;
use Illuminate\Support\Facades\Http;

class FirebaseMessaging
{
    private function credentials(): array
    {
        $credentials = json_decode((string) config('mobile.firebase_credentials'), true, 512, JSON_THROW_ON_ERROR);
        if (! isset($credentials['client_email'],$credentials['private_key'],$credentials['project_id'])) {
            throw new \RuntimeException('Firebase credentials not configured');
        }

        return $credentials;
    }

    private function encode(string $value): string
    {
        return rtrim(strtr(base64_encode($value), '+/', '-_'), '=');
    }

    public function send(string $token, array $message): string
    {
        $credentials = $this->credentials();
        $accessToken = Cache::remember('firebase-access-'.hash('sha256', $credentials['client_email']), 3000, function () use ($credentials): string {
            $header = $this->encode(json_encode(['alg' => 'RS256', 'typ' => 'JWT'], JSON_THROW_ON_ERROR));
            $claims = $this->encode(json_encode(['iss' => $credentials['client_email'], 'scope' => 'https://www.googleapis.com/auth/firebase.messaging', 'aud' => 'https://oauth2.googleapis.com/token', 'iat' => time(), 'exp' => time() + 3600], JSON_THROW_ON_ERROR));
            if (! openssl_sign($header.'.'.$claims, $signature, $credentials['private_key'], OPENSSL_ALGO_SHA256)) {
                throw new \RuntimeException('Firebase signing failed');
            }

            return Http::timeout(15)->asForm()->post('https://oauth2.googleapis.com/token', ['grant_type' => 'urn:ietf:params:oauth:grant-type:jwt-bearer', 'assertion' => $header.'.'.$claims.'.'.$this->encode($signature)])->throw()->json('access_token');
        });
        $response = Http::withToken($accessToken)->timeout(20)->post('https://fcm.googleapis.com/v1/projects/'.rawurlencode($credentials['project_id']).'/messages:send', [
            'message' => ['token' => $token, 'data' => ['campaign' => json_encode($message, JSON_UNESCAPED_SLASHES | JSON_THROW_ON_ERROR)], 'android' => ['priority' => 'HIGH', 'ttl' => '3600s']],
        ]);
        if ($response->status() === 404 && str_contains($response->body(), 'UNREGISTERED')) {
            return 'unregistered';
        }
        if ($response->status() === 401) {
            Cache::forget('firebase-access-'.hash('sha256', $credentials['client_email']));
        }
        if (! $response->successful()) {
            throw new \RuntimeException('FCM_HTTP_'.$response->status());
        }

        return 'accepted';
    }
}
