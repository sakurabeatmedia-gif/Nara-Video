package com.example.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

@UnstableApi
class CencMp4DataSource(
    private val okHttpClient: OkHttpClient,
    private val keyBytes: ByteArray,
    private val sharedIndexLock: Any,
    private val indexHolder: IndexHolder
) : BaseDataSource(/* isNetwork = */ true) {

    class IndexHolder {
        @Volatile
        var index: ParsedCencMp4Index? = null
    }

    class Factory(
        private val okHttpClient: OkHttpClient,
        keyHex: String
    ) : DataSource.Factory {
        private val keyBytes = hexToBytes(keyHex)
        private val lock = Any()
        private val holder = IndexHolder()

        override fun createDataSource(): DataSource {
            return CencMp4DataSource(
                okHttpClient = okHttpClient,
                keyBytes = keyBytes,
                sharedIndexLock = lock,
                indexHolder = holder
            )
        }
    }

    private var dataSpec: DataSpec? = null
    private var currentUri: Uri? = null
    private var activeResponse: Response? = null
    private var activeStream: InputStream? = null
    private var currentPosition: Long = 0L
    private var bytesRemaining: Long = 0L
    private var opened: Boolean = false
    private var servingFromMoovOnly: Boolean = false

    private val aesCipher: Cipher by lazy {
        Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"))
        }
    }

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        this.currentUri = dataSpec.uri
        transferInitializing(dataSpec)

        val url = dataSpec.uri.toString()
        val index = ensureIndex(url)

        val totalLen = index.totalFileLength
        val startPos = dataSpec.position
        if (totalLen > 0 && startPos >= totalLen) {
            throw IOException("Requested position $startPos >= file length $totalLen")
        }

        val resolvedLength = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> {
                if (totalLen > 0) minOf(dataSpec.length, totalLen - startPos) else dataSpec.length
            }
            totalLen > 0 -> totalLen - startPos
            else -> C.LENGTH_UNSET.toLong()
        }

        currentPosition = startPos
        bytesRemaining = resolvedLength

        val moovStart = index.moovOffset
        val moovEnd = index.moovOffset + index.patchedMoov.size
        if (resolvedLength > 0 && startPos >= moovStart && (startPos + resolvedLength) <= moovEnd) {
            servingFromMoovOnly = true
        } else {
            servingFromMoovOnly = false
            openHttpStream(url, startPos, resolvedLength)
        }

        opened = true
        transferStarted(dataSpec)
        return resolvedLength
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val index = indexHolder.index ?: throw IOException("CENC index missing")
        val maxRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(length.toLong(), bytesRemaining).toInt()
        }

        val bytesRead: Int
        if (servingFromMoovOnly) {
            val moovRel = (currentPosition - index.moovOffset).toInt()
            val avail = index.patchedMoov.size - moovRel
            if (avail <= 0) return C.RESULT_END_OF_INPUT
            bytesRead = minOf(maxRead, avail)
            System.arraycopy(index.patchedMoov, moovRel, buffer, offset, bytesRead)
        } else {
            val stream = activeStream ?: return C.RESULT_END_OF_INPUT
            bytesRead = stream.read(buffer, offset, maxRead)
            if (bytesRead == -1) {
                return C.RESULT_END_OF_INPUT
            }
            // Apply patched moov bytes for any overlap with [moovStart, moovEnd)
            applyMoovOverlay(index, currentPosition, buffer, offset, bytesRead)
            // Decrypt any CENC sample bytes in [currentPosition, currentPosition + bytesRead)
            decryptBufferRange(index, currentPosition, buffer, offset, bytesRead)
        }

        currentPosition += bytesRead
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            bytesRemaining -= bytesRead
        }
        bytesTransferred(bytesRead)
        return bytesRead
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        currentUri = null
        closeHttpStream()
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    private fun openHttpStream(url: String, startPos: Long, length: Long) {
        closeHttpStream()
        val rangeHeader = if (length > 0 && length != C.LENGTH_UNSET.toLong()) {
            "bytes=$startPos-${startPos + length - 1}"
        } else {
            "bytes=$startPos-"
        }
        val req = Request.Builder()
            .url(url)
            .header("Range", rangeHeader)
            .header("Accept", "*/*")
            .get()
            .build()

        val resp = okHttpClient.newCall(req).execute()
        if (!resp.isSuccessful && resp.code != 206) {
            val code = resp.code
            resp.close()
            throw IOException("HTTP error $code saat membaca aliran media terenkripsi")
        }
        val stream = resp.body?.byteStream() ?: throw IOException("Response body media kosong")
        if (resp.code == 200 && startPos > 0) {
            var remainingToSkip = startPos
            val discardBuf = ByteArray(16384)
            while (remainingToSkip > 0) {
                val n = stream.read(discardBuf, 0, minOf(discardBuf.size.toLong(), remainingToSkip).toInt())
                if (n <= 0) break
                remainingToSkip -= n
            }
        }
        activeResponse = resp
        activeStream = stream
    }

    private fun closeHttpStream() {
        runCatching { activeStream?.close() }
        runCatching { activeResponse?.close() }
        activeStream = null
        activeResponse = null
    }

    private fun ensureIndex(url: String): ParsedCencMp4Index {
        indexHolder.index?.let { return it }
        synchronized(sharedIndexLock) {
            indexHolder.index?.let { return it }
            val parsed = buildCencIndex(okHttpClient, url)
            indexHolder.index = parsed
            return parsed
        }
    }

    private fun applyMoovOverlay(
        index: ParsedCencMp4Index,
        readStart: Long,
        buffer: ByteArray,
        bufOffset: Int,
        len: Int
    ) {
        val moovStart = index.moovOffset
        val moovEnd = moovStart + index.patchedMoov.size
        val readEnd = readStart + len
        if (readEnd <= moovStart || readStart >= moovEnd) return

        val overlapStart = maxOf(readStart, moovStart)
        val overlapEnd = minOf(readEnd, moovEnd)
        val count = (overlapEnd - overlapStart).toInt()
        if (count <= 0) return

        val srcPos = (overlapStart - moovStart).toInt()
        val dstPos = bufOffset + (overlapStart - readStart).toInt()
        System.arraycopy(index.patchedMoov, srcPos, buffer, dstPos, count)
    }

    private fun decryptBufferRange(
        index: ParsedCencMp4Index,
        readStart: Long,
        buffer: ByteArray,
        bufOffset: Int,
        len: Int
    ) {
        val spans = index.encryptedSpans
        if (spans.isEmpty() || len <= 0) return
        val readEnd = readStart + len

        // Binary search for first span with fileEnd > readStart
        var lo = 0
        var hi = spans.size - 1
        var firstIdx = spans.size
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (spans[mid].fileEnd > readStart) {
                firstIdx = mid
                hi = mid - 1
            } else {
                lo = mid + 1
            }
        }

        if (firstIdx >= spans.size) return

        val counter = ByteArray(16)
        var cachedKeystream = ByteArray(16)
        var cachedSpanIdx = -1
        var cachedBlockIdx = -1L

        var idx = firstIdx
        while (idx < spans.size) {
            val span = spans[idx]
            if (span.fileStart >= readEnd) break

            val ovStart = maxOf(readStart, span.fileStart)
            val ovEnd = minOf(readEnd, span.fileEnd)
            var filePos = ovStart

            while (filePos < ovEnd) {
                val encRel = (filePos - span.fileStart) + span.encBaseOffset
                val blockIdx = encRel ushr 4
                val byteInBlock = (encRel and 0x0FL).toInt()

                if (cachedSpanIdx != idx || cachedBlockIdx != blockIdx) {
                    buildCounterBlock(span.iv16, blockIdx, counter)
                    cachedKeystream = aesCipher.doFinal(counter)
                    cachedSpanIdx = idx
                    cachedBlockIdx = blockIdx
                }

                val remainingInBlock = 16 - byteInBlock
                val remainingInOverlap = (ovEnd - filePos).toInt()
                val chunk = minOf(remainingInBlock, remainingInOverlap)

                val dstBase = bufOffset + (filePos - readStart).toInt()
                for (j in 0 until chunk) {
                    buffer[dstBase + j] = (buffer[dstBase + j].toInt() xor cachedKeystream[byteInBlock + j].toInt()).toByte()
                }
                filePos += chunk
            }
            idx++
        }
    }

    companion object {
        fun hexToBytes(hex: String): ByteArray {
            val clean = hex.trim()
            if (clean.length % 2 != 0 || clean.isEmpty()) return ByteArray(0)
            return ByteArray(clean.length / 2) { i ->
                clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }

        private fun buildCounterBlock(iv16: ByteArray, blockIndex: Long, out: ByteArray) {
            System.arraycopy(iv16, 0, out, 0, 16)
            if (blockIndex == 0L) return
            // Add blockIndex (unsigned 64-bit) to bytes [8..15] with carry to [0..7]
            var carry = blockIndex
            for (i in 15 downTo 0) {
                val sum = (out[i].toInt() and 0xFF).toLong() + (carry and 0xFFL)
                out[i] = sum.toByte()
                carry = (carry ushr 8) + (sum ushr 8)
                if (carry == 0L) break
            }
        }

        internal fun buildCencIndex(okHttpClient: OkHttpClient, url: String): ParsedCencMp4Index {
            // Read initial 64KB to walk top-level boxes & capture moov if faststart
            val firstResp = fetchExactRange(okHttpClient, url, 0L, 65535L)
            val totalLength = firstResp.totalFileLength
            val initialBytes = firstResp.bytes

            var cursor = 0L
            var moovOffset = -1L
            var moovSize = -1L

            while (cursor + 8 <= (if (totalLength > 0) totalLength else Long.MAX_VALUE)) {
                val hdr = if (cursor + 16 <= initialBytes.size) {
                    initialBytes.copyOfRange(cursor.toInt(), cursor.toInt() + 16)
                } else {
                    val fetched = fetchExactRange(okHttpClient, url, cursor, cursor + 15L)
                    if (fetched.bytes.size < 8) break
                    fetched.bytes
                }

                val size32 = readUInt32BE(hdr, 0)
                val boxType = String(hdr, 4, 4, StandardCharsets.US_ASCII)
                var boxSize = size32
                if (size32 == 1L && hdr.size >= 16) {
                    boxSize = readInt64BE(hdr, 8)
                } else if (size32 == 0L && totalLength > 0) {
                    boxSize = totalLength - cursor
                }

                if (boxSize < 8L) break

                if (boxType == "moov") {
                    moovOffset = cursor
                    moovSize = boxSize
                    break
                }
                cursor += boxSize
            }

            if (moovOffset < 0L || moovSize <= 8L || moovSize > 16L * 1024L * 1024L) {
                throw IOException("Kotak atom moov tidak ditemukan atau ukurannya tidak valid")
            }

            val rawMoov: ByteArray = if (moovOffset + moovSize <= initialBytes.size) {
                initialBytes.copyOfRange(moovOffset.toInt(), (moovOffset + moovSize).toInt())
            } else {
                fetchExactRange(okHttpClient, url, moovOffset, moovOffset + moovSize - 1L).bytes
            }

            return parseAndPatchMoov(rawMoov, moovOffset, totalLength)
        }

        internal fun parseAndPatchMoov(
            rawMoov: ByteArray,
            moovOffset: Long,
            totalFileLength: Long
        ): ParsedCencMp4Index {
            val patched = rawMoov.copyOf()
            val allSpans = mutableListOf<EncryptedSpan>()

            val moovHdrSize = boxHeaderSize(patched, 0)
            walkChildren(patched, moovHdrSize, patched.size) { boxStart, boxEnd, boxType ->
                if (boxType == "trak") {
                    parseAndPatchTrak(patched, boxStart, boxEnd, allSpans)
                }
            }

            allSpans.sortBy { it.fileStart }
            return ParsedCencMp4Index(
                moovOffset = moovOffset,
                patchedMoov = patched,
                totalFileLength = totalFileLength,
                encryptedSpans = allSpans
            )
        }

        private fun parseAndPatchTrak(
            moov: ByteArray,
            trakStart: Int,
            trakEnd: Int,
            outSpans: MutableList<EncryptedSpan>
        ) {
            val trakHdr = boxHeaderSize(moov, trakStart)
            walkChildren(moov, trakStart + trakHdr, trakEnd) { bStart, bEnd, bType ->
                if (bType == "mdia") {
                    val mdiaHdr = boxHeaderSize(moov, bStart)
                    walkChildren(moov, bStart + mdiaHdr, bEnd) { mStart, mEnd, mType ->
                        if (mType == "minf") {
                            val minfHdr = boxHeaderSize(moov, mStart)
                            walkChildren(moov, mStart + minfHdr, mEnd) { miStart, miEnd, miType ->
                                if (miType == "stbl") {
                                    parseAndPatchStbl(moov, miStart, miEnd, outSpans)
                                }
                            }
                        }
                    }
                }
            }
        }

        private class StscEntry(val firstChunk: Int, val samplesPerChunk: Int)
        private class SubsampleSpan(val clearLen: Int, val encLen: Int)
        private class SencSample(val iv16: ByteArray, val subsamples: List<SubsampleSpan>)

        private fun parseAndPatchStbl(
            moov: ByteArray,
            stblStart: Int,
            stblEnd: Int,
            outSpans: MutableList<EncryptedSpan>
        ) {
            var defaultIvSize = 8
            val stscEntries = mutableListOf<StscEntry>()
            val sampleSizes = mutableListOf<Int>()
            val chunkOffsets = mutableListOf<Long>()
            var sencOffset = -1
            var sencEnd = -1

            val stblHdr = boxHeaderSize(moov, stblStart)
            walkChildren(moov, stblStart + stblHdr, stblEnd) { boxStart, boxEnd, boxType ->
                val hdrSize = boxHeaderSize(moov, boxStart)
                val payloadStart = boxStart + hdrSize
                when (boxType) {
                    "stsd" -> {
                        if (payloadStart + 8 <= boxEnd) {
                            val entryCount = readInt32BE(moov, payloadStart + 4)
                            var cur = payloadStart + 8
                            for (e in 0 until entryCount) {
                                if (cur + 8 > boxEnd) break
                                val eSize = readUInt32BE(moov, cur).toInt()
                                if (eSize < 8 || cur + eSize > boxEnd) break
                                val eType = String(moov, cur + 4, 4, StandardCharsets.US_ASCII)
                                if (eType == "encv" || eType == "enca") {
                                    val entryEnd = cur + eSize
                                    val skipBytes = if (eType == "encv") 78 else 28
                                    val childStart = cur + 8 + skipBytes
                                    var origFormat: ByteArray? = null
                                    if (childStart < entryEnd) {
                                        walkChildren(moov, childStart, entryEnd) { cStart, cEnd, cType ->
                                            if (cType == "sinf") {
                                                val sinfHdr = boxHeaderSize(moov, cStart)
                                                walkChildren(moov, cStart + sinfHdr, cEnd) { sStart, sEnd, sType ->
                                                    when (sType) {
                                                        "frma" -> {
                                                            val fHdr = boxHeaderSize(moov, sStart)
                                                            if (sStart + fHdr + 4 <= sEnd) {
                                                                origFormat = moov.copyOfRange(sStart + fHdr, sStart + fHdr + 4)
                                                            }
                                                        }
                                                        "schi" -> {
                                                            val schiHdr = boxHeaderSize(moov, sStart)
                                                            walkChildren(moov, sStart + schiHdr, sEnd) { tStart, tEnd, tType ->
                                                                if (tType == "tenc") {
                                                                    val tHdr = boxHeaderSize(moov, tStart)
                                                                    // tenc fullbox: 4B ver/flags, 2B reserved/crypt-skip, 1B isEncrypted, 1B ivSize
                                                                    if (tStart + tHdr + 8 <= tEnd) {
                                                                        val parsedIvSize = moov[tStart + tHdr + 7].toInt() and 0xFF
                                                                        if (parsedIvSize == 8 || parsedIvSize == 16) {
                                                                            defaultIvSize = parsedIvSize
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                                // Neutralize sinf -> free
                                                overwriteFourCc(moov, cStart + 4, "free")
                                            }
                                        }
                                    }
                                    if (origFormat != null) {
                                        System.arraycopy(origFormat!!, 0, moov, cur + 4, 4)
                                    }
                                }
                                cur += eSize
                            }
                        }
                    }

                    "stsc" -> {
                        if (payloadStart + 8 <= boxEnd) {
                            val count = readInt32BE(moov, payloadStart + 4)
                            var p = payloadStart + 8
                            for (i in 0 until count) {
                                if (p + 12 > boxEnd) break
                                val fc = readInt32BE(moov, p)
                                val spc = readInt32BE(moov, p + 4)
                                stscEntries.add(StscEntry(fc, spc))
                                p += 12
                            }
                        }
                    }

                    "stsz" -> {
                        if (payloadStart + 12 <= boxEnd) {
                            val constSize = readInt32BE(moov, payloadStart + 4)
                            val count = readInt32BE(moov, payloadStart + 8)
                            if (constSize > 0) {
                                repeat(count) { sampleSizes.add(constSize) }
                            } else {
                                var p = payloadStart + 12
                                for (i in 0 until count) {
                                    if (p + 4 > boxEnd) break
                                    sampleSizes.add(readInt32BE(moov, p))
                                    p += 4
                                }
                            }
                        }
                    }

                    "stco" -> {
                        if (payloadStart + 8 <= boxEnd) {
                            val count = readInt32BE(moov, payloadStart + 4)
                            var p = payloadStart + 8
                            for (i in 0 until count) {
                                if (p + 4 > boxEnd) break
                                chunkOffsets.add(readUInt32BE(moov, p))
                                p += 4
                            }
                        }
                    }

                    "co64" -> {
                        if (payloadStart + 8 <= boxEnd) {
                            val count = readInt32BE(moov, payloadStart + 4)
                            var p = payloadStart + 8
                            for (i in 0 until count) {
                                if (p + 8 > boxEnd) break
                                chunkOffsets.add(readInt64BE(moov, p))
                                p += 8
                            }
                        }
                    }

                    "senc" -> {
                        sencOffset = payloadStart
                        sencEnd = boxEnd
                        overwriteFourCc(moov, boxStart + 4, "free")
                    }

                    "saio", "saiz" -> {
                        overwriteFourCc(moov, boxStart + 4, "free")
                    }
                }
            }

            if (sencOffset < 0 || chunkOffsets.isEmpty() || sampleSizes.isEmpty() || stscEntries.isEmpty()) {
                return
            }

            val sencSamples = parseSencBox(moov, sencOffset, sencEnd, defaultIvSize)
            if (sencSamples.isEmpty()) return

            // Map each sample to its physical byte range in mdat using stco/co64 + stsc + stsz
            var sampleIdx = 0
            for (chunkIdx in chunkOffsets.indices) {
                val chunkNum1Based = chunkIdx + 1
                var samplesPerChunk = stscEntries[0].samplesPerChunk
                for (entry in stscEntries) {
                    if (entry.firstChunk <= chunkNum1Based) {
                        samplesPerChunk = entry.samplesPerChunk
                    } else {
                        break
                    }
                }

                var pos = chunkOffsets[chunkIdx]
                for (s in 0 until samplesPerChunk) {
                    if (sampleIdx >= sampleSizes.size) break
                    val sampleLen = sampleSizes[sampleIdx]
                    if (sampleIdx < sencSamples.size && sampleLen > 0) {
                        val senc = sencSamples[sampleIdx]
                        if (senc.subsamples.isEmpty()) {
                            outSpans.add(
                                EncryptedSpan(
                                    fileStart = pos,
                                    fileEnd = pos + sampleLen,
                                    iv16 = senc.iv16,
                                    encBaseOffset = 0L
                                )
                            )
                        } else {
                            var subPos = pos
                            var encByteAcc = 0L
                            for (sub in senc.subsamples) {
                                subPos += sub.clearLen
                                if (sub.encLen > 0) {
                                    outSpans.add(
                                        EncryptedSpan(
                                            fileStart = subPos,
                                            fileEnd = subPos + sub.encLen,
                                            iv16 = senc.iv16,
                                            encBaseOffset = encByteAcc
                                        )
                                    )
                                    subPos += sub.encLen
                                    encByteAcc += sub.encLen
                                }
                            }
                        }
                    }
                    pos += sampleLen
                    sampleIdx++
                }
            }
        }

        private fun parseSencBox(
            moov: ByteArray,
            payloadStart: Int,
            boxEnd: Int,
            defaultIvSize: Int
        ): List<SencSample> {
            if (payloadStart + 8 > boxEnd) return emptyList()
            val flags = ((moov[payloadStart + 1].toInt() and 0xFF) shl 16) or
                ((moov[payloadStart + 2].toInt() and 0xFF) shl 8) or
                (moov[payloadStart + 3].toInt() and 0xFF)
            val useSubsamples = (flags and 0x02) != 0
            val sampleCount = readInt32BE(moov, payloadStart + 4)
            if (sampleCount <= 0) return emptyList()

            var ivSize = defaultIvSize
            val remainingDataBytes = boxEnd - (payloadStart + 8)
            if (!useSubsamples) {
                ivSize = when (remainingDataBytes) {
                    sampleCount * 8 -> 8
                    sampleCount * 16 -> 16
                    else -> defaultIvSize
                }
            }

            val result = ArrayList<SencSample>(sampleCount)
            var p = payloadStart + 8
            for (i in 0 until sampleCount) {
                if (p + ivSize > boxEnd) break
                val iv16 = ByteArray(16)
                System.arraycopy(moov, p, iv16, 0, ivSize)
                p += ivSize

                val subsamples = if (useSubsamples) {
                    if (p + 2 > boxEnd) break
                    val subCount = ((moov[p].toInt() and 0xFF) shl 8) or (moov[p + 1].toInt() and 0xFF)
                    p += 2
                    val list = ArrayList<SubsampleSpan>(subCount)
                    for (j in 0 until subCount) {
                        if (p + 6 > boxEnd) break
                        val clearLen = ((moov[p].toInt() and 0xFF) shl 8) or (moov[p + 1].toInt() and 0xFF)
                        val encLen = readInt32BE(moov, p + 2)
                        p += 6
                        list.add(SubsampleSpan(clearLen, encLen))
                    }
                    list
                } else {
                    emptyList()
                }
                result.add(SencSample(iv16, subsamples))
            }
            return result
        }

        private inline fun walkChildren(
            buf: ByteArray,
            start: Int,
            end: Int,
            onChild: (boxStart: Int, boxEnd: Int, boxType: String) -> Unit
        ) {
            var pos = start
            while (pos + 8 <= end) {
                val size32 = readUInt32BE(buf, pos)
                val type = String(buf, pos + 4, 4, StandardCharsets.US_ASCII)
                val boxSize = when {
                    size32 == 1L && pos + 16 <= end -> readInt64BE(buf, pos + 8)
                    size32 == 0L -> (end - pos).toLong()
                    else -> size32
                }
                if (boxSize < 8L || pos + boxSize > end) break
                val boxEnd = (pos + boxSize).toInt()
                onChild(pos, boxEnd, type)
                pos = boxEnd
            }
        }

        private fun boxHeaderSize(buf: ByteArray, pos: Int): Int {
            if (pos + 8 > buf.size) return 8
            val size32 = readUInt32BE(buf, pos)
            return if (size32 == 1L) 16 else 8
        }

        private fun overwriteFourCc(buf: ByteArray, pos: Int, tag: String) {
            if (pos + 4 <= buf.size) {
                buf[pos] = tag[0].code.toByte()
                buf[pos + 1] = tag[1].code.toByte()
                buf[pos + 2] = tag[2].code.toByte()
                buf[pos + 3] = tag[3].code.toByte()
            }
        }

        private fun readUInt32BE(buf: ByteArray, pos: Int): Long {
            return ((buf[pos].toLong() and 0xFFL) shl 24) or
                ((buf[pos + 1].toLong() and 0xFFL) shl 16) or
                ((buf[pos + 2].toLong() and 0xFFL) shl 8) or
                (buf[pos + 3].toLong() and 0xFFL)
        }

        private fun readInt32BE(buf: ByteArray, pos: Int): Int = readUInt32BE(buf, pos).toInt()

        private fun readInt64BE(buf: ByteArray, pos: Int): Long {
            var v = 0L
            for (i in 0 until 8) {
                v = (v shl 8) or (buf[pos + i].toLong() and 0xFFL)
            }
            return v
        }

        private data class ExactRangeResult(
            val totalFileLength: Long,
            val bytes: ByteArray
        )

        private fun fetchExactRange(
            okHttpClient: OkHttpClient,
            url: String,
            start: Long,
            endInclusive: Long
        ): ExactRangeResult {
            val req = Request.Builder()
                .url(url)
                .header("Range", "bytes=$start-$endInclusive")
                .header("Accept", "*/*")
                .get()
                .build()

            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 206) {
                    throw IOException("HTTP ${resp.code} saat membaca metadata MP4")
                }
                val contentRange = resp.header("Content-Range")
                val totalFromRange = contentRange?.substringAfterLast("/")?.toLongOrNull() ?: -1L
                val totalLen = when {
                    totalFromRange > 0 -> totalFromRange
                    resp.code == 200 -> resp.header("Content-Length")?.toLongOrNull() ?: -1L
                    else -> -1L
                }
                val maxRead = (endInclusive - start + 1).toInt().coerceAtMost(16 * 1024 * 1024)
                val stream = resp.body?.byteStream() ?: throw IOException("Response body kosong")
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16384)
                var readTotal = 0
                while (readTotal < maxRead) {
                    val n = stream.read(buf, 0, minOf(buf.size, maxRead - readTotal))
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    readTotal += n
                }
                return ExactRangeResult(totalLen, out.toByteArray())
            }
        }
    }
}

data class EncryptedSpan(
    val fileStart: Long,
    val fileEnd: Long,
    val iv16: ByteArray,
    val encBaseOffset: Long
)

data class ParsedCencMp4Index(
    val moovOffset: Long,
    val patchedMoov: ByteArray,
    val totalFileLength: Long,
    val encryptedSpans: List<EncryptedSpan>
)
