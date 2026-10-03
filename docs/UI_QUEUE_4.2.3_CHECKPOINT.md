# Cyclone Mobile 4.2.3 candidate

Base: published v4.2.2, 5042e4cf36d16e2c6de37a23669380fbceb09ef0.
Mobile identity: 4.2.3 / versionCode 84. Source checkpoint branch: codex/cyclone-4.2.2-queue-profile-polish.
Publication is not authorized by this source cut; v4.2.2 is unchanged.

## Fixes

- Opening the Ask composer enters ANALYSIS in the existing overlay machine. That presentation state no longer counts as an executing task. Admission uses actual foreground job/suspension/confirmation ownership and canonical WorkspaceTasks state. Retained DONE pages, review and paused tasks remain protected.
- Steer retries promotion after selecting a profile. An ambiguous app opens the existing explicit target picker with the saved goal/request identity. Failed admission shows the setup reason on the queue entry. Attachments stay attached to their original request.
- AI chat starts uniquely resolved app requests through WorkspaceTasks.start; ambiguous requests still require explicit app choice.
- Current Profile A appears even with no app-workspace registrations. Visible secondary Android profiles are enumerated independently of the workspace registry and are no longer truncated to one.
- Before creating a profile, setup can recover the exact saved Android identity. A saved user handle can be recognized even before launcher apps are installed. An unrelated existing managed profile is not claimed or replaced, and no longer invites an endless create retry.
- Steer is a compact horizontal profile selector, toggled by Steer; its large heading, duplicate goal, and Cancel button are removed.
- Overlay and in-app controls share one model/intelligence panel. The model name is a rounded tinted pill above the slider; tapping it reveals real model presets. Phone autonomy remains a separate expandable control backed by the existing store. Active sliders are blue.
- Removed New phone task label, reduced duplicate chat hero spacing, and removed the Home glass elevation that was clipped by its parent at the sides. Existing real alpine backgrounds remain.

## Known limitations

Secondary-profile Ask execution is not implemented by the v4.2.2 isolated runner. These requests now show an explicit blocked reason instead of a silent no-op. The existing Layer 2 workspace infrastructure remains available; this patch does not invent another runner or convert its identity into a named VD. This part of the requested end-to-end Steer behavior is incomplete.

Android's existing managed-profile limits remain. Cyclone only resumes a profile matching its saved name, user ID (when journaled), and parent identity. An unrelated work profile requires review in Android settings, not automatic adoption/deletion. Physical confirmation of the reported Profile B recovery is still required.

## Validation

Local product/version guards and the 70 Python CI-helper tests pass. Added JVM admission and profile-inventory regression tests; queue tests cover FIFO and attachment retention. Local Gradle cannot download its distribution because network access to services.gradle.org is unavailable. The existing Mobile CI workflow is enabled for this branch to compile, run JVM tests, lint and assemble a candidate APK.

No signed release or physical visual acceptance is implied by source checks.

## Device checklist — UNVERIFIED

- Cold launch and activate Ask; submit Open Chrome. It must start or identify the exact failed setup requirement, never queue behind an imaginary task.
- Repeat from Home and the AI tab. Queue a follow-up during real work; stop the current task and verify FIFO promotion.
- Steer to Profile A and verify immediate retry; try an ambiguous app request and verify goal/attachment retention through app choice.
- Confirm secondary-profile unsupported requests explain the block without launching the wrong account.
- Open Profiles before registering apps: Profile A is present. Resume a partially created, journal-matching Profile B without issuing a duplicate creation.
- Compare Home shadows, chat spacing, the horizontal Steer selector and the shared model/slider control at normal and enlarged font sizes, with IME open and closed.
- Verify model/intelligence persistence, voice, attachments, GATE, Take Control and exact-plane View Progress.

NO DIRECT PHONE / PIXEL / USB / ADB TESTING WAS PERFORMED.
