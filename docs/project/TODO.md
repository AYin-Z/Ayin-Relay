# Ayin-Relay open work

- Deliver the single signed Windows installer described in the [architecture](../spec.md). Verify clean installation, normal account and alternate-administrator UAC, cancellation, upgrade and uninstall on Windows.
- Verify the startup contract after a reboot. The deployed connector starts at user sign-in. Determine which functions need to be available before sign-in and test that separately from interactive control.
- Deliver managed Windows updates using signed release artifacts, explicit device targeting, reconnect confirmation and rollback. Test an offline node, an invalid package and a failed restart.
- Exercise the device capability matrix and local take-over on a second Windows node. Include detached job output capture and hardware-specific checks.
- Distinguish an inconclusive Windows PID probe from a confirmed exit in daemon status, and verify heartbeat recovery under an actual network interruption.
- Add transfer progress and cancellation. Resume transfers only with fresh destination authorization and persisted ownership.
- Verify supported-device readiness for microphone and camera and reconcile legacy screenshot and task-grant policy.
- Audit the remaining dashboard, Hermes and phone compatibility consumers before retiring their server interfaces.
