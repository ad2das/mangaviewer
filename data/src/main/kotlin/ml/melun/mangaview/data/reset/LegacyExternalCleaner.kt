package ml.melun.mangaview.data.reset

import java.io.File
import java.io.IOException

class LegacyExternalCleaner(
    private val markerName: String = "title.gson",
) {
    fun deleteMarkedChildren(recordedRoot: File): Int {
        val root = recordedRoot.canonicalFile
        if (!root.isDirectory) return 0
        var deleted = 0
        root.listFiles().orEmpty().forEach { child ->
            val canonicalChild = child.canonicalFile
            if (!canonicalChild.isDirectory || canonicalChild.parentFile != root) return@forEach
            if (!File(canonicalChild, markerName).isFile) return@forEach
            deleteTree(canonicalChild, root)
            deleted += 1
        }
        return deleted
    }

    fun deleteExactChild(parent: File, childName: String): Boolean {
        require(childName.isNotBlank() && '/' !in childName && '\\' !in childName) {
            "Child name must be a single path segment"
        }
        val canonicalParent = parent.canonicalFile
        val target = File(canonicalParent, childName).canonicalFile
        if (target.parentFile != canonicalParent || !target.exists()) return false
        deleteTree(target, canonicalParent)
        return true
    }

    private fun deleteTree(target: File, boundary: File) {
        val canonicalTarget = target.canonicalFile
        require(canonicalTarget != boundary && canonicalTarget.parentFile == boundary) {
            "Deletion target escaped its exact parent"
        }
        deleteDescendants(canonicalTarget, canonicalTarget)
        if (!canonicalTarget.delete() && canonicalTarget.exists()) {
            throw IOException("Unable to delete legacy directory ${canonicalTarget.name}")
        }
    }

    private fun deleteDescendants(current: File, root: File) {
        if (!current.isDirectory) return
        current.listFiles().orEmpty().forEach { child ->
            val canonical = child.canonicalFile
            require(canonical.path.startsWith(root.path + File.separator)) {
                "Legacy directory contains a path that escapes its root"
            }
            deleteDescendants(canonical, root)
            if (!canonical.delete() && canonical.exists()) {
                throw IOException("Unable to delete legacy child ${canonical.name}")
            }
        }
    }
}
