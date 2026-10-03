# Cyclone 4.3 profile continuity checkpoints

Base v4.2.9: ae52303b834d88e9e40983a7415c1b3bc1558896.
Branch agent/430-profile-rescue.

- 2b9c79d58ff3a4bba13d308d27024951ab0d30e8: setup-independent Return to Profile A control and bounded switch verification.
- 605d7fa5b441cf3a14f57f6184a1f2bd0a28b2e3: exact-user preparation, encrypted credentials, inherited permission/root grants.
- 329ba15ab5e18e21d912aface7916618e904f25e: dedicated rescue screen before app initialization, owner-mediated repair, foreground bootstrap service, crypto tests.
- 40e7ab3e2cc85d1ac745ed158c3cc9be94401957: 4.3.0 / 91 candidate, supported-role grants, permission inventory guard and release notes.
- Final integration: readiness marker only after verified import/grants, active-user recheck before switch, durable encrypted preference updates.

Every checkpoint was pushed immediately. No unrelated branch merged.

Local validation: 72 CI-script tests and product/version/security guards pass.
Local Android Gradle download is network-blocked; GitHub Mobile CI performs JVM tests,
lint and unsigned assembly. Publication must consume exact successful source artifacts.

Physical phone / Pixel / USB / ADB testing: NOT PERFORMED; UNVERIFIED.
See RELEASE_4.3.0.md for supported transfers, platform limits and device checklist.
