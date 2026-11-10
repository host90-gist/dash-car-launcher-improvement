package com.carocean.navicar

import java.io.Serializable

/**
 * Minimal read-only serialization schema matching the Yecon/Carocean firmware's
 * Navi.Status.SystemParamInfo type. The original class has many more fields;
 * Java serialization ignores fields that are not present locally.
 *
 * The serial version UID and class name were identified in the user-supplied
 * stock launcher. This is only for reading the stock provider's data.
 */
class Navi {
    class Status {
        class SystemParamInfo : Serializable {
            @JvmField var mcu_version: String? = null
            @JvmField var system_version: String? = null

            companion object {
                private const val serialVersionUID: Long = 355951474935201023L
            }
        }
    }
}
