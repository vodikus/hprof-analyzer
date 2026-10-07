package hprof

import shark.HprofHeader
import shark.HprofRecordTag
import shark.HprofRecordTag.CLASS_DUMP
import shark.HprofRecordTag.INSTANCE_DUMP
import shark.HprofRecordTag.OBJECT_ARRAY_DUMP
import shark.HprofRecordTag.PRIMITIVE_ARRAY_DUMP
import shark.StreamingHprofReader
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.util.EnumSet

/** Copy of a truncated dump cut back to its last complete record; [kept] bytes of the original survived. */
class RepairedDump(val file: File, val kept: Long)

private const val RECORD_HEADER = 9 // u1 tag, u4 time, u4 length

/**
 * Null when [file] has no record running past its end. Otherwise (a JVM killed mid-dump, a partial copy) writes a
 * temporary copy Shark can read: everything up to the last complete heap object, the cut heap dump segment's length
 * patched to match, and a HEAP_DUMP_END record.
 *
 * ponytail: the copy doubles disk use and finding the cut costs one extra streaming pass; a virtual
 * DualSourceProvider overlaying the patch on the original would avoid both.
 */
internal fun repairTruncated(file: File): RepairedDump? {
    val size = file.length()
    val header = HprofHeader.parseHeaderOf(file)
    var cut = -1L
    var segment = -1L
    RandomAccessFile(file, "r").use { raf ->
        // fast path: a complete dump ends with HEAP_DUMP_END (empty body)
        if (size >= header.recordsPosition + RECORD_HEADER) {
            raf.seek(size - RECORD_HEADER)
            if (raf.readUnsignedByte() == HprofRecordTag.HEAP_DUMP_END.tag && raf.readInt().let { raf.readInt() } == 0) return null
        }
        var pos = header.recordsPosition.toLong()
        while (pos < size) {
            if (pos + RECORD_HEADER > size) { cut = pos; break }
            raf.seek(pos)
            val tag = raf.readUnsignedByte()
            raf.skipBytes(4)
            val end = pos + RECORD_HEADER + (raf.readInt().toLong() and 0xFFFFFFFFL)
            if (end > size) {
                cut = pos
                if (tag == HprofRecordTag.HEAP_DUMP_SEGMENT.tag || tag == HprofRecordTag.HEAP_DUMP.tag) segment = pos
                break
            }
            pos = end
        }
    }
    if (cut < 0) return null // only the optional HEAP_DUMP_END is missing

    if (segment >= 0) {
        // Shark knows every sub-record layout: let it skip objects until it runs out of bytes
        cut = segment + RECORD_HEADER
        try {
            StreamingHprofReader.readerFor(file, header).readRecords(
                EnumSet.of(CLASS_DUMP, INSTANCE_DUMP, OBJECT_ARRAY_DUMP, PRIMITIVE_ARRAY_DUMP),
            ) { tag, _, reader ->
                when (tag) {
                    CLASS_DUMP -> reader.skipClassDumpRecord()
                    INSTANCE_DUMP -> reader.skipInstanceDumpRecord()
                    OBJECT_ARRAY_DUMP -> reader.skipObjectArrayDumpRecord()
                    else -> reader.skipPrimitiveArrayDumpRecord()
                }
                if (reader.bytesRead > cut) cut = reader.bytesRead
            }
        } catch (_: EOFException) {
        }
    }

    val out = File.createTempFile(file.nameWithoutExtension, ".hprof")
    FileChannel.open(file.toPath(), READ).use { src ->
        FileChannel.open(out.toPath(), WRITE).use { dst ->
            var p = 0L
            while (p < cut) p += src.transferTo(p, cut - p, dst)
            if (segment >= 0) dst.write(ByteBuffer.allocate(4).putInt((cut - segment - RECORD_HEADER).toInt()).flip(), segment + 5)
            dst.write(ByteBuffer.wrap(byteArrayOf(HprofRecordTag.HEAP_DUMP_END.tag.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)), cut)
        }
    }
    return RepairedDump(out, cut)
}
