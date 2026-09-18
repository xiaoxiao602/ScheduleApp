package com.gzuschedule.app.data.local

import com.gzuschedule.app.data.local.entity.MetaEntity
import com.gzuschedule.app.domain.model.Course
import com.gzuschedule.app.domain.model.Exam
import com.gzuschedule.app.domain.model.Grade
import com.gzuschedule.app.domain.repository.ExamRepository
import com.gzuschedule.app.domain.repository.GradeRepository
import com.gzuschedule.app.domain.repository.ScheduleRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** ScheduleRepository 的 Room 实现。 */
class LocalScheduleRepository(
    private val db: AppDatabase,
) : ScheduleRepository {

    private val dao = db.courseDao()

    override fun observeCourses(term: String): Flow<List<Course>> =
        dao.observeByTerm(term).map { list -> list.map { it.toDomain() } }

    override fun observeTerms(): Flow<List<String>> = dao.observeTerms()

    override suspend fun saveCourses(term: String, courses: List<Course>) {
        dao.replaceTerm(term, courses.map { it.toEntity(term) })
        db.metaDao().put(MetaEntity(AppDatabase.MetaKeys.CURRENT_TERM, term))
    }

    override suspend fun courseCount(): Int = dao.count()
}

/** GradeRepository 的 Room 实现。 */
class LocalGradeRepository(
    private val db: AppDatabase,
) : GradeRepository {

    private val dao = db.gradeDao()

    override fun observeGrades(): Flow<List<Grade>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeTerms(): Flow<List<String>> = dao.observeTerms()

    override suspend fun saveGrades(grades: List<Grade>) {
        dao.replaceAll(grades.map { it.toEntity() })
    }

    override suspend fun gradeCount(): Int = dao.count()
}

/** ExamRepository 的 Room 实现。 */
class LocalExamRepository(
    private val db: AppDatabase,
) : ExamRepository {

    private val dao = db.examDao()

    override fun observeExams(): Flow<List<Exam>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun saveExams(exams: List<Exam>) {
        dao.replaceAll(exams.map { it.toEntity("") })
    }

    override suspend fun examCount(): Int = dao.count()
}
