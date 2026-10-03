# Notification-style task card swipes

Built on the published Mobile 4.4.7 source, without changing execution or release identity.

- Ask task panels, task progress cards, and Brain outcome cards share the same horizontal interaction.
- Swipe right to reveal Open; swipe left to reveal Clear for terminal results only.
- Release beyond 72% of card width (at least 1.5 action widths) to perform the action directly.
- Short drags spring closed or settle at a 100dp action reveal. Action buttons use CycloneLiquidPanel, rounded 24dp corners, and an 8dp card gap.
- Starting another card's swipe closes the previous reveal. Cancelled gestures return to rest. Vertical scrolling retains its own touch-slop arbitration.
- TalkBack exposes Open and, for terminal cards, Clear result card without requiring a swipe. Existing in-card controls remain available.
- Clear persists only a presentation ID in private preferences. It never cancels execution, closes a workspace, removes a queued request, or deletes a diagnostic trace.
- Brain → Outcomes → Restore cleared cards restores all hidden cards. Active, paused, and human-handoff requests cannot be cleared. Queued request controls are unchanged.

Validation: seven JVM policy tests cover reveal/full-swipe thresholds, active-task protection, narrow cards pre-layout input and task/queue visibility for every phase; 83 repository guards pass. Android tests/lint/build run in Mobile CI. Physical-device gesture and glass-rendering acceptance is still required: both directions, short/full/cancelled drags, scrolling, TalkBack, restart persistence, restore, and active task protection. No new APK release is claimed by this change.
