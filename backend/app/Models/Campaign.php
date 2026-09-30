<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;

class Campaign extends Model
{
    use HasFactory;

    protected $guarded = [];

    protected $attributes = ['timezone' => 'Africa/Addis_Ababa', 'max_per_installation' => 1, 'max_per_day' => 1, 'cooldown_minutes' => 1440, 'once_per_session' => true, 'dismissible' => true, 'allow_opt_out' => true, 'delay_seconds' => 0];

    protected function casts(): array
    {
        return ['test_installation_ids' => 'array', 'audience' => 'array', 'translations' => 'array', 'starts_at' => 'datetime', 'ends_at' => 'datetime', 'next_run_at' => 'datetime', 'once_per_session' => 'boolean', 'dismissible' => 'boolean', 'allow_opt_out' => 'boolean'];
    }
}
