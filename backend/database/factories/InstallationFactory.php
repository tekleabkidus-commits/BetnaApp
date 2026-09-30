<?php

namespace Database\Factories;

use Illuminate\Database\Eloquent\Factories\Factory;
use Illuminate\Support\Str;

class InstallationFactory extends Factory
{
    public function definition(): array
    {
        return ['id' => (string) Str::uuid(), 'token_hash' => hash('sha256', 'test-secret'), 'version_code' => 1, 'version_name' => '1.0.0', 'android_version' => 35, 'language' => 'en', 'notifications_enabled' => true, 'last_seen_at' => now(), 'promotions_enabled' => true];
    }
}
