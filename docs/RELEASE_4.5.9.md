# Cyclone Mobile 4.5.9

Android versionCode 118; builds on published 4.5.8.

Tasks now pick one of three execution tiers:

- Easy: named app or website open. Local landing, no planner.
- Medium: one app or site with in-scene work. Current page agent after landing. Login walls stop for you.
- Hard: multi-app / long-horizon. One waypoint plan, then local execute. Success still becomes a playbook; failure writes a trajectory lesson.

Release requires Mobile CI tests, lint, assembly and existing signing/update-continuity gates.
