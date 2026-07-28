package cn.vectory.ocdroid.service

/**
 * The reason a streaming teardown is running. Drives the dedicated teardown
 * path inside StreamingLifecycleCoordinator.
 */
enum class TeardownReason { Timeout, UserClose, Disconnect, BootstrapFailure }
