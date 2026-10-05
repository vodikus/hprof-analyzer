package hprof

import kotlinx.serialization.Serializable
import shark.HeapField
import shark.HeapGraph
import shark.HeapObject.HeapInstance

@Serializable
data class RefReport(
    /** Soft / Weak / Phantom / Final. */
    val kinds: List<RefKind>,
    /** Reference subclasses by instance count (WeakHashMap$Entry, ThreadLocalMap$Entry...). */
    val byClass: List<NamedCount>,
    /** java.lang.ref.Finalizer.queue length: objects waiting for finalize(); null if not readable. */
    val finalizerQueue: Long?,
    /** Classes of objects registered for finalization (= classes that override finalize()). */
    val finalizable: List<NamedCount>,
    val cleaners: List<NamedCount>,
)

/** [referentBytes]: shallow size of the referents still set (not cleared by the GC). */
@Serializable
data class RefKind(val kind: String, val count: Long, val withReferent: Long, val referentBytes: Long)

private val REF_KINDS = listOf(
    "Soft" to "java.lang.ref.SoftReference",
    "Weak" to "java.lang.ref.WeakReference",
    "Phantom" to "java.lang.ref.PhantomReference",
    "Final" to "java.lang.ref.FinalReference",
)

// ponytail: cap bounds the id list; a dump with more soft references undercounts the soft-only bytes
private const val MAX_SOFT = 1_000_000

private val CLEANERS = setOf("jdk.internal.ref.Cleaner", "sun.misc.Cleaner", "jdk.internal.ref.CleanerImpl\$PhantomCleanableRef")

internal class RefCollector(private val graph: HeapGraph, private val sizeOf: SizeOf) : Collector() {
    /** Kind per instance class id; "" = not a Reference. */
    private val kindByClass = HashMap<Long, String>()
    private class KindAcc { var count = 0L; var withReferent = 0L; var referentBytes = 0L }
    private val kinds = LinkedHashMap<String, KindAcc>()
    private val byClass = HashMap<String, Int>()
    private val finalizable = HashMap<String, Int>()
    private val cleaners = HashMap<String, Int>()
    /** Referent ids of SoftReferences, to measure what only they keep alive (see [softOnlyBytes]). */
    val softReferents = ArrayList<Long>()

    fun instance(obj: HeapInstance, name: String, fields: List<HeapField>) = safe {
        val kind = kindByClass.getOrPut(obj.instanceClassId) {
            REF_KINDS.firstOrNull { obj instanceOf it.second }?.first ?: ""
        }
        if (kind.isEmpty()) return@safe
        val acc = kinds.getOrPut(kind) { KindAcc() }
        acc.count++
        byClass.merge(name, 1, Int::plus)
        if (name in CLEANERS) cleaners.merge(name, 1, Int::plus)
        val id = fields.firstOrNull { it.name == "referent" && it.declaringClass.name == "java.lang.ref.Reference" }
            ?.value?.asNonNullObjectId ?: return@safe
        val referent = graph.findObjectByIdOrNull(id) ?: return@safe
        acc.withReferent++
        if (kind == "Soft" && softReferents.size < MAX_SOFT) softReferents.add(id)
        acc.referentBytes += sizeOf(referent)
        if (name == "java.lang.ref.Finalizer") finalizable.merge(referent.className(), 1, Int::plus)
    }

    fun result(top: Int): RefReport {
        checkOk()
        fun counts(m: Map<String, Int>) = m.map { NamedCount(it.key, it.value) }.sortedByDescending { it.count }.take(top)
        val queue = graph.findClassByName("java.lang.ref.Finalizer")?.get("queue")?.valueAsInstance
            ?.get("java.lang.ref.ReferenceQueue", "queueLength")?.value?.asLong
        return RefReport(
            kinds = REF_KINDS.mapNotNull { (k, _) -> kinds[k]?.let { RefKind(k, it.count, it.withReferent, it.referentBytes) } },
            byClass = counts(byClass),
            finalizerQueue = queue,
            finalizable = counts(finalizable),
            cleaners = counts(cleaners),
        )
    }
}
