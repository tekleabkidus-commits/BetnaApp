<?php

namespace App\Models;

// use Illuminate\Contracts\Auth\MustVerifyEmail;
use Database\Factories\UserFactory;
use Illuminate\Database\Eloquent\Attributes\Fillable;
use Illuminate\Database\Eloquent\Attributes\Hidden;
use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Foundation\Auth\User as Authenticatable;
use Illuminate\Notifications\Notifiable;

#[Fillable(['name', 'email', 'password'])]
#[Hidden(['password', 'remember_token', 'two_factor_secret', 'two_factor_recovery_codes'])]
class User extends Authenticatable
{
    protected $attributes = ['role' => 'viewer'];

    public const ROLES = ['owner', 'super_admin', 'operator', 'viewer'];

    public function roleLabel(): string
    {
        return $this->role === 'super_admin' ? 'SuperAdmin' : ucfirst($this->role);
    }

    /** @param array<int, string> $roles */
    public function canAccessAdminRoute(string $route, array $roles = []): bool
    {
        if (! in_array($this->role, self::ROLES, true)) {
            return false;
        }

        if ($this->role === 'super_admin') {
            foreach (['admin.configuration', 'admin.release', 'admin.vpn', 'admin.cache', 'admin.connections', 'admin.audit', 'admin.staff'] as $excluded) {
                if (str_starts_with($route, $excluded)) {
                    return false;
                }
            }

            return true;
        }

        return $roles === [] || in_array($this->role, $roles, true);
    }

    /** @use HasFactory<UserFactory> */
    use HasFactory, Notifiable;

    /**
     * Get the attributes that should be cast.
     *
     * @return array<string, string>
     */
    protected function casts(): array
    {
        return [
            'email_verified_at' => 'datetime',
            'password' => 'hashed', 'two_factor_secret' => 'encrypted', 'two_factor_recovery_codes' => 'encrypted:array', 'two_factor_confirmed_at' => 'datetime', 'two_factor_last_step' => 'integer',
        ];
    }
}
