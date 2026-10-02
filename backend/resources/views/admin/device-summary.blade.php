<section class="panel">
    <div class="panel-heading"><div><h2>Device recognition</h2><p>All registered installations · Android device and user profile</p></div><x-admin.icon name="devices" class="muted" /></div>
    <div class="stats">
        <x-admin.stat label="Recognized devices" :value="number_format($identityStats['devices'])" note="Distinct app-scoped device identities" icon="devices" />
        <x-admin.stat label="Repeat installations" :value="number_format($identityStats['repeats'])" note="Additional installations on recognized devices" icon="activity" accent="violet" />
        <x-admin.stat label="Unlinked installations" :value="number_format($identityStats['unlinked'])" note="An identity has not been reported yet" icon="clock" accent="blue" />
    </div>
    <p class="table-caption">A new installation can mean a reinstall, cleared app data or a repeated registration. Android cannot confirm an uninstall. Older app records link after updating; model, IP address and location are never used to guess a match.</p>
</section>
