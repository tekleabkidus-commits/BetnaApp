<?php

namespace App\Console\Commands;

use App\Models\Release;
use Illuminate\Console\Command;
use Illuminate\Support\Facades\Http;
use Psr\Http\Message\ResponseInterface;
use Symfony\Component\Process\Process;

class VerifyRelease extends Command
{
    protected $signature = 'releases:verify {release} {--package=} {--certificate=} {--apksigner=apksigner} {--aapt=aapt2}';

    protected $description = 'Download and verify an APK hash, Android package, version and signing identity before publication';

    public function handle(): int
    {
        $release = Release::findOrFail($this->argument('release'));
        if (! $this->option('package') || ! preg_match('/^[a-fA-F0-9]{64}$/', (string) $this->option('certificate'))) {
            $this->error('Provide the expected package and SHA-256 signing certificate fingerprint without colons.');

            return self::FAILURE;
        }
        $file = tempnam(sys_get_temp_dir(), 'apk-verify-');
        try {
            Http::timeout(180)->withOptions(['sink' => $file, 'on_headers' => function (ResponseInterface $response) use ($release): void {
                $size = (int) $response->getHeaderLine('Content-Length');
                if ($size > $release->size_bytes) {
                    throw new \RuntimeException('Remote APK exceeds the declared size.');
                }
            }, 'progress' => function (int $total, int $downloaded) use ($release): void {
                if ($downloaded > $release->size_bytes) {
                    throw new \RuntimeException('APK download exceeded its declared size.');
                }
            }, 'allow_redirects' => ['max' => 5, 'protocols' => ['https']]])->get($release->apk_url)->throw();
            if (filesize($file) !== $release->size_bytes || ! hash_equals(strtolower($release->sha256), hash_file('sha256', $file))) {
                throw new \RuntimeException('APK hash or size does not match.');
            }
            $signature = new Process([$this->option('apksigner'), 'verify', '--verbose', '--print-certs', $file]);
            $signature->setTimeout(60);
            $signature->mustRun();
            if (! preg_match('/Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]+)/', $signature->getOutput(), $match) || ! hash_equals(strtolower($this->option('certificate')), strtolower($match[1]))) {
                throw new \RuntimeException('Signing identity does not match.');
            }
            $manifest = new Process([$this->option('aapt'), 'dump', 'badging', $file]);
            $manifest->mustRun();
            if (! preg_match("/package: name='([^']+)' versionCode='([0-9]+)'/", $manifest->getOutput(), $match) || $match[1] !== $this->option('package') || (int) $match[2] !== $release->version_code) {
                throw new \RuntimeException('Package identity or version does not match.');
            }
            $release->update(['artifact_verified' => true]);
            $this->info('APK verified. It can now be published in the back office.');

            return self::SUCCESS;
        } catch (\Throwable $e) {
            $release->update(['artifact_verified' => false]);
            $this->error($e->getMessage());

            return self::FAILURE;
        } finally {
            @unlink($file);
        }
    }
}
