package hprof

import kotlinx.serialization.Serializable
import shark.GcRoot
import shark.HeapGraph
import shark.HeapObject.HeapInstance
import shark.HprofRecord.StackFrameRecord
import shark.HprofRecord.StackTraceRecord
import shark.HprofRecordTag
import shark.StreamingHprofReader
import java.io.File
import java.util.EnumSet

@Serializable
data class ConcurrencyReport(
    /** Platform threads by Thread.State (from the threadStatus field; the hprof has no monitors). */
    val states: List<NamedCount>,
    val pools: List<PoolStat>,
    val virtualThreads: Int,
    /** Retained size of the virtual threads' continuations (their stacks). */
    val virtualRetained: Long?,
    /** Classes of the values held in ThreadLocals, all threads together. */
    val threadLocalValues: List<NamedCount>,
    /** Threads sharing exactly the same stack (>= 2). */
    val stackGroups: List<StackGroup>,
    /** Frames aggregated from the top of the stacks (innermost frame first); value = thread count. */
    val flame: TreeNode?,
    /** Every pool by class (before the top-N cut of [pools]). */
    val poolsByClass: List<NamedCount> = emptyList(),
    /** ThreadPoolExecutors with max = Integer.MAX_VALUE and an unbounded queue (max never reached); scheduled pools excluded. */
    val unboundedPools: Int = 0,
)

/**
 * [queued]: tasks waiting in the work queue; null when the queue type is not known.
 * [unbounded]: the work queue has no capacity limit; null when unknown.
 */
@Serializable
data class PoolStat(
    val kind: String, val className: String, val id: String, val core: Int?, val max: Int?, val threads: Int?,
    val queueType: String?, val queued: Int?, val completed: Long?, val unbounded: Boolean? = null,
    /** ScheduledThreadPoolExecutor (or subclass): max = Integer.MAX_VALUE with a DelayedWorkQueue is its design. */
    val scheduled: Boolean = false,
)

private val UNBOUNDED_QUEUES = setOf(
    "java.util.concurrent.PriorityBlockingQueue", "java.util.concurrent.ScheduledThreadPoolExecutor\$DelayedWorkQueue",
    "java.util.concurrent.LinkedTransferQueue", "java.util.concurrent.DelayQueue",
)

@Serializable
data class StackGroup(val count: Int, val threads: List<String>, val frames: List<String>)

// ponytail: fixed caps
private const val MAX_POOLS = 1000
private const val STACK_GROUP_NAMES = 10
private const val FLAME_DEPTH = 10
private const val FLAME_WIDTH = 15

/** java.lang.Thread.State from the JVMTI bits in threadStatus (same mapping as jdk.internal.misc.VM.toThreadState). */
internal fun threadState(status: Int): String = when {
    status and 0x4 != 0 -> "RUNNABLE"
    status and 0x400 != 0 -> "BLOCKED"
    status and 0x10 != 0 -> "WAITING"
    status and 0x20 != 0 -> "TIMED_WAITING"
    status and 0x2 != 0 -> "TERMINATED"
    status and 0x1 == 0 -> "NEW"
    else -> "RUNNABLE"
}

private val POOL_KINDS = listOf(
    "ThreadPoolExecutor" to "java.util.concurrent.ThreadPoolExecutor",
    "ForkJoinPool" to "java.util.concurrent.ForkJoinPool",
    "Timer" to "java.util.Timer",
)

/** Collects executors, timers and virtual threads during the main pass. */
internal class ConcurrencyCollector : Collector() {
    private val kindByClass = HashMap<Long, String>()
    val pools = ArrayList<Pair<String, Long>>()
    val virtualThreads = ArrayList<Long>()

    fun instance(obj: HeapInstance, name: String) = safe {
        if (name == "java.lang.VirtualThread") { virtualThreads.add(obj.objectId); return@safe }
        val kind = kindByClass.getOrPut(obj.instanceClassId) { POOL_KINDS.firstOrNull { obj instanceOf it.second }?.first ?: "" }
        if (kind.isNotEmpty() && pools.size < MAX_POOLS) pools.add(kind to obj.objectId)
    }

    fun result(graph: HeapGraph, retained: LongArray?, threads: List<ThreadInfo>, tlValues: Map<String, Int>, top: Int): ConcurrencyReport {
        checkOk()
        val all = pools.mapNotNull { (kind, id) -> graph.findObjectByIdOrNull(id)?.asInstance?.let { pool(kind, it) } }
        val pools = all.sortedWith(compareByDescending<PoolStat> { it.queued ?: -1 }.thenByDescending { it.threads ?: -1 }).take(top)
        val vRetained = retained?.let { r ->
            virtualThreads.sumOf { id ->
                graph.findObjectByIdOrNull(id)?.asInstance?.refField("cont")?.let { r[graph.indexOf(it)] } ?: 0L
            }
        }
        return ConcurrencyReport(
            states = threads.groupingBy { it.state ?: "?" }.eachCount().map { NamedCount(it.key, it.value) }.sortedByDescending { it.count },
            pools = pools,
            virtualThreads = virtualThreads.size,
            virtualRetained = vRetained,
            threadLocalValues = tlValues.map { NamedCount(it.key, it.value) }.sortedByDescending { it.count }.take(top),
            stackGroups = threads.filter { it.frames.isNotEmpty() }.groupBy { t -> t.frames.map { it.text } }
                .filterValues { it.size > 1 }.map { (frames, ts) -> StackGroup(ts.size, ts.take(STACK_GROUP_NAMES).map { it.name }, frames) }
                .sortedByDescending { it.count }.take(top),
            flame = flame(threads),
            poolsByClass = all.groupingBy { it.className }.eachCount().map { NamedCount(it.key, it.value) }.sortedByDescending { it.count },
            unboundedPools = all.count { it.max == Int.MAX_VALUE && it.unbounded == true && !it.scheduled },
        )
    }

    private fun pool(kind: String, p: HeapInstance): PoolStat {
        fun int(name: String) = p.field(name)?.asInt
        val queue = when (kind) {
            "ThreadPoolExecutor" -> p.refField("workQueue")?.asInstance
            "Timer" -> p.refField("queue")?.asInstance
            else -> null
        }
        return when (kind) {
            "ThreadPoolExecutor" -> PoolStat(kind, p.instanceClassName, hex(p.objectId), int("corePoolSize"), int("maximumPoolSize"),
                p.refField("workers")?.asInstance?.refField("map")?.asInstance?.field("size")?.asInt,
                queue?.instanceClassName, queue?.let(::queueSize), p.field("completedTaskCount")?.asLong, queue?.let(::unbounded),
                p instanceOf "java.util.concurrent.ScheduledThreadPoolExecutor")
            "ForkJoinPool" -> {
                // queued = sum of (top - base) over the work queues ("queues" in JDK 25, "workQueues" before)
                val queues = (p.refField("queues") ?: p.refField("workQueues"))?.asObjectArray?.readElements()?.mapNotNull { it.asObject?.asInstance }?.toList()
                val queued = queues?.sumOf { q -> ((q.field("top")?.asInt ?: 0) - (q.field("base")?.asInt ?: 0)).coerceAtLeast(0) }
                PoolStat(kind, p.instanceClassName, hex(p.objectId), int("parallelism"), null, null, null, queued, null)
            }
            else -> PoolStat(kind, p.instanceClassName, hex(p.objectId), null, null, 1, queue?.instanceClassName,
                queue?.field("size")?.asInt, null)
        }
    }

    /** Element count of the common BlockingQueue implementations. */
    private fun queueSize(q: HeapInstance): Int? {
        q.field("count")?.let { c -> return c.asInt ?: c.asObject?.asInstance?.field("value")?.asInt } // ArrayBQ, LinkedBQ (AtomicInteger), LinkedBDeque
        q.field("size")?.asInt?.let { return it } // DelayedWorkQueue, PriorityBlockingQueue
        return if (q.instanceClassName == "java.util.concurrent.SynchronousQueue") 0 else null
    }

    /** LinkedBlockingQueue/Deque without a capacity, or a queue type that has none; null = unknown. */
    private fun unbounded(q: HeapInstance): Boolean? = when {
        q.instanceClassName in UNBOUNDED_QUEUES -> true
        q.instanceClassName == "java.util.concurrent.SynchronousQueue" -> false
        else -> q.field("capacity")?.asInt?.let { it == Int.MAX_VALUE }
    }

    private fun flame(threads: List<ThreadInfo>): TreeNode? {
        class Node(val name: String) { var value = 0; val kids = LinkedHashMap<String, Node>() }
        val root = Node("threads")
        for (t in threads) {
            if (t.frames.isEmpty()) continue
            root.value++
            var node = root
            for (f in t.frames.take(FLAME_DEPTH)) {
                node = node.kids.getOrPut(f.text) { Node(f.text) }
                node.value++
            }
        }
        fun convert(n: Node): TreeNode = TreeNode(n.name, n.value.toLong(),
            n.kids.values.sortedByDescending { it.value }.take(FLAME_WIDTH).map(::convert).ifEmpty { null })
        return if (root.value == 0) null else convert(root)
    }
}

/** Top-level hprof records (heap dump sub-records are counted in the summary instead). */
private val TOP_LEVEL_TAGS = EnumSet.range(HprofRecordTag.STRING_IN_UTF8, HprofRecordTag.CONTROL_SETTINGS)

/**
 * Platform threads (ThreadObject GC roots) with stacks, locals, state and ThreadLocals; [tlValues] gets the value
 * classes and [records] the count of each top-level record tag (same pass over the file).
 */
internal fun readThreads(
    file: File, graph: HeapGraph, retained: LongArray?, tlValues: MutableMap<String, Int>, records: MutableMap<String, Int>,
): List<ThreadInfo> {
    val strings = HashMap<Long, String>()
    val classNameIdBySerial = HashMap<Int, Long>()
    val frames = HashMap<Long, StackFrameRecord>()
    val traces = HashMap<Int, StackTraceRecord>()
    StreamingHprofReader.readerFor(file).readRecords(TOP_LEVEL_TAGS) { tag, length, reader ->
        records.merge(tag.name, 1, Int::plus)
        when (tag) {
            HprofRecordTag.STRING_IN_UTF8 -> reader.readStringRecord(length).let { strings[it.id] = it.string }
            HprofRecordTag.LOAD_CLASS -> reader.readLoadClassRecord().let { classNameIdBySerial[it.classSerialNumber] = it.classNameStringId }
            HprofRecordTag.STACK_FRAME -> reader.readStackFrameRecord().let { frames[it.id] = it }
            HprofRecordTag.STACK_TRACE -> reader.readStackTraceRecord().let { traces[it.stackTraceSerialNumber] = it }
            else -> reader.skip(length)
        }
    }

    // objects held as locals by each (thread, frame), and by each thread
    val locals = HashMap<Pair<Int, Int>, MutableList<String>>()
    val localIdx = HashMap<Int, MutableSet<Int>>()
    for (root in graph.gcRoots) if (root is GcRoot.JavaFrame) {
        val o = graph.findObjectByIdOrNull(root.id) ?: continue
        locals.getOrPut(root.threadSerialNumber to root.frameNumber) { ArrayList() }.add(o.label())
        localIdx.getOrPut(root.threadSerialNumber) { HashSet() }.add(graph.indexOf(o))
    }

    return graph.gcRoots.filterIsInstance<GcRoot.ThreadObject>().mapNotNull { root ->
        val thread = graph.findObjectByIdOrNull(root.id)?.asInstance ?: return@mapNotNull null
        val frameList = traces[root.stackTraceSerialNumber]?.stackFrameIds?.mapIndexed { i, fid ->
            val f = frames[fid]
            val text = if (f == null) "?" else {
                val cls = strings[classNameIdBySerial[f.classSerialNumber]]?.let { normalizeClassName(it).replace('/', '.') } ?: "?"
                val method = strings[f.methodNameStringId] ?: "?"
                val src = strings[f.sourceFileNameStringId]
                val where = when {
                    f.lineNumber > 0 -> "$src:${f.lineNumber}"
                    f.lineNumber == -3 -> "Native Method"
                    else -> src ?: "Unknown Source"
                }
                "$cls.$method($where)"
            }
            Frame(text, locals[root.threadSerialNumber to i].orEmpty())
        }.orEmpty()

        // ThreadLocalMap: entries, stale ones (ThreadLocal key already collected) and value classes
        val tlMap = thread["java.lang.Thread", "threadLocals"]?.valueAsInstance
        var entries = 0
        var stale = 0
        tlMap?.refField("table")?.asObjectArray?.readElements()?.forEach { e ->
            val entry = e.asObject?.asInstance ?: return@forEach
            entries++
            if (entry["java.lang.ref.Reference", "referent"]?.value?.isNullReference != false) stale++
            entry.refField("value")?.let { v -> tlValues.merge(v.className(), 1, Int::plus) }
        }
        ThreadInfo(
            name = thread.threadField("name")?.readAsJavaString() ?: "?",
            id = hex(thread.objectId),
            daemon = thread.threadField("daemon")?.asBoolean,
            priority = thread.threadField("priority")?.asInt,
            retained = retained?.get(graph.indexOf(thread)),
            frames = frameList,
            state = thread.threadField("threadStatus")?.asInt?.let(::threadState),
            localsRetained = retained?.let { r -> localIdx[root.threadSerialNumber].orEmpty().sumOf { r[it] } },
            threadLocalsRetained = retained?.let { r -> tlMap?.let { r[graph.indexOf(it)] } },
            threadLocals = tlMap?.let { entries },
            staleThreadLocals = tlMap?.let { stale },
        )
    }.sortedByDescending { it.retained ?: 0 }
}
