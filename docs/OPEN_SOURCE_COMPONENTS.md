# Open Source Components

Cyclone may interoperate with or incorporate open-source projects. Before code is copied or distributed, verify the current upstream license and document the exact usage boundary here.

## Mobilerun Portal

- Project: `droidrun/mobilerun-portal`
- Upstream reference inspected: `1b6431dfb90cb797d3cd4147dc4cceefb7dfc047`
- License at that reference: **GNU AGPL-3.0-or-later**
- Copyright notice in upstream license: `Mobilerun Portal Copyright (C) 2025 Niels Schmidt`
- Cyclone usage in `feature/mobile-mobilerun-backend`: **external compatibility backend only**
- Source copied into Cyclone: **No**
- Integration method: documented authenticated Mobilerun Portal HTTP API
- Cyclone adapter: `apps/cyclone-core/app/mobile_portal.py`
- Gateway: `apps/cyclone-core/app/mobile_gateway.py`

### Licensing boundary

Mobilerun Portal remains independently installed/run on the Android device. Cyclone's compatibility adapter was written against Portal's documented network API and exposes Cyclone's own stable `phone.*` protocol to higher layers.

Do not copy Portal source into Cyclone or distribute a modified Portal binary as part of Cyclone without first making an explicit AGPL compliance/distribution decision and preserving all required notices/source availability obligations.

See `docs/MOBILERUN_PORTAL_BACKEND.md` for architecture and configuration.
