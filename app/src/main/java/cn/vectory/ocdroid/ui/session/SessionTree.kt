package cn.vectory.ocdroid.ui.session

import cn.vectory.ocdroid.data.model.Session

data class SessionNode(
    val session: Session,
    val children: List<SessionNode>
)

fun buildSessionTree(sessions: List<Session>): List<SessionNode> {
    val sessionIds = sessions.map { it.id }.toSet()
    val childrenMap = sessions.groupBy { it.parentId }
    fun buildNodes(parentId: String?): List<SessionNode> =
        (childrenMap[parentId] ?: emptyList())
            .sortedByDescending { it.time?.updated ?: 0L }
            .map { s -> SessionNode(session = s, children = buildNodes(s.id)) }
    val roots = buildNodes(null)
    val orphans = sessions
        .filter { it.parentId != null && it.parentId !in sessionIds }
        .sortedByDescending { it.time?.updated ?: 0L }
        .map { s -> SessionNode(session = s, children = buildNodes(s.id)) }
    return (roots + orphans).sortedByDescending { it.session.time?.updated ?: 0L }
}

fun flattenVisibleTree(
    nodes: List<SessionNode>,
    expandedIds: Set<String>,
    depth: Int = 0
): List<Pair<SessionNode, Int>> =
    nodes.flatMap { node ->
        listOf(node to depth) + if (expandedIds.contains(node.session.id)) {
            flattenVisibleTree(node.children, expandedIds, depth + 1)
        } else emptyList()
    }
