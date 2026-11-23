# Third-party notices

## sing-box / libbox

ART VPN uses the Android libbox library from singbox-android, built from sing-box. The library is distributed under GNU GPL v3.0. The APK build downloads the pinned 1.14.4 AAR from its GitHub release and verifies its SHA-256 before compilation.

- Project: https://github.com/singbox-android/libbox
- Core: https://github.com/SagerNet/sing-box
- License: GPL-3.0

ART VPN is an independent project and is not affiliated with the upstream maintainers or the subscription publisher.

## Configuration source

The default subscription is published by igareck:
https://github.com/igareck/vpn-configs-for-russia

ART VPN downloads subscription data at runtime. It does not bundle third-party server credentials in the APK. Public endpoints and credentials are untrusted and may stop working at any time.
