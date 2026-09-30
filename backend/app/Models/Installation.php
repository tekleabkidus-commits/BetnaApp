<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;

class Installation extends Model
{
    use HasFactory;

    public $incrementing = false;

    protected $keyType = 'string';

    protected $guarded = [];

    protected $hidden = ['token_hash', 'push_token'];

    protected function casts(): array
    {
        return ['push_available' => 'boolean', 'low_ram' => 'boolean', 'capabilities' => 'array', 'opening_started_at' => 'datetime', 'version_code' => 'integer', 'android_version' => 'integer', 'session_count' => 'integer', 'notifications_enabled' => 'boolean', 'promotions_enabled' => 'boolean', 'test_device' => 'boolean', 'foreground' => 'boolean', 'last_seen_at' => 'datetime', 'push_token' => 'encrypted'];
    }
}
