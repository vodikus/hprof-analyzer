package hprof

import kotlinx.serialization.Serializable
import shark.HeapField

/** Native memory estimated from the Java objects that own it; the hprof has no native memory itself. */
@Serializable
data class OffHeapReport(val direct: DirectStat, val netty: NettyStat?, val resources: List<ResourceStat>)

/**
 * DirectByteBuffer capacity: owners (att == null) allocate, views share an owner's memory, mapped = files.
 * [viewOwners]: distinct buffers the views point to (a duplicate() of a big buffer counts its whole capacity again).
 */
@Serializable
data class DirectStat(
    val owners: Long, val ownerBytes: Long, val views: Long, val viewBytes: Long, val mapped: Long, val mappedBytes: Long,
    val viewOwners: Long = 0,
)

@Serializable
data class NettyStat(val chunks: Long, val allocated: Long, val used: Long)

/** [open]: instances not closed, when the class has a readable state field; null otherwise. */
@Serializable
data class ResourceStat(val className: String, val count: Long, val open: Long?)

private val DIRECT = setOf("java.nio.DirectByteBuffer", "java.nio.DirectByteBufferR")

private val RESOURCES = listOf(
    "java.io.FileDescriptor", "java.io.FileInputStream", "java.io.FileOutputStream", "java.io.RandomAccessFile",
    "sun.nio.ch.FileChannelImpl", "java.net.Socket", "java.net.ServerSocket", "sun.nio.ch.SocketChannelImpl",
    "sun.nio.ch.ServerSocketChannelImpl", "java.util.zip.ZipFile", "java.util.zip.Inflater", "java.util.zip.Deflater",
)

internal class OffHeapCollector : Collector() {
    private var owners = 0L; private var ownerBytes = 0L
    private var views = 0L; private var viewBytes = 0L
    private val viewOwners = HashSet<Long>()
    private var mapped = 0L; private var mappedBytes = 0L
    private var chunks = 0L; private var allocated = 0L; private var free = 0L
    private class ResAcc { var count = 0L; var open = 0L; var known = false }
    private val resources = LinkedHashMap<String, ResAcc>().apply { RESOURCES.forEach { put(it, ResAcc()) } }

    fun instance(name: String, fields: List<HeapField>) = safe {
        when (name) {
            in DIRECT -> {
                val capacity = fields.named("capacity")?.asInt?.toLong() ?: 0L
                when {
                    fields.named("fd")?.isNonNullReference == true -> { mapped++; mappedBytes += capacity }
                    fields.named("att")?.isNonNullReference == true -> {
                        views++; viewBytes += capacity
                        fields.named("att")?.asNonNullObjectId?.let(viewOwners::add)
                    }
                    else -> { owners++; ownerBytes += capacity }
                }
            }
            "io.netty.buffer.PoolChunk" -> {
                chunks++
                allocated += fields.named("chunkSize")?.asInt ?: 0
                free += fields.named("freeBytes")?.asInt ?: 0
            }
            else -> resources[name]?.let { acc ->
                acc.count++
                val open = when (name) {
                    "java.io.FileDescriptor" ->
                        (fields.named("fd")?.asInt ?: -1) != -1 || (fields.named("handle")?.asLong ?: -1L) != -1L
                    else -> fields.named("closed")?.asBoolean?.not()
                } ?: return@let
                acc.known = true
                if (open) acc.open++
            }
        }
    }

    fun result(): OffHeapReport {
        checkOk()
        return OffHeapReport(
            DirectStat(owners, ownerBytes, views, viewBytes, mapped, mappedBytes, viewOwners.size.toLong()),
            if (chunks > 0) NettyStat(chunks, allocated, allocated - free) else null,
            resources.filterValues { it.count > 0 }.map { (k, a) -> ResourceStat(k, a.count, if (a.known) a.open else null) },
        )
    }
}
