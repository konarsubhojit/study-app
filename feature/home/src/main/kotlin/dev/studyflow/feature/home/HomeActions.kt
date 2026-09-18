package dev.studyflow.feature.home

/** Navigation destinations owned by dashboard cards. */
public data class HomeActions(
    val onOpenTimer: () -> Unit = {},
    val onOpenTask: (String) -> Unit = {},
    val onOpenMaterial: (String) -> Unit = {},
    val onOpenHistory: () -> Unit = {},
)
