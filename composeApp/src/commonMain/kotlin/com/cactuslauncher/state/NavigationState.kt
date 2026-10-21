package com.cactuslauncher.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cactuslauncher.model.Dest
import com.cactuslauncher.model.OtherSection

/** Current section and which top-level dialogs are open. */
class NavigationState {
    var dest by mutableStateOf(Dest.Home)
    var readingNews by mutableStateOf(false)
    var readingBeta by mutableStateOf(false)
    var settingsOpen by mutableStateOf(false)
    var newBuildOpen by mutableStateOf(false)
    /** The open section of "Остальное", or null for its menu. Not remembered between launches. */
    var otherSection by mutableStateOf<OtherSection?>(null)
}
