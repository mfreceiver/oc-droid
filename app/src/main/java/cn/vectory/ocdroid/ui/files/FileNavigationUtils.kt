package cn.vectory.ocdroid.ui.files

import cn.vectory.ocdroid.data.model.FileNode

internal fun resolveRelativePreviewPath(pathToShow: String, sessionDirectory: String?): String {
    val sessionNorm = sessionDirectory?.trimStart('/') ?: ""
    val pathNorm = pathToShow.trimStart('/')
    return if (sessionNorm.isNotEmpty() && (pathNorm == sessionNorm || pathNorm.startsWith("$sessionNorm/"))) {
        pathNorm.removePrefix(sessionNorm).trimStart('/')
    } else {
        pathNorm
    }
}

internal fun buildDirectoryPreviewContent(path: String, tree: List<FileNode>): String {
    return if (tree.isEmpty()) {
        "Directory (empty or path not found): $path"
    } else {
        "Directory:\n" + tree.joinToString("\n") { it.path }
    }
}
