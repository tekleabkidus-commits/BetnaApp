<?php

namespace App\Services;

class Totp
{
    public function secret(): string
    {
        $alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
        $bits = '';
        foreach (str_split(random_bytes(20)) as $byte) {
            $bits .= str_pad(decbin(ord($byte)), 8, '0', STR_PAD_LEFT);
        }
        $out = '';
        foreach (str_split($bits, 5) as $chunk) {
            $out .= $alphabet[bindec($chunk)];
        }

        return $out;
    }

    public function code(string $secret, int $step, int $digits = 6): string
    {
        $bits = '';
        foreach (str_split(strtoupper($secret)) as $char) {
            $position = strpos('ABCDEFGHIJKLMNOPQRSTUVWXYZ234567', $char);
            if ($position === false) {
                throw new \InvalidArgumentException('Invalid authenticator secret.');
            } $bits .= str_pad(decbin($position), 5, '0', STR_PAD_LEFT);
        }
        $key = '';
        foreach (str_split($bits, 8) as $chunk) {
            if (strlen($chunk) === 8) {
                $key .= chr(bindec($chunk));
            }
        }
        $hash = hash_hmac('sha1', pack('N2', intdiv($step, 4294967296), $step % 4294967296), $key, true);
        $offset = ord($hash[19]) & 15;
        $value = (unpack('N', substr($hash, $offset, 4))[1] & 0x7FFFFFFF) % (10 ** $digits);

        return str_pad((string) $value, $digits, '0', STR_PAD_LEFT);
    }

    public function step(string $secret, string $code, int $lastStep = -1, ?int $timestamp = null): ?int
    {
        if (! preg_match('/^\d{6}$/', $code)) {
            return null;
        } $now = intdiv($timestamp ?? time(), 30);
        foreach ([$now, $now - 1, $now + 1] as $step) {
            if ($step > $lastStep && hash_equals($this->code($secret, $step), $code)) {
                return $step;
            }
        }

        return null;
    }
}
