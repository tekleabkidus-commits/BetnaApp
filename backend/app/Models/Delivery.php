<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;

class Delivery extends Model
{
    use HasFactory;

    public $incrementing = false;

    protected $keyType = 'string';

    protected $guarded = [];

    protected function casts(): array
    {
        return ['is_test' => 'boolean', 'expires_at' => 'datetime', 'sent_at' => 'datetime', 'displayed_at' => 'datetime', 'clicked_at' => 'datetime', 'dismissed_at' => 'datetime'];
    }
}
