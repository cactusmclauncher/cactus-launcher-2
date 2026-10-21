package com.cactuslauncher.model

/** A screenshot in the build's "screenshots" folder; [takenText] is "21.10.2026, 12:05". */
data class Screenshot(val fileName: String, val modifiedMillis: Long, val size: Long, val takenText: String)
