package hprof

import kotlinx.serialization.Serializable
import shark.FilteringLeakingObjectFinder
import shark.HeapAnalysisFailure
import shark.HeapAnalysisSuccess
import shark.HeapAnalyzer
import shark.HeapGraph
import shark.HeapObject
import shark.HeapObject.HeapInstance
import shark.IgnoredReferenceMatcher
import shark.LeakTrace
import shark.LeakTraceReference.ReferenceType
import shark.LeakingObjectFinder
import shark.LibraryLeak
import shark.LibraryLeakReferenceMatcher
import shark.ObjectInspector
import shark.ObjectInspectors
import shark.ObjectReporter
import shark.OnAnalysisProgressListener
import shark.ReferenceMatcher
import shark.ReferencePattern.InstanceFieldPattern
import shark.ReferencePattern.StaticFieldPattern
import java.io.File

/** Leaking objects found by JVM rules (and --leak-class), grouped by Shark's leak signature. */
@Serializable
data class LeakReport(val leakingObjects: Int, val groups: List<LeakGroup>)

/**
 * [kind]: "application" or "library" (the path goes through a known JDK/framework reference, see [LIBRARY_LEAKS]).
 * [retained]: Shark's estimate, computed over its own shortest-path tree; may differ from the dominator tree numbers.
 */
@Serializable
data class LeakGroup(
    val kind: String, val description: String, val signature: String, val occurrences: Int, val retained: Long?, val trace: LeakPath,
)

/** A JVM-level leak rule: instances of [className] for which [test] holds are leaking. */
private class JvmLeak(val className: String, val reason: String, val test: (HeapInstance) -> Boolean)

private fun HeapInstance.closed() = field("closed")?.asBoolean == true

private val JVM_LEAKS = listOf(
    JvmLeak("java.lang.Thread", "Thread terminated but still referenced") {
        ((it.threadField("threadStatus")?.asInt ?: 0) and 0x2) != 0
    },
    JvmLeak("org.apache.catalina.loader.WebappClassLoaderBase", "Tomcat webapp ClassLoader stopped but still referenced") {
        it.refField("state")?.asInstance?.get("java.lang.Enum", "name")?.value?.readAsJavaString() in setOf("STOPPED", "DESTROYED")
    },
    JvmLeak("org.apache.catalina.session.StandardSession", "HTTP session invalidated but still referenced") {
        it.field("isValid")?.asBoolean == false
    },
    JvmLeak("java.io.FileInputStream", "Stream closed but still referenced") { it.closed() },
    JvmLeak("java.io.FileOutputStream", "Stream closed but still referenced") { it.closed() },
    JvmLeak("java.io.RandomAccessFile", "File closed but still referenced") { it.closed() },
)

// ponytail: short list of well-known JDK leak paths; extend as real dumps show more
private val LIBRARY_LEAKS: List<ReferenceMatcher> = listOf(
    LibraryLeakReferenceMatcher(InstanceFieldPattern("java.lang.Thread", "contextClassLoader"),
        "Thread context ClassLoader: a long-lived thread keeps the ClassLoader that was current when it was created (common after a webapp redeploy)."),
    LibraryLeakReferenceMatcher(InstanceFieldPattern("java.lang.ThreadLocal\$ThreadLocalMap\$Entry", "value"),
        "ThreadLocal value never removed: pooled threads keep it alive."),
    LibraryLeakReferenceMatcher(StaticFieldPattern("java.sql.DriverManager", "registeredDrivers"),
        "JDBC driver registered by the application and never deregistered."),
    LibraryLeakReferenceMatcher(StaticFieldPattern("java.lang.ApplicationShutdownHooks", "hooks"),
        "Shutdown hook registered and never removed."),
)

private val IGNORED_REFERENT = IgnoredReferenceMatcher(InstanceFieldPattern("java.lang.ref.Reference", "referent"))

internal fun toLeakPath(trace: LeakTrace, title: String): LeakPath {
    val objects = trace.referencePath.map { it.originObject } + trace.leakingObject
    val refs = listOf<String?>(null) + trace.referencePath.map { ref ->
        val static = if (ref.referenceType == ReferenceType.STATIC_FIELD) "static " else ""
        "$static${ref.owningClassSimpleName}.${ref.referenceDisplayName}"
    }
    return LeakPath(
        title = title,
        gcRoot = trace.gcRootType.description,
        nodes = objects.mapIndexed { i, o ->
            PathNode(o.className, o.typeName, o.leakingStatus.name, o.leakingStatusReason, o.labels.toList(), refs[i])
        },
    )
}

internal fun leakSuspects(file: File, graph: HeapGraph, leakClasses: Set<String>, top: Int): LeakReport {
    // rules that apply per instance class, cached (instanceOf walks the class hierarchy)
    val rulesByClass = HashMap<Long, List<JvmLeak>>()
    fun rules(o: HeapInstance) = rulesByClass.getOrPut(o.instanceClassId) { JVM_LEAKS.filter { o instanceOf it.className } }
    fun reasons(o: HeapObject): List<String> {
        if (o !is HeapInstance) return emptyList()
        val requested = if (o.instanceClassName in leakClasses) listOf("Requested with --leak-class") else emptyList()
        return requested + rules(o).filter { it.test(o) }.map { it.reason }
    }
    var leaking = 0
    val finder = FilteringLeakingObjectFinder(listOf(object : FilteringLeakingObjectFinder.LeakingObjectFilter {
        override fun isLeakingObject(heapObject: HeapObject) = reasons(heapObject).isNotEmpty().also { if (it) leaking++ }
    }))
    val inspector = object : ObjectInspector {
        override fun inspect(reporter: ObjectReporter) { reporter.leakingReasons += reasons(reporter.heapObject) }
    }
    val analysis = HeapAnalyzer(OnAnalysisProgressListener.NO_OP).analyze(
        heapDumpFile = file,
        graph = graph,
        leakingObjectFinder = finder,
        referenceMatchers = LIBRARY_LEAKS + IGNORED_REFERENT,
        computeRetainedHeapSize = true,
        objectInspectors = ObjectInspectors.jdkDefaults + inspector,
    )
    if (analysis is HeapAnalysisFailure) throw analysis.exception
    val groups = (analysis as HeapAnalysisSuccess).allLeaks.map { leak ->
        val trace = leak.leakTraces.first()
        LeakGroup(
            kind = if (leak is LibraryLeak) "library" else "application",
            description = if (leak is LibraryLeak) leak.description else trace.leakingObject.leakingStatusReason,
            signature = leak.signature,
            occurrences = leak.leakTraces.size,
            retained = leak.totalRetainedHeapByteSize?.toLong(),
            trace = toLeakPath(trace, leak.shortDescription),
        )
    }.sortedWith(compareByDescending<LeakGroup> { it.retained ?: 0 }.thenByDescending { it.occurrences }).take(top).toList()
    return LeakReport(leaking, groups)
}

/** Shortest path from a GC root to each suspect (one BFS per suspect). */
internal fun findPaths(
    file: File, graph: HeapGraph, suspects: Set<Long>, opt: Options, warnings: MutableList<String>,
): List<LeakPath> {
    val analyzer = HeapAnalyzer(OnAnalysisProgressListener.NO_OP)
    fun warn(id: Long, e: Throwable) {
        val w = opt.msg["log.analyzerFailed", hex(id), e.toString()]
        opt.log(w); warnings.add(w)
    }
    // ponytail: one BFS per suspect; a single analyze() drops paths that pass through another suspect.
    return suspects.flatMap { id ->
        // Shark can throw before its own try (e.g. class name indexed but CLASS_DUMP missing from the dump)
        val analysis = try {
            analyzer.analyze(
                heapDumpFile = file,
                graph = graph,
                leakingObjectFinder = LeakingObjectFinder { setOf(id) },
                referenceMatchers = listOf(IGNORED_REFERENT),
                computeRetainedHeapSize = false,
                objectInspectors = ObjectInspectors.jdkDefaults,
            )
        } catch (e: Exception) {
            warn(id, e)
            return@flatMap emptyList()
        }
        if (analysis is HeapAnalysisFailure) {
            warn(id, analysis.exception)
            return@flatMap emptyList()
        }
        (analysis as HeapAnalysisSuccess).allLeaks.flatMap { it.leakTraces }
            .map { toLeakPath(it, "${it.leakingObject.className} @${hex(id)}") }.toList()
    }
}
