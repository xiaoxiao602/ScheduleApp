package com.gzuschedule.app.data.local

import com.gzuschedule.app.data.local.entity.CourseEntity
import com.gzuschedule.app.data.local.entity.ExamEntity
import com.gzuschedule.app.data.local.entity.GradeEntity
import com.gzuschedule.app.domain.model.Course
import com.gzuschedule.app.domain.model.Exam
import com.gzuschedule.app.domain.model.Grade
import java.time.LocalDate
import java.time.LocalTime

/** 实体 <-> 领域模型 的转换。 */

fun CourseEntity.toDomain(): Course = Course(
    id = id,
    name = name,
    teacher = teacher,
    location = location,
    dayOfWeek = dayOfWeek,
    startPeriod = startPeriod,
    endPeriod = endPeriod,
    startWeek = startWeek,
    endWeek = endWeek,
    weekParity = weekParity,
    credit = credit,
    note = note,
)

fun Course.toEntity(term: String): CourseEntity = CourseEntity(
    id = id,
    name = name,
    teacher = teacher,
    location = location,
    dayOfWeek = dayOfWeek,
    startPeriod = startPeriod,
    endPeriod = endPeriod,
    startWeek = startWeek,
    endWeek = endWeek,
    weekParity = weekParity,
    credit = credit,
    note = note,
    term = term,
)

fun ExamEntity.toDomain(): Exam = Exam(
    id = id,
    courseName = courseName,
    date = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
    startTime = startTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() },
    endTime = endTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() },
    location = location,
    seat = seat,
    format = format,
    note = note,
)

fun Exam.toEntity(term: String): ExamEntity = ExamEntity(
    id = id,
    courseName = courseName,
    date = date?.toString(),
    startTime = startTime?.toString(),
    endTime = endTime?.toString(),
    location = location,
    seat = seat,
    format = format,
    note = note,
    term = term,
)

fun GradeEntity.toDomain(): Grade = Grade(
    id = id,
    term = term,
    courseName = courseName,
    score = score,
    gpa = gpa,
    credit = credit,
    category = category,
    creditGpa = creditGpa,
)

fun Grade.toEntity(): GradeEntity = GradeEntity(
    id = id,
    term = term,
    courseName = courseName,
    score = score,
    gpa = gpa,
    credit = credit,
    category = category,
    creditGpa = creditGpa,
)
