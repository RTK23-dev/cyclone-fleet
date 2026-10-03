# Cyclone Mobile 4.5.4

Android versionCode 113; builds on published 4.5.3.

`open chrome and go to Shopify` is a website landing, not generic word matching. Cyclone launches `https://shopify.com` locally instead of spending two provider turns opening Chrome first. After the intent, a changing/loading page is retried up to five same-scope captures instead of being treated as a hard blocker at attempt two. Reaching the loaded host completes the simple navigation goal.

Release requires Mobile CI tests, lint, assembly and existing signing/update-continuity gates.
