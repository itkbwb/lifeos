package com.lifeos.app.ui

/**
 * Depth-first, collapse-aware flattening of any tree shape into a flat
 * (node, depth) list - shared by [SubtasksScreen]'s live `parent_id`-flat-list
 * tree and [ProjectImportReviewDialog]'s locally-parsed nested-list tree,
 * which have genuinely different underlying shapes (grouped-by-parent-id vs.
 * already-nested `children`) but identical depth-tracking/collapse-skipping
 * logic - the part most likely to have a subtle off-by-one bug if
 * reimplemented twice, so it lives here once instead. Siblings must already
 * be in the desired render order in [roots]/[childrenOf]'s output; this
 * function does not sort.
 */
fun <T> buildVisibleRowsGeneric(
    roots: List<T>,
    childrenOf: (T) -> List<T>,
    idOf: (T) -> Int,
    collapsedIds: Set<Int>,
): List<Pair<T, Int>> {
    val result = mutableListOf<Pair<T, Int>>()
    fun walk(nodes: List<T>, depth: Int) {
        for (node in nodes) {
            result.add(node to depth)
            val children = childrenOf(node)
            if (children.isNotEmpty() && idOf(node) !in collapsedIds) {
                walk(children, depth + 1)
            }
        }
    }
    walk(roots, 0)
    return result
}
