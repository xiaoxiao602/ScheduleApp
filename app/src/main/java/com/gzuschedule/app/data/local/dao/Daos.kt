package com.gzuschedule.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.gzuschedule.app.data.local.entity.CourseEntity
import com.gzuschedule.app.data.local.entity.ExamEntity
import com.gzuschedule.app.data.local.entity.GradeEntity
import com.gzuschedule.app.data.local.entity.MetaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CourseDao {

    @Query("SELECT * FROM courses WHERE term = :term ORDER BY dayOfWeek, startPeriod")
    fun observeByTerm(term: String): Flow<List<CourseEntity>>

    @Query("SELECT * FROM courses ORDER BY dayOfWeek, startPeriod")
    fun observeAll(): Flow<List<CourseEntity>>

    @Query("SELECT DISTINCT term FROM courses ORDER BY term DESC")
    fun observeTerms(): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM courses")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<CourseEntity>)

    @Query("DELETE FROM courses WHERE term = :term")
    suspend fun deleteByTerm(term: String)

    /** 原子替换某学期课表：先清后插，避免出现半新半旧的状态。 */
    @Transaction
    suspend fun replaceTerm(term: String, items: List<CourseEntity>) {
        deleteByTerm(term)
        insertAll(items)
    }
}

@Dao
interface ExamDao {

    @Query("SELECT * FROM exams ORDER BY date ASC, startTime ASC")
    fun observeAll(): Flow<List<ExamEntity>>

    @Query("SELECT COUNT(*) FROM exams")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<ExamEntity>)

    @Query("DELETE FROM exams")
    suspend fun clear()

    @Transaction
    suspend fun replaceAll(items: List<ExamEntity>) {
        clear()
        insertAll(items)
    }
}

@Dao
interface GradeDao {

    @Query("SELECT * FROM grades ORDER BY term DESC, courseName ASC")
    fun observeAll(): Flow<List<GradeEntity>>

    @Query("SELECT DISTINCT term FROM grades ORDER BY term DESC")
    fun observeTerms(): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM grades")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<GradeEntity>)

    @Query("DELETE FROM grades")
    suspend fun clear()

    @Transaction
    suspend fun replaceAll(items: List<GradeEntity>) {
        clear()
        insertAll(items)
    }
}

@Dao
interface MetaDao {

    @Query("SELECT value FROM meta WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Query("SELECT value FROM meta WHERE `key` = :key")
    fun observe(key: String): Flow<String?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(item: MetaEntity)

    @Query("DELETE FROM meta WHERE `key` = :key")
    suspend fun remove(key: String)
}
