# Events hooks

Place event-observation hooks in this package.

- Observe events from the other modules through Core's neutral event interface.
- Do not make other modules depend on Events.
- Keep this package limited to event collection and forwarding; do not implement Media, Protection, Voltage, or Autorun behavior here.
- Implement `com.mt8163.tools.xposed.PackageHook` for each independently registered hook.

No target package or hook is assumed until the device-specific behavior is defined.
