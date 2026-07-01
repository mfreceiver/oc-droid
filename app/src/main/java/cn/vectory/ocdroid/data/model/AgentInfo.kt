package cn.vectory.ocdroid.data.model

import kotlinx.serialization.Serializable

@Serializable
data class AgentInfo(
    val name: String,
    val description: String? = null,
    val mode: String? = null,
    val hidden: Boolean? = null,
    val native: Boolean? = null
) {
    val id: String get() = name

    val shortName: String
        get() {
            val parenIndex = name.indexOf("(")
            if (parenIndex > 0) return name.substring(0, parenIndex).trim()
            val spaceIndex = name.indexOf(" ")
            if (spaceIndex > 0) return name.substring(0, spaceIndex)
            return name
        }

    val isVisible: Boolean
        get() {
            if (hidden == true) return false
            if (mode == null) return true
            return mode == "primary" || mode == "all"
        }
}
