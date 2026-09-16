package dev.studyflow.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "subjects",
    indices = [Index(value = ["archived", "name", "id"], name = "index_subjects_archived_name")],
)
public data class SubjectEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val colorArgb: Int,
    val archived: Boolean,
)
