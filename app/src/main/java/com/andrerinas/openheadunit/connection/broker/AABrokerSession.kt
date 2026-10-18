package com.andrerinas.openheadunit.connection.broker

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.andrerinas.openheadunit.utils.AppLog

/**
 * One BLE session from this head unit to the AABroker.
 * Wireless credentials are pushed only after a device has been chosen in settings.
 */
object AABrokerSession {
    enum class Phase { IDLE, CONNECTING, READY, FAILED, INCOMPLETE, PAIRING }

    data class Status(val phase: Phase, val detail: String = "")

    private val main = Handler(Looper.getMainLooper())
    private var client: AABrokerClient? = null
    private var appContext: Context? = null
    private var lastSnapshot: String? = null
    private var lastInfo: AABrokerProtocol.DisplayInfo? = null
    private var status = Status(Phase.IDLE)
    private var listener: ((Status) -> Unit)? = null
    private var retryUntilMs = 0L
    private var retryPosted = false
    private var fromRetry = false
    private var gaveUp = false
    private var projectionUp = false
    /** When the last AA_ENABLE was handed to the broker. The next waits out delivery to the phone. */
    private var lastDialAtMs = 0L
    /** BLE is down and the retry window has not expired. The displayed phase stays as it was. */
    private var awaitingBroker = false

    /** Runs once when the broker stays down for the whole retry window. */
    var onGiveUp: (() -> Unit)? = null

    private val retry = Runnable {
        retryPosted = false
        val app = appContext ?: return@Runnable
        if (!awaitingBroker && (status.phase == Phase.READY || status.phase == Phase.PAIRING)) return@Runnable
        if (SystemClock.elapsedRealtime() >= retryUntilMs) {
            giveUp()
            return@Runnable
        }
        val known = lastInfo
        fromRetry = true
        try {
            if (known != null && known.isComplete) {
                offer(app, known, force = true)
            } else {
                offerStart(app)
            }
        } finally {
            fromRetry = false
        }
        scheduleRetry()
    }

    fun current(): Status = status

    fun setListener(onStatus: ((Status) -> Unit)?) {
        listener = onStatus
        onStatus?.invoke(status)
    }

    fun offer(context: Context, info: AABrokerProtocol.DisplayInfo, force: Boolean = false) {
        val app = context.applicationContext
        val address = AABrokerStore(app).deviceAddress
        if (address == null) {
            publish(Status(Phase.IDLE))
            return
        }
        if (!info.isComplete) {
            if (status.phase != Phase.READY && status.phase != Phase.CONNECTING) {
                publish(Status(Phase.INCOMPLETE))
            }
            return
        }
        val dial = !projectionUp && dialDelivered()
        if (!projectionUp && !dial && !awaitingBroker &&
            (status.phase == Phase.READY || status.phase == Phase.CONNECTING)
        ) {
            AppLog.i("Broker: previous start is still on the way to the phone")
            return
        }
        lastInfo = info
        val snapshot = listOf(address, info.ssid, info.bssid, info.ipAddress, info.port.toString()).joinToString("|")
        if (snapshot == lastSnapshot && status.phase == Phase.CONNECTING) return
        if (!force && snapshot == lastSnapshot && status.phase == Phase.READY) return
        lastSnapshot = snapshot
        appContext = app
        if (!fromRetry) gaveUp = false
        publish(Status(Phase.CONNECTING))
        if (dial) lastDialAtMs = SystemClock.elapsedRealtime()
        AppLog.i("Broker: sending Wi-Fi snapshot ${info.ssid} ${info.ipAddress}:${info.port} to $address" +
            if (dial) "" else " without a new start")
        brokerClient(app).prepare(
            address,
            info.copy(generation = System.currentTimeMillis() / 1000L),
            restartProjection = dial,
            confirmProjection = projectionUp,
        ) { result ->
            if (result.isSuccess) {
                awaitingBroker = false
                stopRetry()
                publish(Status(Phase.READY, address))
            } else {
                lastSnapshot = null
                if (!noteLinkLost()) {
                    publish(Status(Phase.FAILED, result.exceptionOrNull()?.message ?: ""))
                }
            }
        }
    }

    /** Ask the broker to answer a phone's Classic inquiry for 120 seconds. */
    fun openPairing(context: Context) {
        val app = context.applicationContext
        val address = AABrokerStore(app).deviceAddress
        if (address == null) {
            publish(Status(Phase.IDLE))
            return
        }
        appContext = app
        publish(Status(Phase.CONNECTING))
        AppLog.i("Broker: opening phone pairing window on $address")
        brokerClient(app).allowPhonePairing(address) { result ->
            if (result.isSuccess) {
                publish(Status(Phase.PAIRING, address))
            } else {
                publish(Status(Phase.FAILED, result.exceptionOrNull()?.message ?: ""))
            }
        }
    }

    /**
     * The phone is being waited on or woken. Send the last complete display snapshot again so the
     * broker can start Android Auto with the address and network that are on screen now.
     */
    fun offerStart(context: Context) {
        val app = context.applicationContext
        if (AABrokerStore(app).deviceAddress == null) return
        val known = lastInfo
        if (known != null) {
            offer(app, known, force = true)
            return
        }
        AppLog.i("Broker: phone wait/wake has no snapshot yet, reading the live group")
        offerLiveGroup(app, force = true)
    }

    /** The phone opened or closed the projection socket. While it stays up, repeat the notice so the broker can drop the state when these stop. */
    fun notifyProjection(up: Boolean) {
        val changed = projectionUp != up
        projectionUp = up
        main.removeCallbacks(projectionHeartbeat)
        if (changed) {
            AppLog.i("Broker: projection socket ${if (up) "up" else "down"}")
        }
        client?.notifyProjection(up)
        if (up) {
            main.postDelayed(projectionHeartbeat, PROJECTION_HEARTBEAT_MS)
        }
    }

    private val projectionHeartbeat: Runnable = Runnable {
        if (!projectionUp) return@Runnable
        client?.notifyProjection(true)
        main.postDelayed(projectionHeartbeat, PROJECTION_HEARTBEAT_MS)
    }

    /** Read the live Wi-Fi Direct group and send it when a broker is saved. */
    fun offerLiveGroup(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        if (AABrokerStore(app).deviceAddress == null) return
        WifiDirectDisplayInfo.read(app) { info -> offer(app, info, force) }
    }

    fun close() {
        stopRetry()
        gaveUp = false
        awaitingBroker = false
        client?.close()
        client = null
        projectionUp = false
        main.removeCallbacks(projectionHeartbeat)
        lastDialAtMs = 0L
        lastSnapshot = null
        lastInfo = null
        publish(Status(Phase.IDLE))
    }

    private fun brokerClient(app: Context): AABrokerClient {
        val active = client ?: AABrokerClient(app).also { client = it }
        active.onLinkLost = { noteLinkLost() }
        return active
    }

    /** @return true when the loss is inside the quiet retry window */
    private fun noteLinkLost(): Boolean {
        if (gaveUp) return false
        val app = appContext ?: return false
        if (AABrokerStore(app).deviceAddress == null) return false
        awaitingBroker = true
        val now = SystemClock.elapsedRealtime()
        if (retryUntilMs != 0L && now >= retryUntilMs) {
            giveUp()
            return true
        }
        if (retryUntilMs == 0L) {
            retryUntilMs = now + RETRY_WINDOW_MS
            AppLog.w("Broker: link lost, retrying for ${RETRY_WINDOW_MS / 1000}s")
        }
        scheduleRetry()
        return true
    }

    private fun scheduleRetry() {
        if (retryPosted || retryUntilMs == 0L) return
        retryPosted = true
        main.postDelayed(retry, RETRY_EVERY_MS)
    }

    private fun stopRetry() {
        retryUntilMs = 0L
        retryPosted = false
        main.removeCallbacks(retry)
    }

    private fun giveUp() {
        if (gaveUp) return
        gaveUp = true
        awaitingBroker = false
        stopRetry()
        AppLog.w("Broker: still down after ${RETRY_WINDOW_MS / 1000}s, stopping the phone session")
        onGiveUp?.invoke()
    }

    private const val RETRY_WINDOW_MS = 10_000L
    private const val RETRY_EVERY_MS = 2_000L
    /** Shorter than the broker's silence limit, so one missed write does not look like the socket closed. */
    private const val PROJECTION_HEARTBEAT_MS = 2_000L

    /**
     * One BLE setup is several writes. A second start in that burst is the same request.
     * The Wi-Fi group often comes up about a second after the first snapshot, and that later
     * snapshot is the one the phone must receive, so this stays under that gap.
     */
    private const val START_DELIVERY_MS = 1_000L

    private fun dialDelivered(now: Long = SystemClock.elapsedRealtime()): Boolean =
        lastDialAtMs == 0L || now - lastDialAtMs >= START_DELIVERY_MS

    private fun publish(next: Status) {
        status = next
        main.post { listener?.invoke(next) }
    }
}
