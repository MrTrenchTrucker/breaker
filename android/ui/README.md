# Ui — README

Screens: dictation, history, settings, auth, training.

The module's public interface is one function, `createSettingsView(context, settings)`, which builds the settings view for a caller-supplied context and settings store. Everything else is internal to the module.

Status: settings screen. The theme choice, routing mode, the three switches and the read-only lines are implemented and covered by the module's unit tests. The view the renderer draws is not covered: how the nodes are measured, laid out and painted, and how taps are routed inside the host view, are not unit-tested. The theme control is a text action rather than a rocker toggle, which meets the function and not the look the design notes ask for.

Full module card: `AGENTS.md` in this folder.
