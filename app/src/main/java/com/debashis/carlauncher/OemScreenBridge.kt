package com.debashis.carlauncher

import android.content.Context
import android.util.Log

/**
 * Switch to the factory/OEM car information screen on compatible head units.
 *
 * Recovered from QCarLauncherPreview's QUtil.open_app() qacc:car_info branch:
 *   McuServiceManager.getInstance()
 *   service.initialize(context, null)
 *   service.RPCGeneralRpcCall(152, byteArrayOf(-94, 1, 0, 0))
 *
 * The vendor SDK is absent on ordinary phones and on many aftermarket firmware
 * variants. Reflection keeps this feature optional, without packaging a proprietary
 * MCU library in the launcher or adding any Google dependencies.
 *
 * IMPORTANT: This command is only known to be used by QCarLauncher's intended
 * head-unit family. It is not a universal Mercedes/Audi CAN bus command.
 */
object OemScreenBridge {
    private const val TAG = "QCarDash-OEM"

    fun open(context: Context): Boolean {
        return try {
            val serviceClass = Class.forName("navi.McuServiceManager")
            val service = serviceClass.getMethod("getInstance").invoke(null)
                ?: return false
            val initialize = serviceClass.methods.firstOrNull {
                it.name == "initialize" &&
                    it.parameterTypes.size == 2 &&
                    Context::class.java.isAssignableFrom(it.parameterTypes[0]) &&
                    !it.parameterTypes[1].isPrimitive
            } ?: return false
            initialize.invoke(service, context.applicationContext, null)
            val rpc = serviceClass.methods.firstOrNull {
                it.name == "RPCGeneralRpcCall" &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[1] == ByteArray::class.java
            } ?: return false
            rpc.invoke(service, 152, byteArrayOf((-94).toByte(), 1, 0, 0))
            true
        } catch (error: Exception) {
            Log.w(TAG, "OEM switch unavailable on this firmware", error)
            false
        } catch (error: LinkageError) {
            Log.w(TAG, "Proprietary MCU SDK not available", error)
            false
        }
    }
}
