package com.cactuslauncher.model

/**
 * A compatibility problem of the installed jar [fileName]; [error] problems stop the game from starting.
 * [installId] is the Modrinth slug of a missing dependency, [otherFile] the other jar of a duplicate or conflict.
 */
data class ModProblem(
    val fileName: String,
    val modName: String,
    val error: Boolean,
    val message: String,
    val installId: String? = null,
    val otherFile: String? = null,
)
