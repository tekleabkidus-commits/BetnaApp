<?php

use App\Models\User;
use Illuminate\Support\Facades\Artisan;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Schedule;

Artisan::command('app:admin {email} {--name=Owner}', function (): void {
    if (User::where('email', $this->argument('email'))->exists()) {
        $this->error('This account already exists.');

        return;
    }
    $password = $this->secret('Choose a password (at least 14 characters)');
    if (strlen((string) $password) < 14) {
        $this->error('Password must contain at least 14 characters.');

        return;
    }
    $user = User::create(['email' => $this->argument('email'), 'name' => $this->option('name'), 'password' => $password]);
    $user->role = 'owner';
    $user->save();
    $this->info('Owner account created.');
})->purpose('Create the first owner without a default password');
Artisan::command('mobile:signing-key', function (): void {
    $key = openssl_pkey_new(['private_key_bits' => 2048, 'private_key_type' => OPENSSL_KEYTYPE_RSA]);
    openssl_pkey_export($key, $private);
    $public = openssl_pkey_get_details($key)['key'];
    $this->line('MOBILE_SIGNING_PRIVATE_KEY='.base64_encode($private));
    $this->line('CONFIG_PUBLIC_KEY='.preg_replace('/-----[^-]+-----|\s/', '', $public));
    $this->warn('Keep the private key secret; pin only CONFIG_PUBLIC_KEY in the Android build.');
})->purpose('Generate a private server key and an Android public key pin');
Artisan::command('reports:prune {--days=90}', function (): void {
    $cutoff = now()->subDays(max(7, (int) $this->option('days')));
    DB::table('telemetry_events')->where('created_at', '<', $cutoff)->delete();
    DB::table('eligibility_checks')->where('created_at', '<', $cutoff)->delete();
    DB::table('connection_reports')->where('created_at', '<', $cutoff)->delete();
    $this->info('Old event detail removed; installation and delivery records retained.');
})->purpose('Remove detailed telemetry beyond the configured retention period');
Schedule::command('campaigns:dispatch')->everyMinute()->withoutOverlapping(10)->onOneServer();
Schedule::command('reports:prune')->daily()->withoutOverlapping()->onOneServer();
