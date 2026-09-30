<?php

return [
    'require_two_factor' => (bool) env('MOBILE_REQUIRE_TWO_FACTOR', true),
    'signing_private_key' => env('MOBILE_SIGNING_PRIVATE_KEY'),
    'configuration_ttl_hours' => (int) env('MOBILE_CONFIGURATION_TTL_HOURS', 168),
    'website_url' => env('MOBILE_WEBSITE_URL', 'https://example.com'),
    'support_url' => env('MOBILE_SUPPORT_URL', ''),
    'firebase_credentials' => env('FIREBASE_CREDENTIALS_JSON'),
    'global_campaign_cap' => (int) env('MOBILE_DAILY_CAMPAIGN_CAP', 5),
    'online_seconds' => 90,
];
