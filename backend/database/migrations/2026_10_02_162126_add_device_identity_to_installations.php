<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        Schema::create('devices', function (Blueprint $table): void {
            $table->uuid('id')->primary();
            $table->string('identifier_hash', 64)->unique();
            $table->timestamps();
        });
        Schema::table('installations', function (Blueprint $table) {
            $table->foreignUuid('device_id')->nullable()->index()->constrained('devices')->nullOnDelete();
        });
    }

    public function down(): void
    {
        Schema::table('installations', function (Blueprint $table) {
            $table->dropConstrainedForeignId('device_id');
        });
        Schema::dropIfExists('devices');
    }
};
