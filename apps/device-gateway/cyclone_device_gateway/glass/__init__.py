"""Cyclone Glass hosting: the local gateway serves the Glass web app and hands each browser tab a session.

Glass has no backend of its own and no intelligence. This package only serves static files and exchanges a
one-time launch code for the gateway bearer; every other call goes through the existing authenticated routes.
"""

from .api import GLASS_MOUNT, LaunchCodes, create_glass_router, resolve_glass_dist

__all__ = ["GLASS_MOUNT", "LaunchCodes", "create_glass_router", "resolve_glass_dist"]
