<?php

use App\Http\Controllers\AdminController;
use App\Http\Controllers\CampaignPreviewController;
use App\Http\Controllers\DownloadController;
use App\Http\Controllers\LaunchController;
use App\Http\Controllers\TwoFactorController;
use Illuminate\Support\Facades\Route;

Route::get('/login', [AdminController::class, 'loginForm'])->name('login');
Route::post('/login', [AdminController::class, 'login'])->middleware('throttle:admin-login');
Route::get('/download', [DownloadController::class, 'page'])->name('download.page');
Route::get('/download/apk', [DownloadController::class, 'apk'])->name('download.apk');
Route::get('/support/telegram', [DownloadController::class, 'support'])->name('support.telegram');
Route::get('/get/{slug}', [DownloadController::class, 'link'])->where('slug', '[a-z0-9-]+')->name('download.link');
Route::middleware('auth')->group(function (): void {
    Route::post('/logout', [AdminController::class, 'logout'])->name('logout');
    Route::get('/security/setup', [TwoFactorController::class, 'setup'])->name('two-factor.setup');
    Route::post('/security/setup', [TwoFactorController::class, 'confirm'])->middleware('throttle:two-factor')->name('two-factor.confirm');
    Route::get('/security/challenge', [TwoFactorController::class, 'challenge'])->name('two-factor.challenge');
    Route::post('/security/challenge', [TwoFactorController::class, 'verify'])->middleware('throttle:two-factor')->name('two-factor.verify');
});
Route::middleware(['auth', 'two-factor'])->group(function (): void {
    Route::get('/security/recovery', [TwoFactorController::class, 'recovery'])->name('two-factor.recovery');
    Route::post('/security/recovery', [TwoFactorController::class, 'rotate'])->middleware('throttle:two-factor')->name('two-factor.rotate');
    Route::get('/launch', [LaunchController::class, 'index'])->name('admin.launch');
    Route::get('/connections', [LaunchController::class, 'reports'])->name('admin.connections');
    Route::get('/', [AdminController::class, 'dashboard'])->name('admin.dashboard');
    Route::get('/reports/export', [AdminController::class, 'export'])->name('admin.export');
    Route::get('/installations', [AdminController::class, 'installations'])->name('admin.installations');
    Route::get('/campaigns', [AdminController::class, 'campaigns'])->name('admin.campaigns');
    Route::get('/campaigns/new', [AdminController::class, 'campaignForm'])->name('admin.campaign.new');
    Route::get('/campaigns/{campaign}/preview', [CampaignPreviewController::class, 'preview'])->name('admin.campaign.preview');
    Route::get('/campaigns/{campaign}/report', [AdminController::class, 'campaignReport'])->name('admin.campaign.report');
    Route::get('/campaigns/{campaign}/edit', [AdminController::class, 'campaignForm'])->name('admin.campaign.edit');
    Route::get('/configuration', [AdminController::class, 'configuration'])->name('admin.configuration');
    Route::get('/releases', [AdminController::class, 'releases'])->name('admin.releases');
    Route::get('/audit', [AdminController::class, 'auditLogs'])->name('admin.audit');
    Route::middleware('role:owner,operator')->group(function (): void {
        Route::post('/campaigns/{campaign}/test', [CampaignPreviewController::class, 'send'])->middleware('throttle:two-factor')->name('admin.campaign.test');
        Route::post('/campaigns', [AdminController::class, 'saveCampaign'])->name('admin.campaign.create');
        Route::post('/campaigns/{campaign}', [AdminController::class, 'saveCampaign'])->name('admin.campaign.save');
        Route::post('/campaigns/{campaign}/action', [AdminController::class, 'campaignAction'])->name('admin.campaign.action');
        Route::post('/installations/{installation}', [AdminController::class, 'updateInstallation'])->name('admin.installation.save');
    });
    Route::middleware('role:owner')->group(function (): void {
        Route::post('/installations/{installation}/revoke', [LaunchController::class, 'revoke'])->name('admin.installation.revoke');
        Route::post('/launch', [LaunchController::class, 'save'])->name('admin.launch.save');
        Route::post('/launch/links', [LaunchController::class, 'createLink'])->name('admin.links.create');
        Route::post('/launch/links/{id}', [LaunchController::class, 'updateLink'])->name('admin.links.update');
        Route::post('/configuration/{configuration}/promote', [AdminController::class, 'promoteConfiguration'])->name('admin.configuration.promote');
        Route::post('/configuration', [AdminController::class, 'saveConfiguration'])->name('admin.configuration.save');
        Route::post('/configuration/{configuration}/rollback', [AdminController::class, 'rollback'])->name('admin.configuration.rollback');
        Route::post('/releases', [AdminController::class, 'saveRelease'])->name('admin.release.create');
        Route::post('/releases/{release}/action', [AdminController::class, 'releaseAction'])->name('admin.release.action');
        Route::get('/staff', [AdminController::class, 'staff'])->name('admin.staff');
        Route::post('/staff', [AdminController::class, 'saveStaff'])->name('admin.staff.create');
    });
});
