<?php

namespace App\Http\Controllers;

use App\Models\User;
use App\Services\Totp;
use Illuminate\Http\RedirectResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Auth;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Hash;
use Illuminate\Validation\ValidationException;
use Illuminate\View\View;

class TwoFactorController extends Controller
{
    private function assertPending(Request $r): void
    {
        if ($r->session()->get('two_factor_verified') === $r->user()->id) {
            return;
        }
        if (time() - (int) $r->session()->get('two_factor_started', 0) > 600) {
            Auth::logout();
            $r->session()->invalidate();
            $r->session()->regenerateToken();
            abort(401, 'Your sign-in expired. Sign in again.');
        }
    }

    public function setup(Request $r, Totp $totp): View|RedirectResponse
    {
        $this->assertPending($r);
        if ($r->user()->two_factor_confirmed_at) {
            return redirect()->route('two-factor.challenge');
        }
        if (! $r->session()->has('two_factor_setup_secret')) {
            $r->session()->put('two_factor_setup_secret', $totp->secret());
        }

        return view('admin.two-factor', ['setup' => true, 'secret' => $r->session()->get('two_factor_setup_secret')]);
    }

    public function confirm(Request $r, Totp $totp): RedirectResponse
    {
        $this->assertPending($r);
        $data = $r->validate(['code' => 'required|string|regex:/^\d{6}$/']);
        $secret = $r->session()->get('two_factor_setup_secret');
        if (! $secret || ($step = $totp->step($secret, $data['code'])) === null) {
            throw ValidationException::withMessages(['code' => 'The authenticator code was not accepted.']);
        }
        $codes = $this->newRecoveryCodes();
        DB::transaction(function () use ($r, $secret, $step, $codes): void {
            $user = User::whereKey($r->user()->id)->lockForUpdate()->firstOrFail();
            abort_if($user->two_factor_confirmed_at, 409);
            $user->forceFill(['two_factor_secret' => $secret, 'two_factor_confirmed_at' => now(), 'two_factor_last_step' => $step, 'two_factor_recovery_codes' => array_map(fn ($code) => Hash::make($code), $codes)])->save();
            $this->audit($user->id, 'two_factor.enabled');
        });
        $r->session()->forget('two_factor_setup_secret');
        $r->session()->regenerate();
        $r->session()->put('two_factor_verified', $r->user()->id);

        return redirect()->route('two-factor.recovery')->with('recovery_codes', $codes);
    }

    public function challenge(Request $r): View|RedirectResponse
    {
        $this->assertPending($r);
        if (! $r->user()->two_factor_confirmed_at) {
            return redirect()->route('two-factor.setup');
        }
        if ($r->session()->get('two_factor_verified') === $r->user()->id) {
            return redirect()->route('admin.dashboard');
        }

        return view('admin.two-factor', ['setup' => false, 'secret' => null]);
    }

    public function verify(Request $r, Totp $totp): RedirectResponse
    {
        $this->assertPending($r);
        $data = $r->validate(['code' => 'required|string|max:40']);
        $accepted = DB::transaction(function () use ($r, $totp, $data): bool {
            $user = User::whereKey($r->user()->id)->lockForUpdate()->firstOrFail();
            if (! $user->two_factor_confirmed_at) {
                return false;
            }
            $step = $totp->step($user->two_factor_secret, $data['code'], $user->two_factor_last_step);
            $hashes = $user->two_factor_recovery_codes ?? [];
            if ($step !== null) {
                $user->two_factor_last_step = $step;
            } else {
                $found = null;
                foreach ($hashes as $index => $hash) {
                    if (Hash::check(strtolower(trim($data['code'])), $hash)) {
                        $found = $index;
                        break;
                    }
                } if ($found === null) {
                    return false;
                } unset($hashes[$found]);
                $user->two_factor_recovery_codes = array_values($hashes);
            }
            $user->save();
            $this->audit($user->id, 'two_factor.verified');

            return true;
        });
        if (! $accepted) {
            throw ValidationException::withMessages(['code' => 'The code was not accepted. Codes can only be used once.']);
        }
        $r->session()->regenerate();
        $r->session()->put('two_factor_verified', $r->user()->id);

        return redirect()->route('admin.dashboard');
    }

    public function recovery(Request $r): View
    {
        return view('admin.recovery', ['codes' => $r->session()->get('recovery_codes', [])]);
    }

    public function rotate(Request $r, Totp $totp): RedirectResponse
    {
        $data = $r->validate(['password' => 'required|string', 'code' => 'required|string|regex:/^\d{6}$/']);
        $codes = $this->newRecoveryCodes();
        DB::transaction(function () use ($r, $totp, $data, $codes): void {
            $user = User::whereKey($r->user()->id)->lockForUpdate()->firstOrFail();
            $step = $user->two_factor_secret ? $totp->step($user->two_factor_secret, $data['code'], $user->two_factor_last_step) : null;
            if (! Hash::check($data['password'], $user->password) || $step === null) {
                throw ValidationException::withMessages(['code' => 'Current password and a fresh authenticator code are required.']);
            }
            $user->forceFill(['two_factor_last_step' => $step, 'two_factor_recovery_codes' => array_map(fn ($code) => Hash::make($code), $codes)])->save();
            $this->audit($user->id, 'two_factor.recovery_rotated');
        });

        return back()->with('recovery_codes', $codes)->with('status', 'Previous recovery codes are now invalid.');
    }

    private function newRecoveryCodes(): array
    {
        return array_map(fn () => bin2hex(random_bytes(8)), range(1, 10));
    }

    private function audit(int $id, string $action): void
    {
        DB::table('audit_logs')->insert(['user_id' => $id, 'action' => $action, 'created_at' => now()]);
    }
}
