package com.easytier.jni

fun interface ConfigServerEventCallback {
    fun onEvent(eventJson: String)
}

/** EasyTier JNI 接口类，包名与 .so 编译时保持一致 */
object EasyTierJNI {

    init {
        System.loadLibrary("easytier_android_jni")
    }

    @JvmStatic external fun setTunFd(instanceName: String, fd: Int): Int
    @JvmStatic external fun parseConfig(config: String): Int
    @JvmStatic external fun runNetworkInstance(config: String): Int
    @JvmStatic
    external fun startConfigServerClient(
        url: String,
        hostname: String?,
        machineId: String,
        secureMode: Boolean,
        callback: ConfigServerEventCallback?
    ): Int
    @JvmStatic external fun stopConfigServerClient(): Int
    @JvmStatic external fun isConfigServerClientConnected(): Boolean
    @JvmStatic external fun retainNetworkInstance(instanceNames: Array<String>?): Int
    @JvmStatic external fun collectNetworkInfos(maxLength: Int): String?
    @JvmStatic external fun listInstances(maxLength: Int): String?
    @JvmStatic external fun callJsonRpc(
        serviceName: String,
        methodName: String,
        domainName: String?,
        payloadJson: String
    ): String?
    @JvmStatic external fun getLastError(): String?

    @JvmStatic
    fun stopAllInstances(): Int = retainNetworkInstance(null)

    @JvmStatic
    fun retainSingleInstance(instanceName: String): Int = retainNetworkInstance(arrayOf(instanceName))
}
