<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        Schema::table('users', function (Blueprint $table) {
            $table->string('role')->default('viewer');
        });
        Schema::create('installations', function (Blueprint $t) {
            $t->uuid('id')->primary();
            $t->string('token_hash', 64);
            $t->unsignedInteger('version_code');
            $t->string('version_name', 40);
            $t->unsignedSmallInteger('android_version');
            $t->string('language', 20)->default('en');
            $t->boolean('notifications_enabled')->default(false);
            $t->boolean('promotions_enabled')->default(true);
            $t->text('push_token')->nullable();
            $t->boolean('foreground')->default(false);
            $t->boolean('test_device')->default(false);
            $t->string('label', 80)->nullable();
            $t->timestamp('last_seen_at')->nullable()->index();
            $t->unsignedInteger('session_count')->default(0);
            $t->timestamps();
        });
        Schema::create('app_configurations', function (Blueprint $t) {
            $t->id();
            $t->json('payload');
            $t->boolean('published')->default(false)->index();
            $t->foreignId('created_by')->nullable()->constrained('users');
            $t->timestamps();
        });
        Schema::create('campaigns', function (Blueprint $t) {
            $t->id();
            $t->string('name', 120);
            $t->string('type', 16);
            $t->string('status', 16)->default('draft');
            $t->string('trigger', 24)->default('app_open');
            $t->string('title', 150);
            $t->text('body');
            $t->text('image_url')->nullable();
            $t->text('action_url')->nullable();
            $t->string('button_text', 40)->default('Open');
            $t->json('translations')->nullable();
            $t->json('audience')->nullable();
            $t->timestamp('starts_at')->nullable();
            $t->timestamp('ends_at')->nullable();
            $t->string('timezone')->default('Africa/Addis_Ababa');
            $t->unsignedInteger('repeat_minutes')->nullable();
            $t->timestamp('next_run_at')->nullable()->index();
            $t->unsignedInteger('priority')->default(0);
            $t->unsignedInteger('max_per_installation')->default(1);
            $t->unsignedInteger('max_per_day')->default(1);
            $t->unsignedInteger('cooldown_minutes')->default(1440);
            $t->boolean('once_per_session')->default(true);
            $t->unsignedInteger('delay_seconds')->default(0);
            $t->boolean('dismissible')->default(true);
            $t->boolean('allow_opt_out')->default(true);
            $t->string('quiet_start', 5)->nullable();
            $t->string('quiet_end', 5)->nullable();
            $t->timestamps();
        });
        Schema::create('campaign_runs', function (Blueprint $t) {
            $t->id();
            $t->foreignId('campaign_id')->constrained()->cascadeOnDelete();
            $t->timestamp('scheduled_at');
            $t->string('status', 20)->default('building');
            $t->timestamps();
            $t->unique(['campaign_id', 'scheduled_at']);
        });
        Schema::create('deliveries', function (Blueprint $t) {
            $t->uuid('id')->primary();
            $t->foreignId('campaign_id')->constrained()->cascadeOnDelete();
            $t->foreignUuid('installation_id')->constrained()->cascadeOnDelete();
            $t->foreignId('campaign_run_id')->nullable()->constrained()->cascadeOnDelete();
            $t->uuid('session_id')->nullable();
            $t->string('status', 20)->default('offered');
            $t->timestamp('expires_at');
            $t->timestamp('sent_at')->nullable();
            $t->timestamp('displayed_at')->nullable();
            $t->timestamp('clicked_at')->nullable();
            $t->timestamp('dismissed_at')->nullable();
            $t->string('failure_code', 100)->nullable();
            $t->timestamps();
            $t->unique(['campaign_run_id', 'installation_id']);
            $t->index(['installation_id', 'campaign_id', 'created_at']);
        });
        Schema::create('campaign_opt_outs', function (Blueprint $t) {
            $t->id();
            $t->foreignId('campaign_id')->constrained()->cascadeOnDelete();
            $t->foreignUuid('installation_id')->constrained()->cascadeOnDelete();
            $t->timestamps();
            $t->unique(['campaign_id', 'installation_id']);
        });
        Schema::create('eligibility_checks', function (Blueprint $t) {
            $t->id();
            $t->foreignUuid('installation_id')->constrained()->cascadeOnDelete();
            $t->foreignId('campaign_id')->nullable()->constrained()->cascadeOnDelete();
            $t->string('reason', 80);
            $t->string('trigger', 24);
            $t->timestamp('created_at')->index();
        });
        Schema::create('telemetry_events', function (Blueprint $t) {
            $t->uuid('id')->primary();
            $t->foreignUuid('installation_id')->constrained()->cascadeOnDelete();
            $t->string('type', 40)->index();
            $t->uuid('session_id')->nullable();
            $t->uuid('delivery_id')->nullable();
            $t->unsignedInteger('version_code');
            $t->string('host', 253)->nullable();
            $t->unsignedInteger('duration_ms')->nullable();
            $t->string('code', 80)->nullable();
            $t->timestamp('occurred_at');
            $t->timestamp('created_at')->index();
        });
        Schema::create('releases', function (Blueprint $t) {
            $t->id();
            $t->unsignedInteger('version_code')->unique();
            $t->string('version_name', 40);
            $t->text('notes')->nullable();
            $t->text('apk_url');
            $t->json('backup_urls')->nullable();
            $t->string('sha256', 64);
            $t->unsignedBigInteger('size_bytes');
            $t->unsignedInteger('min_android')->default(26);
            $t->unsignedInteger('minimum_supported_version')->default(1);
            $t->unsignedTinyInteger('rollout_percent')->default(100);
            $t->unsignedInteger('remind_hours')->default(24);
            $t->boolean('published')->default(false);
            $t->boolean('artifact_verified')->default(false);
            $t->timestamps();
        });
        Schema::create('audit_logs', function (Blueprint $t) {
            $t->id();
            $t->foreignId('user_id')->nullable()->constrained()->nullOnDelete();
            $t->string('action', 80);
            $t->string('subject', 120)->nullable();
            $t->json('details')->nullable();
            $t->timestamp('created_at')->index();
        });
    }

    public function down(): void
    {
        foreach (['audit_logs', 'releases', 'telemetry_events', 'eligibility_checks', 'campaign_opt_outs', 'deliveries', 'campaign_runs', 'campaigns', 'app_configurations', 'installations'] as $name) {
            Schema::dropIfExists($name);
        }
        Schema::table('users', function (Blueprint $table) {
            $table->dropColumn('role');
        });
    }
};
