package com.tans.tfiletransporter.net

import android.os.Build
import com.tans.tfiletransporter.utils.*
import kotlinx.coroutines.*
import java.io.IOException
import java.net.*
import java.nio.ByteBuffer
import kotlin.jvm.Throws
import kotlin.math.max
import kotlin.math.min

const val BROADCAST_RECEIVER_PORT = 6666
const val BROADCAST_LISTENER_PORT = 6667

// 1 KB
const val NET_BUFFER_SIZE = 1024

// 4 KB
const val FILE_BUFFER_SIZE = 4 * 1024

fun CoroutineScope.launchBroadcastSender(
        // Unit: milli second
        broadcastDelay: Long = 300,
        broadMessage: String = "${Build.BRAND} ${Build.MODEL}",
        localAddress: InetAddress,
        acceptRequest: suspend (remoteAddress: SocketAddress, remoteDevice: String) -> Boolean): Job = launch {
    val broadcastSender = BroadcastSender(
            broadcastDelay = broadcastDelay,
            broadMessage = broadMessage,
            localAddress = localAddress)
    val broadcastJob = launch (context = Dispatchers.IO) { broadcastSender.startBroadcastSender() }
    val listenerJob = launch (context = Dispatchers.IO) { broadcastSender.startBroadcastListener(acceptRequest) }
    listenerJob.join()
    broadcastJob.cancel()
}


internal class BroadcastSender(
        // Unit: milli second
        val broadcastDelay: Long = 300,
        val broadMessage: String = "${Build.BRAND} ${Build.MODEL}",
        val localAddress: InetAddress) {

    private val broadcastAddress = localAddress.getBroadcastAddress()

    @Throws(IOException::class)
    suspend fun startBroadcastSender() {
        val dc = openDatagramChannel()
        dc.use {
            dc.setOptionSuspend(StandardSocketOptions.SO_BROADCAST, true)
            val dataBuffer = ByteBuffer.allocate(NET_BUFFER_SIZE)
            dataBuffer.put(broadMessage.toByteArray(Charsets.UTF_8).let {
                if (it.size > NET_BUFFER_SIZE) {
                    it.take(NET_BUFFER_SIZE).toByteArray()
                } else {
                    it
                }
            })
            while (true) {
                dataBuffer.flip()
                dc.sendSuspend(dataBuffer, InetSocketAddress(broadcastAddress, BROADCAST_RECEIVER_PORT))
                delay(broadcastDelay)
            }
        }
    }

    /**
     * If accept the connect request, close listener.
     *
     * 4 bytes: Remote Device Info Length.
     * Length bytes: Remote Device Info
     * 1 bytes: 1. 0x00: accept 2. 0x01: deny
     *
     */
    @Throws(IOException::class)
    suspend fun startBroadcastListener(acceptRequest: suspend (remoteAddress: SocketAddress, remoteDevice: String) -> Boolean) {
        val ssc = openAsynchronousServerSocketChannelSuspend()
        ssc.use {
            ssc.setOptionSuspend(StandardSocketOptions.SO_REUSEADDR, true)
            ssc.bindSuspend(InetSocketAddress(localAddress, BROADCAST_LISTENER_PORT), 1)
            while (true) {
                val byteBuffer = ByteBuffer.allocate(NET_BUFFER_SIZE)
                byteBuffer.position(NET_BUFFER_SIZE - 4)
                byteBuffer.limit(NET_BUFFER_SIZE)
                val isAccept = ssc.acceptSuspend().use { clientSsc ->
                    clientSsc.readSuspend(byteBuffer)
                    byteBuffer.position(NET_BUFFER_SIZE - 4)
                    // 1. Get message size
                    val remoteDeviceInfoSize = min(max(byteBuffer.asIntBuffer().get(), 0), NET_BUFFER_SIZE)
                    byteBuffer.position(NET_BUFFER_SIZE - remoteDeviceInfoSize)
                    clientSsc.readSuspend(byteBuffer)
                    byteBuffer.position(NET_BUFFER_SIZE - remoteDeviceInfoSize)

                    // 2. Get remote device info.
                    val remoteInfo = String(byteBuffer.compact().array(), Charsets.UTF_8)

                    // 3. Accept or deny.
                    if (acceptRequest(clientSsc.remoteAddress, remoteInfo)) {
                        byteBuffer.clear()
                        byteBuffer.put(0x00)
                        byteBuffer.flip()
                        clientSsc.writeSuspend(byteBuffer)
                        true
                    } else {
                        byteBuffer.clear()
                        byteBuffer.put(0x01)
                        byteBuffer.flip()
                        clientSsc.writeSuspend(byteBuffer)
                        false
                    }
                }
                if (isAccept) { break }
            }
        }
    }
}

fun CoroutineScope.launchBroadcastReceiver() {

}

internal class BroadcastReceiver {
    suspend fun start() {

    }
}