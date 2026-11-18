"""Ayin-Relay WebSocket service and retained compatibility routes."""

# Define __version__ *before* importing .server — server.py reads it back
# during its own import via ``from . import __version__``, so this ordering
# avoids a circular-import crash during package initialization.
#
# Canonical plugin version source is pyproject.toml's [project].version.
# Keep this runtime constant in sync with pyproject.toml for server-v*
# releases. Desktop releases use desktop/package.json and desktop-v* tags. The /health endpoint
# reports this plugin version, and stale values make live diagnosis harder than
# it should be.
__version__ = "1.13.1"

from .server import create_app, main  # noqa: E402 — must come after __version__

__all__ = ["create_app", "main", "__version__"]
