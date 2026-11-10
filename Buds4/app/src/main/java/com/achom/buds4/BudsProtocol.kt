package com.achom.buds4

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Режимы шумоподавления. code = последний байт payload "01 01 XX". */
enum class AncMode(val code: Int) {
    ANC(0x04),
    OFF(0x01),
    TRANSPARENCY(0x02)
}

/** -1 = значение ещё не пришло. */
data class BudsState(
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val left: Int = -1,
    val right: Int = -1,
    val case: Int = -1,
    val anc: AncMode? = null,
    val deviceName: String = "OnePlus Buds 4",
    val error: String? = null
)

/**
 * Кодирование / декодирование пакетов.
 *
 * ВАЖНО: в спеке задано только cat / op / payload. Обёртка кадра (заголовок, длина, crc)
 * описана ТОЛЬКО здесь, в encode() и extract(). Если у наушников реальный формат кадра
 * другой — правится в этих двух функциях, остальной код не трогается.
 *
 * Кадр: AA | cat | op | len | payload... | crc, где crc = (cat + op + len + sum(payload)) & 0xFF
 */
object BudsProtocol {
    const val RFCOMM_CHANNEL = 15
    const val FRAME_HEADER = 0xAA

    const val CAT_INIT = 0x0F
    const val OP_INIT = 0x04

    const val CAT_AUDIO = 0x04
    const val OP_BATTERY = 0x02
    const val OP_ANC = 0x04

    const val ID_LEFT = 1
    const val ID_RIGHT = 2
    const val ID_CASE = 3

    class Packet(val cat: Int, val op: Int, val payload: ByteArray)

    class Extracted(val packet: Packet?, val consumed: Int)

    data class Battery(val left: Int?, val right: Int?, val case: Int?)

    private fun crc(cat: Int, op: Int, payload: ByteArray): Int {
        var sum = cat + op + payload.size
        for (b in payload) sum += (b.toInt() and 0xFF)
        return sum and 0xFF
    }

    fun encode(cat: Int, op: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(5 + payload.size)
        out[0] = FRAME_HEADER.toByte()
        out[1] = cat.toByte()
        out[2] = op.toByte()
        out[3] = payload.size.toByte()
        payload.copyInto(out, 4)
        out[4 + payload.size] = crc(cat, op, payload).toByte()
        return out
    }

    /** cat=0x0F, op=0x04, payload = 01 */
    fun initPacket(): ByteArray = encode(CAT_INIT, OP_INIT, byteArrayOf(0x01))

    /** cat=0x04, op=0x04, payload = 01 01 XX */
    fun ancPacket(mode: AncMode): ByteArray =
        encode(CAT_AUDIO, OP_ANC, byteArrayOf(0x01, 0x01, mode.code.toByte()))

    /**
     * Достаёт один кадр из начала буфера.
     * consumed == 0 -> не хватает данных, ждём ещё байты.
     * consumed > 0, packet == null -> выкинули мусор, можно вызывать снова.
     */
    fun extract(buf: ByteArray): Extracted {
        if (buf.isEmpty()) return Extracted(null, 0)
        val start = buf.indexOfFirst { (it.toInt() and 0xFF) == FRAME_HEADER }
        if (start < 0) return Extracted(null, buf.size)
        if (start > 0) return Extracted(null, start)
        if (buf.size < 4) return Extracted(null, 0)

        val cat = buf[1].toInt() and 0xFF
        val op = buf[2].toInt() and 0xFF
        val len = buf[3].toInt() and 0xFF
        val total = 5 + len
        if (buf.size < total) return Extracted(null, 0)

        val payload = buf.copyOfRange(4, 4 + len)
        val expected = crc(cat, op, payload)
        val actual = buf[4 + len].toInt() and 0xFF
        if (expected != actual) return Extracted(null, 1) // ложный заголовок, пересинхронизируемся
        return Extracted(Packet(cat, op, payload), total)
    }

    /** cat=0x04, op=0x02, payload: 01 02/03 [id, %] [id, %] ... */
    fun parseBattery(p: Packet): Battery? {
        if (p.cat != CAT_AUDIO || p.op != OP_BATTERY) return null
        if (p.payload.size < 4 || (p.payload[0].toInt() and 0xFF) != 0x01) return null

        var left: Int? = null
        var right: Int? = null
        var case: Int? = null
        var i = 2
        while (i + 1 < p.payload.size) {
            val id = p.payload[i].toInt() and 0xFF
            val value = (p.payload[i + 1].toInt() and 0x7F).coerceIn(0, 100)
            when (id) {
                ID_LEFT -> left = value
                ID_RIGHT -> right = value
                ID_CASE -> case = value
            }
            i += 2
        }
        if (left == null && right == null && case == null) return null
        return Battery(left, right, case)
    }

    /** Ответ/эхо смены режима: cat=0x04, op=0x04, payload 01 01 XX. */
    fun parseAnc(p: Packet): AncMode? {
        if (p.cat != CAT_AUDIO || p.op != OP_ANC || p.payload.size < 3) return null
        if (p.payload[0].toInt() != 0x01 || p.payload[1].toInt() != 0x01) return null
        val code = p.payload[2].toInt() and 0xFF
        return AncMode.values().firstOrNull { it.code == code }
    }
}

/** Одно общее соединение с наушниками на всё приложение. */
object BudsController {
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private val _state = MutableStateFlow(BudsState())
    val state: StateFlow<BudsState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var socket: BluetoothSocket? = null
    private var output: OutputStream? = null
    private val writeLock = Any()

    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
        else emptyArray()

    fun hasPermission(context: Context): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    @SuppressLint("MissingPermission")
    fun isBuds(device: BluetoothDevice): Boolean {
        val names = listOfNotNull(
            runCatching { device.name }.getOrNull(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) runCatching { device.alias }.getOrNull() else null
        )
        return names.any {
            it.contains("OnePlus Buds", ignoreCase = true) || it.contains("Buds 4", ignoreCase = true)
        }
    }

    @SuppressLint("MissingPermission")
    private fun nameOf(device: BluetoothDevice): String {
        val alias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) runCatching { device.alias }.getOrNull() else null
        return alias ?: runCatching { device.name }.getOrNull() ?: "OnePlus Buds 4"
    }

    @SuppressLint("MissingPermission", "DiscouragedPrivateApi")
    private fun openSocket(device: BluetoothDevice): BluetoothSocket {
        return try {
            // Канал 15 напрямую (скрытый API createRfcommSocket(int))
            val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
            m.invoke(device, BudsProtocol.RFCOMM_CHANNEL) as BluetoothSocket
        } catch (e: Exception) {
            // запасной вариант, если рефлексия закрыта (канал выберется по SDP)
            device.createRfcommSocketToServiceRecord(SPP_UUID)
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(context: Context) {
        val app = context.applicationContext
        if (!hasPermission(app)) {
            _state.update { it.copy(error = "Нет разрешения Bluetooth") }
            return
        }
        val cur = _state.value
        if (cur.connected || cur.connecting) return
        _state.update { it.copy(connecting = true, error = null) }

        job = scope.launch {
            var s: BluetoothSocket? = null
            try {
                val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                if (adapter == null || !adapter.isEnabled) error("Bluetooth выключен")
                val device = adapter.bondedDevices.firstOrNull { isBuds(it) }
                    ?: error("Наушники не найдены среди сопряжённых")

                adapter.cancelDiscovery()
                s = openSocket(device)
                s.connect()

                socket = s
                output = s.outputStream
                _state.update {
                    it.copy(connected = true, connecting = false, deviceName = nameOf(device), error = null)
                }
                send(BudsProtocol.initPacket())
                readLoop(s.inputStream)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Ошибка подключения") }
            } finally {
                runCatching { s?.close() }
                socket = null
                output = null
                _state.update { it.copy(connected = false, connecting = false) }
            }
        }
    }

    fun disconnect() {
        job?.cancel()
        runCatching { socket?.close() }
    }

    /** Меняет режим: сразу обновляет UI и шлёт 01 01 XX. */
    fun setAnc(mode: AncMode) {
        if (!_state.value.connected) {
            _state.update { it.copy(error = "Наушники не подключены") }
            return
        }
        _state.update { it.copy(anc = mode) }
        scope.launch { send(BudsProtocol.ancPacket(mode)) }
    }

    private fun send(bytes: ByteArray) {
        try {
            synchronized(writeLock) {
                output?.let {
                    it.write(bytes)
                    it.flush()
                }
            }
        } catch (e: Exception) {
            _state.update { it.copy(error = "Не удалось отправить команду") }
        }
    }

    private fun readLoop(input: InputStream) {
        val buf = ByteArray(512)
        var pending = ByteArray(0)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            pending += buf.copyOf(n)
            while (true) {
                val r = BudsProtocol.extract(pending)
                if (r.consumed == 0) break
                pending = pending.copyOfRange(r.consumed, pending.size)
                r.packet?.let { handle(it) }
            }
        }
    }

    private fun handle(p: BudsProtocol.Packet) {
        BudsProtocol.parseBattery(p)?.let { b ->
            _state.update {
                it.copy(
                    left = b.left ?: it.left,
                    right = b.right ?: it.right,
                    case = b.case ?: it.case
                )
            }
        }
        BudsProtocol.parseAnc(p)?.let { m ->
            _state.update { it.copy(anc = m) }
        }
    }
}
