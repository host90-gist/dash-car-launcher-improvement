package com.debashis.carlauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log

/**
 * Minimal read-only bridge to the Carocean MCU service.
 *
 * Protocol copied from the user's original QC Launcher APK:
 * - service action: com.carocean.mcuservice
 * - package: com.carocean.mcuserver
 * - callback registration message: 256, bundle key "cmdcode"
 * - callback reply: 258, bundle keys "cmdcode" and "data"
 * - code 24: byte[2] = remaining fuel liters, byte[3..4] = RPM
 *
 * We intentionally expose only fields whose byte layout is confirmed by that APK.
 * Code 36 remains unknown and is not decoded here.
 */
class McuVehicleBridge(
    context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onFuelLiters(liters: Int)
        fun onRpm(rpm: Int) {}
        fun onDisconnected() {}
    }

    private val appContext = context.applicationContext
    private var serviceMessenger: Messenger? = null
    private var bound = false

    private val incoming = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what != MSG_REPLY_CALLBACK) {
                super.handleMessage(msg)
                return
            }
            val data = msg.data ?: return
            val code = data.getInt(KEY_CMD_CODE, -1)
            val bytes = data.getByteArray(KEY_DATA) ?: return
            if (code == 24 && bytes.size >= 5) {
                val liters = bytes[2].toInt() and 0xff
                val rpm = ((bytes[3].toInt() and 0xff) shl 8) or
                    (bytes[4].toInt() and 0xff)
                listener.onFuelLiters(liters)
                listener.onRpm(rpm)
            }
        }
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null) return
            serviceMessenger = Messenger(binder)
            bound = true
            registerCallbacks()
            // These are the two initialization requests issued by QC Launcher
            // after connecting to the same MCU service.
            sendGeneralRpc(152, byteArrayOf(7, 4, 0, 0))
            Handler(Looper.getMainLooper()).postDelayed({
                if (bound) sendGeneralRpc(152, byteArrayOf((-107).toByte(), 4, 0, 0))
            }, 200L)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceMessenger = null
            bound = false
            listener.onDisconnected()
        }
    }

    fun start() {
        if (bound) return
        val intent = Intent(ACTION_MCU_SERVICE).setPackage(MCU_PACKAGE)
        try {
            if (!appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                Log.w(TAG, "Carocean MCU service not available")
                listener.onDisconnected()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to bind Carocean MCU service", e)
            listener.onDisconnected()
        }
    }

    fun stop() {
        if (!bound) return
        try {
            sendRegistration(false)
            appContext.unbindService(connection)
        } catch (e: Exception) {
            Log.d(TAG, "MCU unbind failed", e)
        } finally {
            bound = false
            serviceMessenger = null
        }
    }

    private fun registerCallbacks() = sendRegistration(true)

    private fun sendRegistration(register: Boolean) {
        val message = Message.obtain(null, if (register) MSG_REGISTER else MSG_UNREGISTER)
        message.data = Bundle().apply {
            putIntArray(KEY_CMD_CODE, intArrayOf(18, 24, 36))
        }
        message.replyTo = incoming
        send(message)
    }

    private fun sendGeneralRpc(rpc: Int, payload: ByteArray) {
        val message = Message.obtain(null, MSG_GENERAL_RPC)
        message.arg1 = rpc
        message.data = Bundle().apply {
            putByteArray(KEY_GENERAL_RPC_DATA, payload)
        }
        send(message)
    }

    private fun send(message: Message) {
        try {
            serviceMessenger?.send(message)
        } catch (e: Exception) {
            Log.w(TAG, "MCU message failed: " + message.what, e)
        }
    }

    companion object {
        private const val TAG = "QCarMcuVehicle"
        private const val ACTION_MCU_SERVICE = "com.carocean.mcuservice"
        private const val MCU_PACKAGE = "com.carocean.mcuserver"
        private const val MSG_GENERAL_RPC = 99
        private const val MSG_REGISTER = 256
        private const val MSG_UNREGISTER = 257
        private const val MSG_REPLY_CALLBACK = 258
        private const val KEY_CMD_CODE = "cmdcode"
        private const val KEY_DATA = "data"
        private const val KEY_GENERAL_RPC_DATA = "g_rpc_call_data"
    }
}
