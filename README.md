# jstall-intellij-plugin

![Build](https://github.com/parttimenerd/jstall-intellij-plugin/workflows/Build/badge.svg)

<!-- Plugin description -->
A tiny plugin to integrate the [jstall](https://github.com/parttimenerd/jstall) CLI tool into JetBrains IDEs,
giving you rich JVM diagnostics (thread analysis, deadlock detection, …) directly in your IDE,
instead of just basic thread dumps.

It's the fastest way to answer the age old question "Why is my JVM process stuck?" without leaving your IDE.
Before you had to run a profiler or capture thread dumps and analyze them manually,
now you can get insights with a single click.

## Features

### Run Toolbar Actions
Available in the **Run** tool window toolbar (next to Stop, Rerun, …):

- **JStall Status** — Analyze a running JVM with `jstall status` and view the output in a console tab.
  Automatically uses the PID of the process in the current run window.
- **JStall Record** — Record JVM diagnostics over time into a ZIP file (`<project-dir>/<pid>-<timestamp>.zip`)
  for later analysis.

Both actions fall back to a **JVM picker popup** when invoked outside a run window
(e.g. via <kbd>Shift</kbd><kbd>Shift</kbd> → "JStall Status" / "JStall Record").

### Recording File Support
JStall recording `.zip` files get a **custom file icon** in the project view and support:

- **Double-click** to automatically run `jstall status` on the recording and display the results in an editor tab.
- **Right-click → Analyze JStall Recording** — Same analysis via the context menu.
- **Right-click → Extract JStall Recording** — Extract the recording ZIP contents into a folder.

### Settings
Configurable under **Settings → Tools → JStall**:

| Setting | Description | Default |
|---|---|---|
| Full diagnostics (`--full`) | Include expensive analyses | Off |
| Sample interval | Seconds between thread dump samples | 5 |
| Sample count | Number of samples to collect | 2 |

### Progress Indicators
All long-running operations show a **time-based progress bar** in the IDE status bar,
estimated from the configured interval × sample count.

<!-- Plugin description end -->

## Usage

Use <kbd>Shift</kbd><kbd>Shift</kbd> (Search Everywhere) and type **"JStall Status"**, **"JStall Record"**,
or **"Analyze JStall Recording"** to quickly invoke actions from anywhere in the IDE.

## Installation

- Using the IDE built-in plugin system:

  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "jstall-intellij-plugin"</kbd> >
  <kbd>Install</kbd>

- Manually:

  Download the [latest release](https://github.com/parttimenerd/jstall-intellij-plugin/releases/latest) and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

## Support, Feedback, Contributing

This project is open to feature requests/suggestions, bug reports etc.
via [GitHub](https://github.com/parttimenerd/jstall-intellij-plugin/issues) issues.
Contribution and feedback are encouraged and always welcome.

## License

MIT, Copyright 2026 SAP SE or an SAP affiliate company, Johannes Bechberger and contributors