<?php

namespace App\Services;

use App\Models\Device;
use App\Models\Installation;
use Illuminate\Support\Str;

class DeviceIdentity
{
    public function attach(Installation $installation, ?string $identifier): void
    {
        if (! $identifier || $installation->device_id) {
            return;
        }

        $hash = hash_hmac('sha256', 'betna-device:v1:'.strtolower($identifier), config('app.key'));
        $device = Device::firstOrCreate(['identifier_hash' => $hash], ['id' => (string) Str::uuid()]);
        $installation->device_id = $device->id;
    }
}
