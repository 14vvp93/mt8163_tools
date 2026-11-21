# Events Hook Integration

Events is a regular application module, not an Xposed hook. It receives events through the Core `com.mt8163.tools.core.EventLogger` interface.

Xposed hooks in individual modules submit their own operational events through this interface. Events owns storage, filtering, display, copy, and export. Other modules must not depend on the Events module implementation.

Module documentation: [docs/modules/events/README.md](../../../modules/events/README.md)
