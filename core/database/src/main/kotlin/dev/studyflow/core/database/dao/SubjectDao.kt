package dev.studyflow.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.studyflow.core.database.entity.SubjectEntity
import kotlinx.coroutines.flow.Flow

@Dao
public interface SubjectDao {
    @Query("SELECT * FROM subjects ORDER BY archived ASC, name ASC, id ASC")
    public fun observeAll(): Flow<List<SubjectEntity>>

    @Upsert
    public suspend fun upsert(subject: SubjectEntity)

    @Upsert
    public suspend fun upsertAll(subjects: List<SubjectEntity>)

    @Query("SELECT COUNT(*) FROM subjects")
    public suspend fun count(): Int
}
