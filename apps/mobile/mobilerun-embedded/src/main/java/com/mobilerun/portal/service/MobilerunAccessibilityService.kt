package com.mobilerun.portal.service

/**
 * Source-compatibility name for pre-3.9.1 UI code only.
 *
 * This is intentionally not an Android Service and is never declared in a manifest. Cyclone's
 * native com.cyclone.mobile.CycloneAccessibilityService is the sole phone-control endpoint.
 */
@Deprecated(
    message = "Cyclone native accessibility is authoritative; this type is only a legacy name token.",
    level = DeprecationLevel.WARNING,
)
class MobilerunAccessibilityService private constructor()
