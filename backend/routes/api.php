<?php

use App\Http\Controllers\Api\BrowserControlController;
use App\Http\Controllers\Api\DiagnosticsController;
use App\Http\Controllers\Api\InstallationController;
use Illuminate\Support\Facades\Route;

Route::prefix('v1')->group(function (): void {
    Route::get('ping', [DiagnosticsController::class, 'ping'])->middleware('throttle:register');
    Route::post('installations', [InstallationController::class, 'register'])->middleware('throttle:register');
    Route::middleware(['installation', 'throttle:installation'])->group(function (): void {
        Route::post('opening', [DiagnosticsController::class, 'opening']);
        Route::post('diagnostics', [DiagnosticsController::class, 'report']);
        Route::post('location', [BrowserControlController::class, 'location']);
        Route::post('commands/ack', [BrowserControlController::class, 'acknowledge']);
        Route::post('vpn/peer', [BrowserControlController::class, 'peer']);
        Route::post('heartbeat', [InstallationController::class, 'heartbeat']);
        Route::get('configuration', [InstallationController::class, 'configuration']);
        Route::get('messages', [InstallationController::class, 'messages']);
        Route::post('events', [InstallationController::class, 'events']);
        Route::post('opt-out', [InstallationController::class, 'optOut']);
    });
});
