package com.cactuslauncher.model

/** Lines appended to a build log since the last read. [reset] means the file was truncated or skipped ahead. */
data class LogChunk(val lines: List<String>, val nextOffset: Long, val reset: Boolean)
