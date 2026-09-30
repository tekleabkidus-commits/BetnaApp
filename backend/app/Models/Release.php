<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;

class Release extends Model
{
    protected $guarded = [];

    protected function casts(): array
    {
        return ['version_code' => 'integer', 'size_bytes' => 'integer', 'min_android' => 'integer', 'minimum_supported_version' => 'integer', 'rollout_percent' => 'integer', 'backup_urls' => 'array', 'published' => 'boolean', 'artifact_verified' => 'boolean'];
    }
}
