package dev.diff2test.android.changedetector

import dev.diff2test.android.core.ChangeSet
import dev.diff2test.android.core.ChangeSource

data class ScanRequest(
    val baseRef: String = "HEAD~1",
    val headRef: String = "HEAD",
    val source: ChangeSource = ChangeSource.GIT_DIFF,
)

interface ChangeDetector {
    fun scan(request: ScanRequest = ScanRequest()): ChangeSet
}

class GitDiffChangeDetector : ChangeDetector {
    override fun scan(request: ScanRequest): ChangeSet {
        return ChangeSet(
            source = request.source,
            baseRef = request.baseRef,
            headRef = request.headRef,
            summary = "Git diff parsing is not wired yet. This module owns change discovery inputs.",
        )
    }
}

