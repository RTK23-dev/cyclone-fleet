# Cyclone Mobile 4.5.7

Android versionCode 116; builds on published 4.5.6.

Ports PR #121 Instagram stock automations onto the current cookie-burst release: Reels Warmup, Reels Following, Cold DMs, and Prepare Post. They still run through AutomationRunner → StockSkillGateway → PhoneToolExecutor. The four skills now seed as Routines rows under Instagram, so they are visible and runnable from Routines instead of only the automation runtime.

Release requires Mobile CI tests, lint, assembly and existing signing/update-continuity gates.
