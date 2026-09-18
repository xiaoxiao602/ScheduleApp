package com.gzuschedule.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 课程（Room 实体）。
 *
 * ⚠️ ADR-054 性能：加 (term, dayOfWeek, startPeriod) 复合索引。
 *    主查询 `WHERE term = :term ORDER BY dayOfWeek, startPeriod` 正好覆盖它 ——
 *    否则每次读课表都是**全表扫描 + 内存排序**。数据会随学期累积，越用越慢。
 */
@Entity(
    tableName = "courses",
    indices = [Index(value = ["term", "dayOfWeek", "startPeriod"])],
)
data class CourseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val teacher: String,
    val location: String,
    val dayOfWeek: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val startWeek: Int,
    val endWeek: Int,
    val weekParity: Int,
    val credit: String,
    val note: String,
    /** 该条数据所属学期。 */
    val term: String,
)

/**
 * 考试（Room 实体）。
 *
 * ⚠️ ADR-054 性能：date 建索引 —— 查询按 `ORDER BY date, startTime`，
 *    且首页要按日期过滤「未来 30 天」。
 */
@Entity(
    tableName = "exams",
    indices = [Index(value = ["date", "startTime"])],
)
data class ExamEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val courseName: String,
    /** ISO-8601 日期字符串，如 "2026-01-15"。 */
    val date: String?,
    /** "HH:mm" 格式。 */
    val startTime: String?,
    val endTime: String?,
    val location: String,
    val seat: String,
    val format: String,
    val note: String,
    val term: String,
)

/** 成绩（Room 实体）。 */
@Entity(tableName = "grades")
data class GradeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val term: String,
    val courseName: String,
    val score: String,
    val gpa: String,
    val credit: String,
    val category: String,
    val creditGpa: String,
)

/** 元数据（键值对）：存"上次更新时间"、"当前学期"等。 */
@Entity(tableName = "meta")
data class MetaEntity(
    @PrimaryKey val key: String,
    val value: String,
)
