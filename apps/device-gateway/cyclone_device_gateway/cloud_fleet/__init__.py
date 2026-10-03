"""Cloud phones (plan 44, run 1 / alpha 90): VMOS Cloud, DuoPlus and any remote-ADB phone, joined to this PC's fleet
and kept connected.

The provider is only the way in. Its API opens a remote-ADB link (VMOS: an SSH tunnel with a key that expires; DuoPlus:
an address open to this PC's IP), and Cyclone keeps that link alive on its own: it renews the key before it expires,
restarts a tunnel that dies, and reconnects adb. From there a cloud phone is an ordinary fleet phone: phone care
installs Cyclone, trust asks "Connect this PC?", and PhoneToolExecutor on the phone stays the only thing that acts.

Never: a provider key in a response, a log or diagnostics; a free-form adb or shell command; provider-native taps.
"""
from .service import CloudFleetService

__all__ = ["CloudFleetService"]
