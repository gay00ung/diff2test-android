package dev.diff2test.android.styleindex

import dev.diff2test.android.core.StyleGuide
import java.nio.file.Files
import java.nio.file.Path

interface StyleIndexer {
    fun index(moduleRoot: Path): StyleGuide
}

class DefaultStyleIndexer : StyleIndexer {
    override fun index(moduleRoot: Path): StyleGuide {
        val hasTestDirectory = Files.exists(moduleRoot.resolve("src/test"))
        return if (hasTestDirectory) {
            StyleGuide(
                mockLibrary = "project-default",
                assertionStyle = "project-default",
                coroutineEntryPoint = "runTest",
                flowProbe = "project-default",
                namingPattern = "project-default",
            )
        } else {
            StyleGuide()
        }
    }
}

