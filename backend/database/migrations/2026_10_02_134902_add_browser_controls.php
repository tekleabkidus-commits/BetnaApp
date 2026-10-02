<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        Schema::table('installations', function (Blueprint $t): void {
            $t->string('location_permission', 20)->default('unknown');
            $t->string('vpn_status', 24)->default('off');
            $t->unsignedBigInteger('vpn_rx_bytes')->default(0);
            $t->unsignedBigInteger('vpn_tx_bytes')->default(0);
        });
        Schema::create('device_locations', function (Blueprint $t): void {
            $t->uuid('id')->primary();
            $t->foreignUuid('installation_id')->constrained()->cascadeOnDelete();
            $t->decimal('latitude', 10, 6);
            $t->decimal('longitude', 10, 6);
            $t->decimal('accuracy_m', 12, 2);
            $t->string('precision', 16);
            $t->timestamp('observed_at');
            $t->timestamp('created_at');
            $t->index(['installation_id', 'observed_at']);
            $t->index('created_at');
        });
        Schema::create('device_commands', function (Blueprint $t): void {
            $t->uuid('id')->primary();
            $t->foreignUuid('installation_id')->constrained()->cascadeOnDelete();
            $t->string('type', 24);
            $t->string('timing', 24);
            $t->string('status', 24)->default('pending');
            $t->string('failure_code', 80)->nullable();
            $t->foreignId('created_by')->constrained('users');
            $t->timestamp('expires_at');
            $t->timestamp('completed_at')->nullable();
            $t->timestamps();
            $t->index(['installation_id', 'status']);
        });
        Schema::create('vpn_usage_days', function (Blueprint $t): void {
            $t->id();
            $t->foreignUuid('installation_id')->constrained()->cascadeOnDelete();
            $t->date('day');
            $t->unsignedBigInteger('rx_bytes')->default(0);
            $t->unsignedBigInteger('tx_bytes')->default(0);
            $t->unique(['installation_id', 'day']);
        });
        Schema::create('vpn_peers', function (Blueprint $t): void {
            $t->id();
            $t->foreignUuid('installation_id')->unique()->constrained()->cascadeOnDelete();
            $t->string('public_key', 44)->unique();
            $t->boolean('provisioned')->default(false);
            $t->timestamps();
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('vpn_usage_days');
        Schema::dropIfExists('vpn_peers');
        Schema::dropIfExists('device_commands');
        Schema::dropIfExists('device_locations');
        Schema::table('installations', fn (Blueprint $t) => $t->dropColumn(['location_permission', 'vpn_status', 'vpn_rx_bytes', 'vpn_tx_bytes']));
    }
};
