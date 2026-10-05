# hprof-analyzer — User guide

[Português](../pt-BR/guia.md) · [Back to README](../../README.md)

- [1. Overview](#1-overview)
- [2. Build and install](#2-build-and-install)
- [3. Capturing a heap dump](#3-capturing-a-heap-dump)
- [4. Running the analyzer](#4-running-the-analyzer)
- [5. Reading the report](#5-reading-the-report)
- [6. Key concepts](#6-key-concepts)
- [7. Performance and memory](#7-performance-and-memory)
- [8. Languages (i18n)](#8-languages-i18n)
- [9. Architecture](#9-architecture)
- [10. Limitations](#10-limitations)
- [11. Troubleshooting](#11-troubleshooting)

## 1. Overview

`hprof-analyzer` reads a JVM heap dump (`.hprof`) and writes two reports:

- **HTML**: a single self-contained file. The charting library (Apache ECharts) and all the data are embedded, so it
  opens offline and can be attached to a ticket or sent by e-mail. It follows the system light/dark theme.
- **Markdown**: the same content as tables, with GC root paths drawn as Mermaid diagrams (rendered by GitHub, GitLab
  and most Markdown viewers).

Heap parsing is done by [Shark](https://square.github.io/leakcanary/shark/), the heap analysis library behind
LeakCanary. The tool targets HotSpot/OpenJDK dumps.

## 2. Build and install

Requirements: JDK 21 or newer. Gradle is not needed; the wrapper downloads it.

```bash
./gradlew shadowJar   # build/libs/hprof-analyzer-all.jar (single runnable jar with all dependencies)
./gradlew test        # unit and end-to-end tests
```

The end-to-end test dumps the heap of its own JVM, analyzes it and checks the reports. It also writes sample reports
to `build/test-report.md`, `build/test-report.html` and `build/test-report-en.html`.

Copy `hprof-analyzer-all.jar` anywhere; it has no other runtime dependency.

## 3. Capturing a heap dump

| Method | Command |
| --- | --- |
| Running process | `jcmd <pid> GC.heap_dump /path/app.hprof` |
| Running process (jmap) | `jmap -dump:live,format=b,file=/path/app.hprof <pid>` |
| On `OutOfMemoryError` | start the JVM with `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/path/` |
| From code | `ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean::class.java).dumpHeap(path, true)` |

`jcmd GC.heap_dump` and `jmap -dump:live` run a full GC first, so the dump contains only live objects. Use
`jcmd <pid> GC.heap_dump -all` to keep unreachable objects too.

Heap dumps contain everything the application had in memory (passwords, tokens, personal data). Handle them and the
generated reports as sensitive files.

## 4. Running the analyzer

```bash
java -Xmx4g -jar hprof-analyzer-all.jar <dump.hprof> [options]
```

| Option | Default | Description |
| --- | --- | --- |
| `--out <dir>` | `.` | Output directory. Created if missing. Files are named after the dump (`app.hprof` → `app.html`, `app.md`). |
| `--format html,md` | `html,md` | Comma-separated list of formats. `json` writes `<dump>.snapshot.json` with the full class histogram (the input for comparing dumps). |
| `--top <n>` | `50` | Rows in each table (classes, objects, strings, arrays). |
| `--threshold k=v,...` | — | Health dashboard thresholds (fractions: `0.3` = 30%). Keys: `bigDominator` (0.30), `lowFill` (0.25), `minFillInstances` (1000), `finalizerQueue` (10000), `poolQueue` (10000), `objectMappers` (10), `heapGrowth` (0.20), `webappLoaders` (2), `dupStrings` (0.10), `emptyCollections` (0.05), `poolsPerClass` (10), `offHeapRatio` (1.0), `duplicateClasses` (1), `softRefs` (0.10), `openFiles` (500), `sockets` (1000), `bigStaticCollection` (100000), `unreachable` (0.10), `libraryVersions` (1). An unknown key exits with an error. |
| `--no-redact` | off | By default the report masks `user.name`, `user.home`, `user.dir`, class/module/library path, the arguments of `sun.java.command` (only its 1st token stays) and properties or `-Dkey=value` whose key contains `pass`, `secret`, `token`, `credential`, `auth` or `key`. On top of that, the user home directory (in every spelling) becomes `~` and the user name becomes `‹user›` in any text of the report and the snapshot (paths in `java.home`, thread names, object fields). This option shows everything. |
| `--no-retained` | off | Skip the dominator tree. About 2× faster and uses less memory, but retained sizes, the treemap and the "biggest objects" section are empty, and GC root paths are only computed for `--leak-class`. |
| `--leak-class a.B,c.D` | — | Fully qualified class names. Up to 20 instances of these classes get a GC root path, in addition to the 10 biggest objects. |
| `--app-package a.b,c.d` | auto | Application packages for the extra `<dump>-app.html`/`.md` report (application classes only). Without it, the main class package (`sun.java.command`) is used or, for a jar/launcher, everything outside the JDK, language and known frameworks/libraries. |
| `--baseline a.json,b.json` | — | Snapshots of earlier dumps (made with `--format json`). The report gets the dump comparison section. |
| `--i18n <code>` | `pt-BR` | Language of the reports and console messages. See [section 8](#8-languages-i18n). |
| `-h`, `--help` | — | Print the version and usage (in the language given by `--i18n`). |
| `-V`, `--version` | — | Print the version (`hprof-analyzer 1.0.0`) and exit. |

The first line on stderr is the tool name and version, followed by the progress; the paths of the generated files are
printed to stdout. The version also appears in the report footer ("Generated by hprof-analyzer 1.0.0") and in the
HTML side menu. Invalid arguments exit with
code 2.

Examples:

```bash
# English report, HTML only
java -Xmx4g -jar hprof-analyzer-all.jar app.hprof --out report --format html --i18n en

# Quick look at a very large dump
java -Xmx8g -jar hprof-analyzer-all.jar huge.hprof --no-retained --top 30

# Check why instances of a suspect class stay in memory
java -Xmx4g -jar hprof-analyzer-all.jar app.hprof --leak-class com.acme.SessionContext,com.acme.CacheEntry
```

## 5. Reading the report

Sections come in six layers, from conclusion to evidence (same order in HTML and Markdown):

| Layer | Sections |
| --- | --- |
| Diagnosis | Executive summary, Health dashboard, Warnings, Summary (with dump context and sizing), Where to start, Dump comparison |
| Where the memory is | Memory budget, Dominator tree, Aggregated retained, Biggest objects, Histogram, Packages, Off-heap |
| Why it is retained | Leak suspects, Merged paths, Paths to GC roots, References |
| Waste | Memory waste (with boxing), Duplicate strings, Largest arrays |
| Runtime | Threads (with stack traces), Concurrency, Frameworks |
| Technical appendix | JVM environment, Generated classes, ClassLoaders, GC roots, Libraries, Graph, Metadata |

Automatic analyses (full report only):
- **Executive summary**: 3 to 7 rule-generated sentences: reachable heap, off-heap and garbage in the dump; the largest
  budget components; whether the dump came from an OOM; leak signals; where to start; dashboard findings and the
  suggested `-Xmx` range.
- **Memory budget**: exclusive attribution over the dominator tree. Each object belongs to the component (application,
  each framework/library, JDK, generated at runtime) of its nearest non-JDK dominator, else to the component of its
  class. The parts add up exactly to the reachable heap; top 7 + "Other", with off-heap on the same scale.
- **Where to start**: a 0–100 score for the 30 biggest application/library classes (JDK classes and arrays are left
  out: they are the content, not the cause). Factors: heap share retained (full from 30%, weight 35%), growth between
  dumps with `--baseline` (25%), held by a static field (15%) or by a collection (15%) and average path depth (10%).
  Benign findings count 30%.
- **Dump context**: after-OOM when an `OutOfMemoryError` is a thread local variable or a non-pre-allocated OOM is
  retained; `-XX:+HeapDumpOnOutOfMemoryError` is shown only as a hint. "Much garbage" when the `unreachable` rule fires.
- **Sizing** (heuristic): `-Xmx` between 3× and 4× the live set and `MaxDirectMemorySize` ≥ 1.5× the direct buffers,
  compared with the current `-Xmx` when the arguments are in the heap.
- **Waste by owner field**: collections with idle capacity and duplicate strings grouped by the field that holds them
  (`Foo.cache`), climbing through JDK collection internals. For strings, each copy contributes its share of the waste
  (`bytes × (copies − 1) / copies`), so the owners add up to the total waste. JDK-internal owners (e.g.
  `AppClassLoader.parallelLockMap`) are hidden by default: the HTML has a checkbox to show them; the Markdown says how
  many were left out. Classes defined by more than one ClassLoader are info with plugin isolation and a warning when
  some library shows up in different versions.
- **Libraries**: JARs of the open `ZipFile`s and the class path (file name only), version read from the name; the same
  library in different versions raises a warning.
- **New dashboard rules**: classes defined by more than one ClassLoader, bytes kept only by `SoftReference`, a big
  collection in a static field, many open files/sockets, dump without `:live`, libraries in more than one version and
  an after-OOM dump.

In the HTML:
- **Side menu** grouped by layer, with a **global search**: the term filters every table and dims sections without
  matches.
- **Tables** sort by header click, have their own filter and a **CSV** button (exports the visible rows).
- **Clickable object ids** (`0x…`) open a panel with class, shallow, retained, immediate dominator, fields and the path
  to the GC root. Ids inside the panel are clickable too.
- **Persistent anchors**: `#leaks/2`, `#paths/3`, `#merged/1`, `#retained/2` (selectors) and `#obj/0x…` (panel) reopen
  the same spot; the address follows the selector, ready to paste in a ticket.
- **Color by origin** in the class, package, static field, dominator and pool charts: application, framework/library,
  JDK and generated at runtime, always with the same colors and a legend.
- **Benign findings** (class path `ZipFile$Source`, `Locale`/`MethodType` caches, JVM internal threads) are dimmed, at
  the end of the table, with the reason as tooltip. In Markdown they get a "(benign: reason)" suffix.
- **On-demand rendering**: each chart is drawn when it gets near the viewport; stack traces are built when opened.

### Summary
hprof version, identifier size (4 or 8 bytes), dump timestamp (UTC), counts of objects, classes, instances, object
arrays and primitive arrays, number of GC roots, total shallow size, and how many objects and bytes are reachable
through strong references.

### Generated classes / proxies (metaspace)

Classes created at runtime (ByteBuddy, Hibernate, CGLIB/Spring, Mockito, Javassist, JDK Proxy), grouped by generator and base class, with the number of ClassLoaders that defined them. A group is flagged as suspicious with 10+ classes for the same base, 10+ ClassLoaders, or 200+ JDK proxies: a sign of a proxy regenerated on every use instead of cached, a common cause of `OutOfMemoryError: Metaspace`. Typical fix: generate the class once and reuse it (ByteBuddy `TypeCache`, `Enhancer`/`ProxyFactory` with caching) and do not create a ClassLoader per call. The hprof has no metaspace size, only the classes.

### Class histogram
One row per class (arrays included): number of instances, total shallow size and retained size. The chart shows the
top 20 classes by retained size, shallow size or instance count (selector above the chart).

The retained size of a class adds up the retained size of its instances that are not dominated by another instance of
the same class. This prevents a linked list from being counted once per node.

### Memory by package
Shallow and retained size summed by Java package. Primitive arrays (`byte[]`, `int[]`...) have their own group, and
classes without a package go to `(default)`.

### Dominator tree
The treemap shows the top of the dominator tree: each rectangle is an object, its area is its retained size, and the
rectangles nested inside it are the objects it dominates. Click to zoom in, use the breadcrumb below to go back. Only
the biggest children are drawn (100 at the first level, 15 at the second, 8 at the third).

### Biggest objects (retained)
Objects with the largest retained size, with their id, class, shallow size and, for strings and threads, their value
or name.

### Paths to GC roots
For each suspect (the 10 biggest non-class objects, plus the `--leak-class` instances), the shortest chain of strong
references from a GC root to it. Choose the object in the selector; the graph goes from the GC root at the top to the
object at the bottom, and each node shows the field that points to it.

Node colors come from Shark's `ObjectInspectors` for the JDK:

| Status | Meaning |
| --- | --- |
| `NOT_LEAKING` (green) | Shark knows this object is expected to be alive (for example a live thread or a class loader). |
| `LEAKING` (red) | An object Shark knows should have been collected. |
| `TARGET` (blue) | The chosen object (biggest retained or `--leak-class`): the end of the path, not a proven leak. |
| `UNKNOWN` (gray) | No rule applies. |

To find a leak, look for the first reference in the path that should not exist, usually a static field, a cache or a
listener that is never removed.

### GC roots by type
Count of GC roots per type: `StickyClass` (classes loaded by the bootstrap loader), `JavaFrame` (local variables),
`ThreadObject`, `JniGlobal`, `MonitorUsed`, etc.

### Threads
Each thread with its name, daemon flag, priority, retained size and stack trace. Under each frame, `local:` lists the
objects held by that frame's local variables.

### Duplicate strings
`String` values that appear more than once, sorted by wasted memory (`(copies - 1) × bytes of one copy`). Strings
longer than 1024 characters are skipped, and values are cut at 200 characters.

### Largest arrays
Largest object and primitive arrays with their length, size and retained size.

### ClassLoaders
Every class loader instance with the number of classes it defined and its retained size. `<bootstrap>` stands for the
JVM's built-in loader.

### Dump comparison
Only with `--baseline`. Make a snapshot of each dump with `--format json` and pass the earlier ones when analyzing
the latest:

```bash
java -jar hprof-analyzer-all.jar day1.hprof --format json
java -jar hprof-analyzer-all.jar day2.hprof --format json
java -jar hprof-analyzer-all.jar day3.hprof --baseline day1.snapshot.json,day2.snapshot.json
```

Snapshots are ordered by dump timestamp. The section shows the change (Δ = current − first) in bytes per class and
per package and in elements of the biggest collections, as a diverging bar (grew vs shrank) and as the trend of a
chosen item. "Growing" flags what is present in every dump and larger in each one. Object ids change between dumps,
so collections are matched by the signature of their path from the GC root (e.g.
`[StickyClass] class X → X.cache (java.util.HashMap)`). The health panel warns when collections grow in every dump
(3 or more) and when the heap grows over 20%.

### Health panel
First section of the full report. Automatic rules with a severity (critical, warning, info) and a link to the section:
one object retaining over 30% of the reachable heap, a Finalizer queue over 10,000, large collections with an average
fill ratio under 25%, 2 or more webapp ClassLoaders (Tomcat/Jetty), duplicate strings (all of them, not only the top N) over 10% of the reachable heap, suspicious
proxy groups, empty collections with an allocated array over 5% of the heap, off-heap (`DirectByteBuffer`) larger than
the reachable heap, 10 or more pools of the same class, and pools with `max = Integer.MAX_VALUE` and an unbounded queue
(except `ScheduledThreadPoolExecutor`, where that is by design; the "unbounded queue" column of the pool table only
describes the queue type).
Thresholds live in `Health.kt` and can be changed with `--threshold`. Each finding shows the threshold it crossed, and a collapsed list in the dashboard has the thresholds of every rule, including the ones that did not fire.

### Leak suspects
A single Shark analysis (`FilteringLeakingObjectFinder`) with JVM rules: terminated thread still referenced, stopped
Tomcat webapp ClassLoader (`STOPPED`/`DESTROYED`), invalidated HTTP session, closed `FileInputStream`/
`FileOutputStream`/`RandomAccessFile` still retained, and the `--leak-class` classes. Paths are grouped by Shark's
signature (N occurrences of the same pattern). Paths through well-known JDK references (`Thread.contextClassLoader`,
`ThreadLocal` values, `DriverManager.registeredDrivers`, shutdown hooks) are "library" leaks. Each group's retained
size is Shark's estimate over its own shortest-path tree and may differ from the dominator tree. JVM internal threads
(`C1/C2 CompilerThread*`, `Service Thread`, ...) do not count as terminated threads: the JVM recreates them and the
object stays held by a native reference. Paths without a suspect reference (Shark would give them all the same
signature, the SHA-1 of `""`) are grouped by object class + GC root type.

### Merged paths by class
Sankey of the shortest paths (BFS from the GC roots) to the instances of the 5 biggest classes and the `--leak-class`
classes, merged by class like MAT's "merge shortest paths". Up to 10,000 instances and 8 hops per class.

### Aggregated retained
ClassLoader → package → class sunburst; static fields whose value is dominated by the class itself (static caches and
singletons); and, for the 10 biggest classes, the class of each instance's immediate dominator ("who holds X").

### Concurrency
Thread state from the `threadStatus` field (the hprof has no monitors), `ThreadPoolExecutor` (core, max, threads,
queue type and size), `ForkJoinPool`, `Timer`, virtual threads and their continuations' retained size, classes of
`ThreadLocal` values, identical stacks grouped, and a sunburst of frames from the top of the stack. The thread table
gained state, locals retained, `ThreadLocal` retained and count, and stale entries (key collected).

### Frameworks and technologies
Inspectors that only show up when their classes are in the dump, reading each library's internal fields:
- **HTTP sessions** (Tomcat `StandardSession`, Jetty): count, expired/invalid ones still retained, biggest sessions and
  retained size per attribute name.
- **Hibernate**: persistence contexts (`StatefulPersistenceContext`) and the managed entities in each.
- **Spring**: singletons of each `DefaultListableBeanFactory` and the heaviest beans.
- **JDBC**: HikariCP and DBCP2 pools (connections, idle, active) and the number of driver `Statement`/`ResultSet` objects.
- **Caches**: Caffeine, Guava, Ehcache and static maps with 100 or more entries.
- **Jackson**: number of `ObjectMapper` instances (many point to one per request).
- **Retained exceptions**: `Throwable` grouped by type and message, with retained size (includes the `backtrace`).
  Pre-allocated ones (shortest path goes through a static field or starts at a non-thread GC root, like the
  `OutOfMemoryError`s the JVM creates at startup or H2 constants) go to a separate, collapsed table. An application
  exception kept in a static field lands there too.

Very different library versions may leave values blank; a failing inspector becomes a warning without stopping the others.

### Graph structure
Most referenced objects (fan-in) and objects with the most outgoing references (fan-out), a histogram of depth from
the GC roots (shortest path) and a circular graph of the 50 most frequent references between classes. Instance → class
edges, added only to keep classes alive, are left out.

### File metadata
Top-level hprof record counts by type (read in the same pass as the stack traces), file size / total shallow ratio,
GC collectors registered as MXBeans in the heap, and memory/GC flags when the JVM arguments are in the heap.

### Memory waste
- **Collections**: `ArrayList`, `Vector`, `HashMap`, `LinkedHashMap`, `WeakHashMap`, `Hashtable`, `ConcurrentHashMap`
  and `ArrayDeque` (exact classes only; `HashSet` shows up as its inner `HashMap`). Per type: empty ones with an
  allocated array, elements, capacity and bytes of free slots. Fill ratio and element count charts.
- **Arrays**: all-zero primitive arrays (>= 64 B), all-null or >= 90% null object arrays, and primitive arrays with
  identical content (>= 256 B, 64-bit hash; String backing arrays are skipped, they are in duplicate strings).
- **Boxing**: `Integer`, `Long`, `Boolean`... instances. "Redundant" counts copies of a value the JVM caches
  (-128..127, `true`/`false`): a sign of `new Integer(...)`.
- **Fields almost always null**: classes with >= 1000 instances and reference fields >= 90% null (heatmap).
- **Header and padding**: estimate for a 64-bit JVM with compressed class pointers (12 B header, 16 B for arrays,
  8 B alignment), since the hprof has no header.
- **Strings**: LATIN1 vs UTF16 (`coder` field; JDK 8 counts as UTF16), empty strings, length distribution and the most
  common prefixes.

### References and finalization
Soft, weak, phantom and final references with the referents still set and their bytes; `Reference` classes; the
`Finalizer` queue length and the classes registered for finalization (the hprof has no methods, so they come from the
referents of `java.lang.ref.Finalizer`); `Cleaner`s. The **Unreachable** summary tile includes objects reachable only
through weak/soft references.

### Off-heap memory (estimated)
A dump has no native memory; it is estimated from Java objects: `DirectByteBuffer` capacity (owners, views and mapped),
Netty `PoolChunk`, and counts of file descriptors, streams, sockets, `ZipFile`, `Inflater`/`Deflater` (with "open" when
the class has a `closed`/`fd` field). Views (`slice()`, `duplicate()`) share their owner's memory and must not be added
up: a `duplicate()` of a big buffer has the same capacity as the buffer.

## 6. Key concepts

**Shallow size**: memory used by the object itself (its fields, or its elements for an array).

**Retained size**: memory that would be freed if the object were collected. It is the object plus everything that is
only reachable through it.

**Dominator**: object A dominates object B when every path from a GC root to B goes through A. The retained size of A
is the sum of the shallow sizes of all objects A dominates.

**GC root**: a reference the garbage collector always treats as alive: local variables of running threads, static
fields (through their class), JNI references, active monitors, etc.

**Reference strength**: `WeakReference`, `SoftReference` and `PhantomReference` do not keep their target alive, so the
analyzer ignores them. Objects reachable only through them (for example caches built on `SoftReference`) count as
unreachable and are not part of any retained size.

**Size convention**: sizes are estimated as a 64-bit HotSpot JVM lays objects out: 12-byte header (arrays 16), 8-byte
alignment and 4-byte references when compressed oops are on (detected from `java.vm.compressedOopsMode`; 8 otherwise).
The hprof writes every reference with 8 bytes and no header, which overstated reference-heavy objects. The model in use
is shown in the summary. Snapshots from earlier versions used raw hprof bytes; the dump comparison warns when the
models differ.

**Retained is not additive**: a package's or class's retained size includes objects of other packages and classes it
dominates, so the values overlap. Columns marked "(overlaps)" must not be added up.

## 7. Performance and memory

- Give the JVM about 1.5–2× the dump size: `java -Xmx8g -jar ...` for a 4 GB dump.
- Reference: a 160 MB dump with 1.6 million objects takes about 40 s (about 20 s with `--no-retained`).
- The work is mostly single-threaded. The main costs are reading every object once and computing the dominator tree.
- Each GC root path is a separate search, so a long `--leak-class` list slows the analysis down.
- The HTML report is about 1.2 MB (ECharts) plus the data; `--top` controls how much data is embedded.

## 8. Languages (i18n)

The `--i18n <code>` option selects the language of the reports (Markdown and HTML) and of the console messages.

| Code | Language |
| --- | --- |
| `pt-BR` | Portuguese (Brazil), default |
| `en` | English |

How the code is resolved:

1. Case and separator are normalized: `pt_br`, `PT-BR` and `pt-BR` are the same.
2. The exact code is tried first, then just the language: `en-US` uses `en` when there is no `en-US` file.
3. An unknown code prints a warning and falls back to `pt-BR`.
4. A key missing from a language file falls back to the `pt-BR` text.

Numbers are formatted for the selected locale (`1,638,523` in English, `1.638.523` in Portuguese).

Class names, GC root types and Shark's status texts are data, not UI text, so they are never translated.

### Adding a language

1. Copy `src/main/resources/i18n/messages_en.properties` to `messages_<code>.properties`, where `<code>` is a
   [BCP 47](https://www.rfc-editor.org/info/bcp47) language tag (`es`, `fr`, `de`, `pt-PT`...).
2. Translate the values. Keep the keys and the `{0}`, `{1}` placeholders unchanged.
3. Save the file as **UTF-8**.
4. In `cli.usage`, continuation lines start with `\ ` (backslash, space) so that their indentation is kept.
5. Add the code to the `languages` list in `src/test/kotlin/I18nTest.kt` and run `./gradlew test`. The test fails if
   the new file is missing a key or has a key that does not exist in `pt-BR`.
6. Rebuild the jar and run with `--i18n <code>`.

No code change is needed: files are looked up by name at runtime.

Key groups: `cli.*` (usage and errors), `log.*` (progress), `report.*`, `section.*`, `note.*`, `summary.*`, `col.*`
(table headers), `chart.*`, `paths.*`, `misc.*`.

## 9. Architecture

| File | Role |
| --- | --- |
| `src/main/kotlin/Main.kt` | Parses arguments, loads the language, runs the analysis, writes the files. |
| `src/main/kotlin/Analyzer.kt` | Opens the dump with Shark and builds the `HeapReport` data model. |
| `src/main/kotlin/Dominators.kt` | Dominator tree algorithm. |
| `src/main/kotlin/Waste.kt`, `References.kt`, `OffHeap.kt` | Collectors called from the single pass; one report section each. |
| `src/main/kotlin/Leaks.kt` | Per-suspect paths and leak detection (JVM rules, library patterns). |
| `src/main/kotlin/Paths.kt` | BFS tree from the GC roots and merged paths by class. |
| `src/main/kotlin/Retained.kt` | Retained by ClassLoader, static fields and immediate dominators by class. |
| `src/main/kotlin/Threads.kt` | Threads, pools, virtual threads, ThreadLocals, grouped stacks. |
| `src/main/kotlin/Graph.kt` | Fan-in/fan-out, depth and edges between classes. |
| `src/main/kotlin/Frameworks.kt` | Session, Hibernate, Spring, JDBC, cache, Jackson and exception inspectors. |
| `src/main/kotlin/Health.kt` | Health panel rules (a pure function over `HeapReport`). |
| `src/main/kotlin/Snapshot.kt` | JSON snapshot with the full histogram and the collections by path (`--format json`). |
| `src/main/kotlin/Diff.kt` | Comparison with earlier snapshots (`--baseline`). |
| `src/main/kotlin/Reports.kt` | Markdown and HTML generation. |
| `src/main/kotlin/I18n.kt` | Loads language files and formats messages. |
| `src/main/resources/report.html` | HTML template: CSS and the JavaScript that renders tables and charts. |
| `src/main/resources/echarts.min.js` | Apache ECharts, embedded in every HTML report. |
| `src/main/resources/i18n/` | Language files. |
| `src/main/resources/version.properties` | Filled with the `version` from `build.gradle.kts` at build time; read by `VERSION` in `Main.kt`. |

To release a new version, change `version` in `build.gradle.kts`; nothing else holds the number.

Analysis pipeline:

1. Shark indexes the dump (all GC root types are indexed, not only Shark's default subset).
2. A single pass over every object builds the class histogram, the duplicate string counts, the class loader list and
   the reference graph, and feeds the waste, references and off-heap collectors (a second pass reads only primitive
   array contents). Weak, soft and phantom referents are skipped, and every instance also references its class.
3. A virtual root is linked to every GC root, and the dominator tree of the whole graph is computed with the
   **Semi-NCA** algorithm (Lengauer–Tarjan semidominators and nearest common ancestor). It is iterative and uses only
   `int` arrays, so it scales to tens of millions of objects. Shark 2.14 has a dominator tree of its own, but it is
   internal to the library.
4. Retained sizes are accumulated bottom-up over the tree.
5. Shark's `HeapAnalyzer` finds the shortest GC root path for each suspect and applies the JDK `ObjectInspectors`.
6. Thread stack traces are read from the hprof `STACK TRACE` and `STACK FRAME` records.
7. The `HeapReport` is written as Markdown, or serialized to JSON and embedded in the HTML template together with
   ECharts and the language strings.

Implementation notes:

- **Shark 2.14 bug workaround**: `HeapGraph.findObjectById` returns a wrong `objectIndex` for primitive arrays, and
  `findObjectByIndex` fails for them. The analyzer fixes the index (`HeapGraph.indexOf` in `Analyzer.kt`) and keeps
  its own index-to-id table.
- **HTML safety**: dump content (strings, class names) is untrusted. It is embedded as JSON with `</` escaped, and the
  page inserts it with `textContent`, never as HTML.

## 10. Limitations

- Sizes are estimates for a 64-bit HotSpot with compressed class pointers and 8-byte alignment (see
  [section 6](#6-key-concepts)); `-XX:-UseCompressedClassPointers` or another `ObjectAlignmentInBytes` are not detected.
- Only HotSpot/OpenJDK dumps are tested. Android dumps are readable by Shark but have not been tested.
- Suspects are chosen by size. The tool does not decide by itself whether something is a leak; the GC root path is
  what shows it.
- The class retained size can still count some memory twice when instances of a class dominate each other through
  objects of another class.
- Compressed dumps (`.hprof.gz`) must be decompressed first.

## 11. Troubleshooting

| Problem | Solution |
| --- | --- |
| `OutOfMemoryError` during the analysis | Increase `-Xmx`, or use `--no-retained`. |
| Garbled accented characters in the console (Windows) | Run `chcp 65001` first, or add `-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8` to the `java` command. The report files are always UTF-8. |
| `Warning: language "xx" not found` | No `messages_xx.properties` file exists; see [adding a language](#adding-a-language). |
| "Paths to GC roots" is empty | Retained sizes are needed to choose suspects. Remove `--no-retained` or use `--leak-class`. |
| Big part of the heap is "unreachable" | Usually objects held only by soft/weak references (caches), or garbage in a dump taken with `-all`. |
| Charts do not appear | Check the browser console. The file must be opened whole; it does not load anything from the network. |
