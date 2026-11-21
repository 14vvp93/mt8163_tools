# Hook implementations

Place package-specific hook classes in this directory.

Each hook must implement `PackageHook`, check the exact target package in `supports()`, and install hooks using the target application's `classLoader`.

Keep hooks isolated: a failed hook must not prevent other hooks from being installed.
