package com.gzuschedule.app.domain.repository

import com.gzuschedule.app.domain.model.Course
import com.gzuschedule.app.domain.model.Exam
import com.gzuschedule.app.domain.model.Grade
import kotlinx.coroutines.flow.Flow

/**
 * 本地数据端口 —— App 显示数据只依赖这些接口。
 *
 * 数据来自本地数据库（ADR-005：账密用完即弃，本地库是唯一数据源）。
 * 网络同步由 SyncRepository 负责，与读取分离。
 */

interface ScheduleRepository {
    /** 观察指定学期的课表。 */
    fun observeCourses(term: String): Flow<List<Course>>

    /** 观察所有学期。 */
    fun observeTerms(): Flow<List<String>>

    /** 覆盖保存某学期课表。 */
    suspend fun saveCourses(term: String, courses: List<Course>)

    /** 课表条目数（用于判断是否为空状态）。 */
    suspend fun courseCount(): Int
}

interface GradeRepository {
    fun observeGrades(): Flow<List<Grade>>
    fun observeTerms(): Flow<List<String>>
    suspend fun saveGrades(grades: List<Grade>)
    suspend fun gradeCount(): Int
}

interface ExamRepository {
    fun observeExams(): Flow<List<Exam>>
    suspend fun saveExams(exams: List<Exam>)
    suspend fun examCount(): Int
}
