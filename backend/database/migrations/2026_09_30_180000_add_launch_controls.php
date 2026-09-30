<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        Schema::table('app_configurations', function (Blueprint $t): void {
            $t->string('scope')->default('all');
            $t->json('installation_ids')->nullable();
        });
        Schema::table('users', function (Blueprint $t): void {
            $t->text('two_factor_secret')->nullable();
            $t->timestamp('two_factor_confirmed_at')->nullable();
            $t->bigInteger('two_factor_last_step')->default(-1);
            $t->text('two_factor_recovery_codes')->nullable();
        });
        Schema::table('campaigns', function (Blueprint $t): void {
            $t->string('delivery_scope')->default('all');
            $t->json('test_installation_ids')->nullable();
        });
        Schema::table('deliveries', function (Blueprint $t): void {
            $t->boolean('is_test')->default(false);
        });
        Schema::table('installations', function (Blueprint $t): void {
            $t->timestamp('revoked_at')->nullable();
            $t->string('manufacturer', 80)->nullable();
            $t->string('model', 120)->nullable();
            $t->string('webview_version', 80)->nullable();
            $t->boolean('push_available')->default(false);
            $t->boolean('low_ram')->default(false);
            $t->uuid('last_open_session_id')->nullable();
            $t->timestamp('opening_started_at')->nullable();
            $t->json('capabilities')->nullable();
        });
        Schema::create('site_settings', function (Blueprint $t): void {
            $t->unsignedInteger('id')->primary();
            $t->json('payload');
            $t->timestamps();
        });
        Schema::create('download_links', function (Blueprint $t): void {
            $t->id();
            $t->string('slug', 80)->unique();
            $t->string('label', 120);
            $t->string('destination')->default('page');
            $t->boolean('enabled')->default(true);
            $t->unsignedBigInteger('requests')->default(0);
            $t->timestamps();
        });
        Schema::create('connection_reports', function (Blueprint $t): void {
            $t->uuid('id')->primary();
            $t->foreignUuid('installation_id')->constrained()->cascadeOnDelete();
            $t->json('checks');
            $t->string('network', 20);
            $t->timestamp('created_at')->index();
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('connection_reports');
        Schema::dropIfExists('download_links');
        Schema::dropIfExists('site_settings');
        Schema::table('campaigns', function (Blueprint $t): void {
            $t->dropColumn(['delivery_scope', 'test_installation_ids']);
        });
        Schema::table('deliveries', function (Blueprint $t): void {
            $t->dropColumn('is_test');
        });
        Schema::table('installations', function (Blueprint $t): void {
            $t->dropColumn(['revoked_at', 'manufacturer', 'model', 'webview_version', 'push_available', 'low_ram', 'last_open_session_id', 'opening_started_at', 'capabilities']);
        });
        Schema::table('users', function (Blueprint $t): void {
            $t->dropColumn(['two_factor_secret', 'two_factor_confirmed_at', 'two_factor_last_step', 'two_factor_recovery_codes']);
        });
        Schema::table('app_configurations', function (Blueprint $t): void {
            $t->dropColumn(['scope', 'installation_ids']);
        });
    }
};
