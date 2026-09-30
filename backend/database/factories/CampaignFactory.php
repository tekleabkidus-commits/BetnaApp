<?php

namespace Database\Factories;

use Illuminate\Database\Eloquent\Factories\Factory;

class CampaignFactory extends Factory
{
    public function definition(): array
    {
        return ['name' => 'Test announcement', 'type' => 'popup', 'status' => 'active', 'trigger' => 'app_open', 'title' => 'Welcome', 'body' => 'A message', 'audience' => ['mode' => 'all', 'percentage' => 100, 'rules' => []], 'starts_at' => now()->subMinute(), 'ends_at' => now()->addDay(), 'max_per_installation' => 1, 'max_per_day' => 1, 'cooldown_minutes' => 60, 'once_per_session' => true, 'dismissible' => true, 'allow_opt_out' => true];
    }
}
