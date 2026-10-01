# hprof-analyzer

Command-line tool that analyzes JVM heap dumps (`.hprof`) with [Shark](https://square.github.io/leakcanary/shark/)
and produces a self-contained **HTML** report (interactive charts, works offline) and a **Markdown** report.

## Features

- Summary of the dump: hprof version, object/class/array counts, total and reachable bytes
- Class histogram and memory by package (instances, shallow size, retained size)
- Dominator tree: biggest objects by retained size, plus an interactive treemap
- Shortest path from a GC root to the biggest objects (graph view), with Shark's leak-status heuristics
- GC roots by type, threads with stack traces and the local variables of each frame
- Duplicate strings, largest arrays, class loaders
- Generated classes / proxies (ByteBuddy, Hibernate, CGLIB, Mockito, Javassist, JDK Proxy) per base class, flagging uncached proxies that fill the metaspace
- JVM environment (version, OS, command line, system properties) and detected frameworks
- Extra `<dump>-app` report with only your application's classes, auto-detected (`--app-package` to override)
- Localized reports and messages (`--i18n`): Portuguese (pt-BR, default) and English (en)

## Requirements

JDK 21 or newer. The Gradle wrapper is included.

## Quick start

```bash
git clone https://github.com/vodikus/hprof-analyzer.git
cd hprof-analyzer
./gradlew shadowJar

jcmd <pid> GC.heap_dump /tmp/app.hprof
java -Xmx4g -jar build/libs/hprof-analyzer-all.jar /tmp/app.hprof --out report --i18n en
```

Open `report/app.html` in a browser, or read `report/app.md`.

## Options

| Option | Description |
| --- | --- |
| `--out <dir>` | Output directory (default `.`) |
| `--format html,md` | Formats to generate (default both) |
| `--top <n>` | Rows per table (default 50) |
| `--no-retained` | Skip the dominator tree: faster and uses less memory, but no retained sizes |
| `--leak-class a.B,c.D` | Also find GC root paths for instances of these classes |
| `--app-package a.b,c.d` | Application packages for the `-app` report (default: auto-detect) |
| `--i18n <code>` | Report and message language (default `pt-BR`; available: `pt-BR`, `en`) |
| `--version`, `-V` | Print the version |

Give the JVM about 1.5–2× the dump size (`-Xmx`).

## Documentation

- [User guide (English)](docs/en/guide.md)
- [Guia do usuário (Português)](docs/pt-BR/guia.md)

## License

[MIT](LICENSE)
