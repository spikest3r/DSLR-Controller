package com.spikest3r.cameracontrol

import android.hardware.usb.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

private val LE = ByteOrder.LITTLE_ENDIAN

class PtpResponse(val code: Int, val data: ByteArray) { val ok get() = code == 0x2001 }
class PtpException(val code: Int, msg: String) : Exception(msg)
class PropDesc(val type: Int, val rw: Boolean, val cur: Long, val allowed: List<Long>)

class PtpCamera private constructor(
    private val conn: UsbDeviceConnection,
    private val intf: UsbInterface,
    private val out: UsbEndpoint,
    private val inn: UsbEndpoint,
    private val evt: UsbEndpoint?
) : AutoCloseable {

    private var tid = 0

    companion object {
        fun open(usb: UsbManager, dev: UsbDevice): PtpCamera {
            val intf = (0 until dev.interfaceCount).map { dev.getInterface(it) }
                .first { it.interfaceClass == 6 }
            val conn = usb.openDevice(dev) ?: error("openDevice failed (no permission?)")
            check(conn.claimInterface(intf, true)) { "claim failed" }   // force = steal from MTP
            var o: UsbEndpoint? = null; var i: UsbEndpoint? = null; var e: UsbEndpoint? = null
            for (k in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(k)
                when {
                    ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_OUT -> o = ep
                    ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_IN -> i = ep
                    ep.type == UsbConstants.USB_ENDPOINT_XFER_INT -> e = ep
                }
            }
            return PtpCamera(conn, intf, o!!, i!!, e).also { it.cmd(0x1002, 1) }
        }
    }

    private fun container(type: Int, op: Int, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(12 + payload.size).order(LE)
            .putInt(12 + payload.size).putShort(type.toShort()).putShort(op.toShort()).putInt(tid)
            .put(payload).array()

    private fun write(b: ByteArray) {
        if (conn.bulkTransfer(out, b, b.size, 5000) != b.size) throw PtpException(-1, "write failed")
    }

    private fun readContainer(): Triple<Int, Int, ByteArray> {
        val chunk = ByteArray(16384)
        val bos = ByteArrayOutputStream()
        var total = -1
        while (total < 0 || bos.size() < total) {
            val n = conn.bulkTransfer(inn, chunk, chunk.size, 5000)
            if (n <= 0) throw PtpException(-1, "read failed ($n)")
            if (total < 0) total = ByteBuffer.wrap(chunk).order(LE).int
            bos.write(chunk, 0, n)
        }
        val bb = ByteBuffer.wrap(bos.toByteArray()).order(LE)
        val type = bb.getShort(4).toInt() and 0xFFFF
        val code = bb.getShort(6).toInt() and 0xFFFF
        return Triple(type, code, bb.array().copyOfRange(12, total))
    }

    /** Raw transaction. Returns whatever response code the camera gave. */
    @Synchronized
    fun transact(op: Int, vararg p: Int, data: ByteArray? = null): PtpResponse {
        tid++
        val params = ByteBuffer.allocate(p.size * 4).order(LE).also { b -> p.forEach { b.putInt(it) } }.array()
        write(container(1, op, params))
        if (data != null) write(container(2, op, data))
        var payload = ByteArray(0)
        while (true) {
            val (type, code, pl) = readContainer()
            if (type == 2) payload = pl else if (type == 3) return PtpResponse(code, payload)
        }
    }

    /** Retries on busy (0x2019), throws on any non-OK code. */
    fun cmd(op: Int, vararg p: Int, data: ByteArray? = null): PtpResponse {
        repeat(40) {
            val r = transact(op, *p, data = data)
            if (r.code != 0x2019) {
                if (!r.ok) throw PtpException(r.code, "op %04x -> %04x".format(op, r.code))
                return r
            }
            Thread.sleep(50)
        }
        throw PtpException(0x2019, "busy timeout")
    }

    fun drainEvents() {
        val e = evt ?: return
        val b = ByteArray(64)
        while (conn.bulkTransfer(e, b, b.size, 20) > 0) { }
    }

    /** Waits for one of [codes] on the interrupt endpoint. Null on timeout / no endpoint. */
    fun waitEvent(codes: Set<Int>, timeoutMs: Long = 10_000): Int? {
        val e = evt ?: return null
        val end = System.currentTimeMillis() + timeoutMs
        val b = ByteArray(64)
        while (System.currentTimeMillis() < end) {
            val n = conn.bulkTransfer(e, b, b.size, 300)
            if (n >= 12) {
                val bb = ByteBuffer.wrap(b).order(LE)
                val type = bb.getShort(4).toInt() and 0xFFFF
                val code = bb.getShort(6).toInt() and 0xFFFF
                if (type == 4 && code in codes) return code
            }
        }
        return null
    }

    private fun sizeOf(t: Int) = when (t) { 1, 2 -> 1; 3, 4 -> 2; 5, 6 -> 4; else -> error("type $t") }
    private fun readVal(b: ByteBuffer, t: Int): Long = when (t) {
        1 -> b.get().toLong()
        2 -> b.get().toLong() and 0xFF
        3 -> b.short.toLong()
        4 -> b.short.toLong() and 0xFFFF
        5 -> b.int.toLong()
        6 -> b.int.toLong() and 0xFFFFFFFFL
        else -> error("type $t")
    }

    fun getDesc(prop: Int): PropDesc {
        val b = ByteBuffer.wrap(cmd(0x1014, prop).data).order(LE)
        b.position(2); val type = b.short.toInt() and 0xFFFF
        val rw = b.get().toInt() != 0
        readVal(b, type)
        val cur = readVal(b, type)
        val form = b.get().toInt()
        val allowed = when (form) {
            2 -> List(b.short.toInt() and 0xFFFF) { readVal(b, type) }
            1 -> List(3) { readVal(b, type) }
            else -> emptyList()
        }
        return PropDesc(type, rw, cur, allowed)
    }

    fun getProp(prop: Int): Long = getDesc(prop).cur

    fun setProp(prop: Int, v: Long) {
        val sz = sizeOf(getDesc(prop).type)
        val d = ByteBuffer.allocate(sz).order(LE)
        when (sz) { 1 -> d.put(v.toByte()); 2 -> d.putShort(v.toShort()); else -> d.putInt(v.toInt()) }
        cmd(0x1016, prop, data = d.array())
    }

    /** Capture, then wait for CaptureComplete/ObjectAdded (falls back to a sleep if no event arrives). */
    fun shoot() {
        drainEvents()
        cmd(0x100E, 0, 0)
        if (waitEvent(setOf(0x400D, 0x4002)) == null) Thread.sleep(1500)
    }
    fun afDrive() { cmd(0x90C1) }

    override fun close() {
        runCatching { transact(0x1003) }
        conn.releaseInterface(intf)
        conn.close()
    }
}

class Info(val ops: List<Int>, val events: List<Int>, val props: List<Int>)

fun PtpCamera.deviceInfo(): Info {
    val b = ByteBuffer.wrap(cmd(0x1001).data).order(LE)
    b.position(8)                                              // std ver, vendor ext id, ext ver
    fun skipStr() { val n = b.get().toInt() and 0xFF; b.position(b.position() + n * 2) }
    fun arr() = List(b.int) { b.short.toInt() and 0xFFFF }
    skipStr(); b.short                                         // ext desc, functional mode
    return Info(arr(), arr(), arr())
}

fun PtpCamera.objectHandles(): List<Int> {
    val b = ByteBuffer.wrap(cmd(0x1007, -1, 0, 0).data).order(LE)   // all storages, all formats, any parent
    return List(b.int) { b.int }
}