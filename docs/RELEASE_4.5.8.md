# Cyclone Mobile 4.5.8

Android versionCode 117; builds on published 4.5.7.

"Open Facebook and login" no longer spends a provider turn then dies on the first unverified `phone.open_app`. Named apps land locally like websites. Missing Facebook/WhatsApp packages try Lite/Business, then the website. App-not-installed is a recoverable miss, not a hard capability blocker.

Release requires Mobile CI tests, lint, assembly and existing signing/update-continuity gates.
