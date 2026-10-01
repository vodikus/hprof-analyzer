package hprof

import kotlinx.serialization.Serializable
import shark.GcRoot
import shark.HeapAnalysisFailure
import shark.HeapAnalysisSuccess
import shark.HeapAnalyzer
import shark.HeapGraph
import shark.HeapObject
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.HeapObject.HeapPrimitiveArray
import shark.HprofHeader
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.HprofRecordTag
import shark.HprofRecord.LoadClassRecord
import shark.HprofRecord.StackFrameRecord
import shark.HprofRecord.StackTraceRecord
import shark.HprofRecord.StringRecord
import shark.IgnoredReferenceMatcher
import shark.LeakTraceReference.ReferenceType
import shark.LeakingObjectFinder
import shark.ObjectInspectors
import shark.OnAnalysisProgressListener
import shark.ReferencePattern.InstanceFieldPattern
import shark.StreamingHprofReader
import shark.StreamingRecordReaderAdapter.Companion.asStreamingRecordReader
import java.io.File
import java.time.Instant
import java.util.EnumSet
import java.util.PriorityQueue

@Serializable
data class HeapReport(
    val summary: Summary,
    val classes: List<ClassStat>,
    val packages: List<PackageStat>,
    val retainedObjects: List<ObjectStat>,
    val dominatorTree: TreeNode?,
    val leaks: List<LeakPath>,
    val gcRoots: List<NamedCount>,
    val threads: List<ThreadInfo>,
    val duplicateStrings: List<DupString>,
    val largestArrays: List<ArrayStat>,
    val classLoaders: List<LoaderStat>,
    /** Problems hit while analyzing; the affected sections are empty or partial. */
    val warnings: List<String> = emptyList(),
)

@Serializable
data class Summary(
    val file: String,
    val fileSize: Long,
    val hprofVersion: String,
    val identifierByteSize: Int,
    val timestamp: String,
    val objectCount: Int,
    val classCount: Int,
    val instanceCount: Int,
    val objectArrayCount: Int,
    val primitiveArrayCount: Int,
    val gcRootCount: Int,
    val totalShallow: Long,
    /** null when retained sizes were skipped. */
    val reachableCount: Int?,
    val reachableBytes: Long?,
    val analysisMillis: Long,
    val toolVersion: String,
)

@Serializable
data class ClassStat(val name: String, val count: Long, val shallow: Long, val retained: Long?)

@Serializable
data class PackageStat(val name: String, val count: Long, val shallow: Long, val retained: Long?)

@Serializable
data class ObjectStat(val id: String, val className: String, val shallow: Long, val retained: Long, val detail: String?)

@Serializable
data class TreeNode(val name: String, val value: Long, val children: List<TreeNode>? = null)

@Serializable
data class LeakPath(val title: String, val gcRoot: String, val nodes: List<PathNode>)

/** [reference] is how the previous node points to this one (null for the GC root). */
@Serializable
data class PathNode(
    val className: String,
    val type: String,
    val status: String,
    val reason: String,
    val labels: List<String>,
    val reference: String?,
)

@Serializable
data class NamedCount(val name: String, val count: Int)

@Serializable
data class ThreadInfo(
    val name: String,
    val id: String,
    val daemon: Boolean?,
    val priority: Int?,
    val retained: Long?,
    val frames: List<Frame>,
)

@Serializable
data class Frame(val text: String, val locals: List<String>)

@Serializable
data class DupString(val value: String, val count: Int, val bytesEach: Long, val wasted: Long)

@Serializable
data class ArrayStat(val id: String, val className: String, val length: Int, val bytes: Long, val retained: Long?)

@Serializable
data class LoaderStat(val id: String, val className: String, val classesLoaded: Int, val retained: Long?)

class Options(
    val top: Int = 50,
    val retained: Boolean = true,
    val leakClasses: Set<String> = emptySet(),
    val msg: Messages = Messages.load(),
    val log: (String) -> Unit = { System.err.println(it) },
)

// ponytail: sizes are what the hprof records hold (field/element bytes, no object header/alignment),
// same convention as Shark. Add a JVM header model if absolute numbers must match MAT.
private const val MAX_DEDUP_STRING = 1024
private const val MAX_STRING_SHOWN = 200
private const val SUSPECTS = 10

private class IntList(cap: Int) {
    var data = IntArray(maxOf(cap, 16))
    var size = 0
    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }
}

private class Acc(val name: String) { var count = 0L; var shallow = 0L; var retained = 0L }

private class StrAcc(var count: Int, val bytes: Long)

private fun hex(id: Long) = "0x" + java.lang.Long.toHexString(id)

private fun packageOf(className: String, msg: Messages): String {
    val base = className.substringBefore('[')
    if (base != className && '.' !in base) return msg["misc.primitiveArrays"]
    val dot = base.lastIndexOf('.')
    return if (dot < 0) "(default)" else base.substring(0, dot)
}

private fun <T> topN(n: Int, items: Sequence<T>, key: (T) -> Long): List<T> {
    val pq = PriorityQueue<T>(compareBy(key))
    for (item in items) {
        pq.add(item)
        if (pq.size > n) pq.poll()
    }
    return pq.sortedByDescending(key)
}

private fun HeapObject.className(): String = when (this) {
    is HeapClass -> "java.lang.Class"
    is HeapInstance -> instanceClassName
    is HeapObjectArray -> arrayClassName
    is HeapPrimitiveArray -> arrayClassName
}

private fun HeapObject.label(): String = if (this is HeapClass) "class $name" else className()

private fun HeapObject.shallowSize(): Long = when (this) {
    is HeapClass -> recordSize.toLong()
    is HeapInstance -> byteSize.toLong()
    is HeapObjectArray -> byteSize.toLong()
    is HeapPrimitiveArray -> byteSize.toLong()
}

// Shark 2.14 bug (HprofInMemoryIndex.indexedObjectOrNull): primitive arrays looked up by id get
// objectIndex shifted by primitiveArrayCount - objectArrayCount, and findObjectByIndex fails for them.
// Objects coming from the graph.objects/xxxArrays sequences are indexed correctly.
private fun HeapGraph.indexOf(lookedUp: HeapObject) =
    if (lookedUp is HeapPrimitiveArray) lookedUp.objectIndex - primitiveArrayCount + objectArrayCount
    else lookedUp.objectIndex

/** Thread fields moved into Thread.holder (Thread$FieldHolder) in JDK 19+. */
private fun HeapInstance.threadField(name: String) =
    this["java.lang.Thread", name]?.value
        ?: this["java.lang.Thread", "holder"]?.valueAsInstance?.get("java.lang.Thread\$FieldHolder", name)?.value

private fun HeapObject.detail(): String? = when {
    this is HeapInstance && instanceClassName == "java.lang.String" -> readAsJavaString()?.take(MAX_STRING_SHOWN)
    this is HeapInstance && instanceOf("java.lang.Thread") -> threadField("name")?.readAsJavaString()
    else -> null
}

fun analyze(file: File, opt: Options = Options()): HeapReport {
    val start = System.currentTimeMillis()
    val header = HprofHeader.parseHeaderOf(file)
    opt.log(opt.msg["log.indexing", file.name])
    // index every GC root type (Shark's default skips VmInternal, InternedString, ...)
    val rootTags = EnumSet.copyOf(HprofRecordTag.values().filter { it.name.startsWith("ROOT_") })
    file.openHeapGraph(indexedGcRootTypes = rootTags).use { graph ->
        val n = graph.objectCount
        opt.log(opt.msg["log.scanning", n])

        // ---- single pass: histogram, references, strings, class loaders ----
        val classAccs = ArrayList<Acc>()
        val accByName = HashMap<String, Int>()
        val classNameById = HashMap<Long, String>()
        val clsOf = IntArray(n)
        val ids = LongArray(n)
        val shallow = LongArray(n + 1) // slot n = virtual GC root
        val edgeSrc = IntList(n * 2)
        val edgeDst = IntList(n * 2)
        val strings = HashMap<String, StrAcc>()
        val isLoaderClass = HashMap<Long, Boolean>()
        val loaderIds = ArrayList<Long>()
        val classesPerLoader = HashMap<Long, Int>()
        val leakIds = LinkedHashSet<Long>()
        var totalShallow = 0L

        fun addEdge(from: Int, toId: Long) {
            val target = graph.findObjectByIdOrNull(toId) ?: return
            edgeSrc.add(from); edgeDst.add(graph.indexOf(target))
        }

        var seen = 0
        for (obj in graph.objects) {
            val idx = obj.objectIndex
            check(idx in 0 until n) { "objectIndex out of range: $idx" }
            ids[idx] = obj.objectId
            val name = when (obj) {
                is HeapInstance -> classNameById.getOrPut(obj.instanceClassId) { obj.instanceClassName }
                is HeapObjectArray -> classNameById.getOrPut(obj.arrayClassId) { obj.arrayClassName }
                else -> obj.className()
            }
            val acc = accByName.getOrPut(name) { classAccs.add(Acc(name)); classAccs.size - 1 }
            clsOf[idx] = acc
            val size = obj.shallowSize()
            shallow[idx] = size
            classAccs[acc].count++
            classAccs[acc].shallow += size
            totalShallow += size

            when (obj) {
                is HeapClass -> {
                    for (f in obj.readStaticFields()) if (f.value.isNonNullReference) addEdge(idx, f.value.asNonNullObjectId!!)
                    val loaderId = obj.readRecord().classLoaderId
                    classesPerLoader.merge(loaderId, 1, Int::plus)
                    if (loaderId != 0L) addEdge(idx, loaderId)
                }
                is HeapInstance -> {
                    addEdge(idx, obj.instanceClassId) // an instance keeps its class alive
                    for (f in obj.readFields()) {
                        if (!f.value.isNonNullReference) continue
                        // weak/soft/phantom referents do not retain memory
                        if (f.name == "referent" && f.declaringClass.name == "java.lang.ref.Reference") continue
                        addEdge(idx, f.value.asNonNullObjectId!!)
                    }
                    if (name == "java.lang.String") {
                        val value = obj.readAsJavaString()
                        if (value != null && value.length <= MAX_DEDUP_STRING) {
                            val arrayBytes = obj["java.lang.String", "value"]?.valueAsPrimitiveArray?.byteSize ?: 0
                            strings.getOrPut(value) { StrAcc(0, size + arrayBytes) }.count++
                        }
                    }
                    if (isLoaderClass.getOrPut(obj.instanceClassId) { obj instanceOf "java.lang.ClassLoader" }) {
                        loaderIds.add(obj.objectId)
                    }
                    if (name in opt.leakClasses && leakIds.size < SUSPECTS * 2) leakIds.add(obj.objectId)
                }
                is HeapObjectArray -> {
                    for (e in obj.readElements()) if (e.isNonNullReference) addEdge(idx, e.asNonNullObjectId!!)
                }
                is HeapPrimitiveArray -> Unit
            }
            if (++seen % 1_000_000 == 0) opt.log(opt.msg["log.progress", seen, n])
        }

        // ---- virtual root (index n) points to every GC root object ----
        for (r in graph.gcRoots.mapNotNull { graph.findObjectByIdOrNull(it.id)?.let(graph::indexOf) }.distinct()) {
            edgeSrc.add(n); edgeDst.add(r)
        }

        // ---- dominator tree / retained sizes ----
        var dom: Dominators? = null
        var retained: LongArray? = null
        if (opt.retained) {
            opt.log(opt.msg["log.dominators", edgeSrc.size])
            val offsets = IntArray(n + 2)
            for (i in 0 until edgeSrc.size) offsets[edgeSrc.data[i] + 1]++
            for (i in 0..n) offsets[i + 1] += offsets[i]
            val targets = IntArray(edgeSrc.size)
            val fill = offsets.copyOf(n + 1)
            for (i in 0 until edgeSrc.size) targets[fill[edgeSrc.data[i]]++] = edgeDst.data[i]
            edgeSrc.data = IntArray(0); edgeDst.data = IntArray(0)

            val d = dominators(n + 1, n, offsets, targets)
            val ret = shallow.copyOf()
            for (k in d.order.size - 1 downTo 1) {
                val v = d.order[k]
                ret[d.idom[v]] += ret[v]
            }
            // ponytail: class retained = instances not dominated by an instance of the same class
            // (avoids double counting linked lists); chains through other classes still double count.
            for (k in 1 until d.order.size) {
                val v = d.order[k]
                val parent = d.idom[v]
                if (parent == n || clsOf[parent] != clsOf[v]) classAccs[clsOf[v]].retained += ret[v]
            }
            dom = d; retained = ret
        }

        val top = opt.top
        val classes = classAccs.sortedByDescending { if (opt.retained) it.retained else it.shallow }
            .take(top)
            .map { ClassStat(it.name, it.count, it.shallow, if (opt.retained) it.retained else null) }
        val packages = classAccs.groupBy { packageOf(it.name, opt.msg) }
            .map { (pkg, list) ->
                PackageStat(pkg, list.sumOf { it.count }, list.sumOf { it.shallow },
                    if (opt.retained) list.sumOf { it.retained } else null)
            }
            .sortedByDescending { it.shallow }

        // From here on each section is independent: a failure (e.g. an inconsistent dump) becomes a
        // report warning and an empty section instead of losing the whole analysis.
        val warnings = ArrayList<String>()
        fun <T> guard(section: String, fallback: T, block: () -> T): T = try { block() } catch (e: Exception) {
            val w = opt.msg["warn.section", opt.msg[section], e.toString()]
            opt.log(w); warnings.add(w); fallback
        }

        val topRetained = if (dom != null) topN(top, dom.order.asSequence().drop(1)) { retained!![it] } else emptyList()
        val retainedObjects = guard("section.objects", emptyList()) {
            topRetained.map { idx ->
                val o = graph.findObjectById(ids[idx])
                ObjectStat(hex(o.objectId), o.label(), shallow[idx], retained!![idx], o.detail())
            }
        }
        val tree = guard("section.dominators", null) { dom?.let { buildTree(graph, ids, it, retained!!) } }

        // ---- leaks / paths to GC roots (Shark HeapAnalyzer) ----
        val leaks = guard("section.paths", emptyList()) {
            val suspects = LinkedHashSet(leakIds)
            // class objects are GC roots themselves (trivial path), so skip them
            topRetained.asSequence().map { graph.findObjectById(ids[it]) }.filter { it !is HeapClass }
                .take(SUSPECTS).forEach { suspects.add(it.objectId) }
            opt.log(opt.msg["log.paths", suspects.size])
            findPaths(file, graph, suspects, opt, warnings)
        }

        // ---- GC roots / threads ----
        val gcRoots = guard("section.gcRoots", emptyList()) {
            graph.gcRoots.groupingBy { it::class.simpleName ?: "?" }.eachCount()
                .map { NamedCount(it.key, it.value) }.sortedByDescending { it.count }
        }
        opt.log(opt.msg["log.threads"])
        val threads = guard("section.threads", emptyList()) { readThreads(file, graph, retained) }

        // ---- strings / arrays / class loaders ----
        val duplicateStrings = guard("section.strings", emptyList()) {
            topN(top, strings.entries.asSequence().filter { it.value.count > 1 }) {
                (it.value.count - 1) * it.value.bytes
            }.map { DupString(it.key.take(MAX_STRING_SHOWN), it.value.count, it.value.bytes, (it.value.count - 1) * it.value.bytes) }
        }

        val arrays = guard("section.arrays", emptyList()) {
            topN(top, (graph.objectArrays + graph.primitiveArrays).map { it.objectIndex }) { shallow[it] }.map { idx ->
                val o = graph.findObjectById(ids[idx])
                val length = when (o) {
                    is HeapObjectArray -> o.byteSize / graph.identifierByteSize
                    is HeapPrimitiveArray -> o.byteSize / o.primitiveType.byteSize
                    else -> 0
                }
                ArrayStat(hex(o.objectId), o.className(), length, shallow[idx], retained?.get(idx))
            }
        }

        val classLoaders = guard("section.loaders", emptyList()) {
            buildList {
                classesPerLoader[0L]?.let { add(LoaderStat("0x0", "<bootstrap>", it, null)) }
                for (id in loaderIds) {
                    val o = graph.findObjectById(id)
                    add(LoaderStat(hex(id), o.className(), classesPerLoader[id] ?: 0, retained?.get(graph.indexOf(o))))
                }
            }.sortedByDescending { it.classesLoaded }
        }

        val summary = Summary(
            file = file.name,
            fileSize = file.length(),
            hprofVersion = header.version.versionString,
            identifierByteSize = header.identifierByteSize,
            timestamp = Instant.ofEpochMilli(header.heapDumpTimestamp).toString(),
            objectCount = n,
            classCount = graph.classCount,
            instanceCount = graph.instanceCount,
            objectArrayCount = graph.objectArrayCount,
            primitiveArrayCount = graph.primitiveArrayCount,
            gcRootCount = graph.gcRoots.size,
            totalShallow = totalShallow,
            reachableCount = dom?.let { it.order.size - 1 },
            reachableBytes = retained?.get(n),
            analysisMillis = System.currentTimeMillis() - start,
            toolVersion = VERSION,
        )
        return HeapReport(summary, classes, packages, retainedObjects, tree, leaks, gcRoots, threads,
            duplicateStrings, arrays, classLoaders, warnings)
    }
}

/** Top of the dominator tree (3 levels, biggest children only) for the treemap. */
private fun buildTree(graph: HeapGraph, ids: LongArray, dom: Dominators, retained: LongArray): TreeNode {
    val n = ids.size
    val children = HashMap<Int, MutableList<Int>>()
    for (k in 1 until dom.order.size) {
        val v = dom.order[k]
        children.getOrPut(dom.idom[v]) { ArrayList() }.add(v)
    }
    val widths = intArrayOf(100, 15, 8)
    fun node(v: Int, depth: Int): TreeNode {
        val kids = if (depth < widths.size) {
            children[v].orEmpty().sortedByDescending { retained[it] }.take(widths[depth])
                .filter { retained[it] > 0 }.map { node(it, depth + 1) }
        } else emptyList()
        val name = if (v == n) "heap" else graph.findObjectById(ids[v]).label()
        return TreeNode(name, retained[v], kids.ifEmpty { null })
    }
    return node(n, 0)
}

private fun findPaths(
    file: File, graph: HeapGraph, suspects: Set<Long>, opt: Options, warnings: MutableList<String>,
): List<LeakPath> {
    val analyzer = HeapAnalyzer(OnAnalysisProgressListener.NO_OP)
    val matchers = listOf(IgnoredReferenceMatcher(InstanceFieldPattern("java.lang.ref.Reference", "referent")))
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
                referenceMatchers = matchers,
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
        (analysis as HeapAnalysisSuccess).allLeaks.flatMap { it.leakTraces }.map { trace ->
            val objects = trace.referencePath.map { it.originObject } + trace.leakingObject
            val refs = listOf<String?>(null) + trace.referencePath.map { ref ->
                val static = if (ref.referenceType == ReferenceType.STATIC_FIELD) "static " else ""
                "$static${ref.owningClassSimpleName}.${ref.referenceDisplayName}"
            }
            LeakPath(
                title = "${trace.leakingObject.className} @${hex(id)}",
                gcRoot = trace.gcRootType.description,
                nodes = objects.mapIndexed { i, o ->
                    PathNode(o.className, o.typeName, o.leakingStatus.name, o.leakingStatusReason, o.labels.toList(), refs[i])
                },
            )
        }.toList()
    }
}

private fun readThreads(file: File, graph: HeapGraph, retained: LongArray?): List<ThreadInfo> {
    val strings = HashMap<Long, String>()
    val classNameIdBySerial = HashMap<Int, Long>()
    val frames = HashMap<Long, StackFrameRecord>()
    val traces = HashMap<Int, StackTraceRecord>()
    StreamingHprofReader.readerFor(file).asStreamingRecordReader().readRecords(
        setOf(StringRecord::class, LoadClassRecord::class, StackFrameRecord::class, StackTraceRecord::class)
    ) { _, r ->
        when (r) {
            is StringRecord -> strings[r.id] = r.string
            is LoadClassRecord -> classNameIdBySerial[r.classSerialNumber] = r.classNameStringId
            is StackFrameRecord -> frames[r.id] = r
            is StackTraceRecord -> traces[r.stackTraceSerialNumber] = r
            else -> Unit
        }
    }

    // objects held as locals by each (thread, frame)
    val locals = HashMap<Pair<Int, Int>, MutableList<String>>()
    for (root in graph.gcRoots) if (root is GcRoot.JavaFrame) {
        val o = graph.findObjectByIdOrNull(root.id) ?: continue
        locals.getOrPut(root.threadSerialNumber to root.frameNumber) { ArrayList() }.add(o.label())
    }

    return graph.gcRoots.filterIsInstance<GcRoot.ThreadObject>().mapNotNull { root ->
        val thread = graph.findObjectByIdOrNull(root.id)?.asInstance ?: return@mapNotNull null
        val frameList = traces[root.stackTraceSerialNumber]?.stackFrameIds?.mapIndexed { i, fid ->
            val f = frames[fid]
            val text = if (f == null) "?" else {
                val cls = strings[classNameIdBySerial[f.classSerialNumber]]?.replace('/', '.') ?: "?"
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
        ThreadInfo(
            name = thread.threadField("name")?.readAsJavaString() ?: "?",
            id = hex(thread.objectId),
            daemon = thread.threadField("daemon")?.asBoolean,
            priority = thread.threadField("priority")?.asInt,
            retained = retained?.get(graph.indexOf(thread)),
            frames = frameList,
        )
    }.sortedByDescending { it.retained ?: 0 }
}
