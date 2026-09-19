package com.gzuschedule.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.gzuschedule.app.data.local.dao.CourseDao
import com.gzuschedule.app.data.local.dao.ExamDao
import com.gzuschedule.app.data.local.dao.GradeDao
import com.gzuschedule.app.data.local.dao.MetaDao
import com.gzuschedule.app.data.local.entity.CourseEntity
import com.gzuschedule.app.data.local.entity.ExamEntity
import com.gzuschedule.app.data.local.entity.GradeEntity
import com.gzuschedule.app.data.local.entity.MetaEntity

/**
 * 本地数据库 —— App 的数据真相来源。
 *
 * 设计意图（ADR-005）：账密用完即弃，因此【本地库是唯一持久化数据】。
 * 打开 App 直接读这里，不需要联网、不需要登录。
 *
 * ⚠️ ADR-054：version 1 → 2。
 *    为 courses / exams 增加了索引（性能优化，见对应实体的注释）。
 *    Room 检测到 schema 变化会要求升版本；当前用
 *    `fallbackToDestructiveMigration()`，即**重建表**——
 *    对课表类 App 可接受：数据源是教务，重新同步即可恢复。
 */
@Database(
    entities = [
        CourseEntity::class,
        ExamEntity::class,
        GradeEntity::class,
        MetaEntity::class,
        DayOverrideEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun courseDao(): CourseDao
    abstract fun examDao(): ExamDao
    abstract fun gradeDao(): GradeDao
    abstract fun metaDao(): MetaDao

    /** ⚠️ ADR-105：临时调课（当日替换/清空）。 */
    abstract fun dayOverrideDao(): DayOverrideDao

    companion object {
        private const val DB_NAME = "gzuschedule.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME,
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }

    /** 元数据键名集中管理。放在类外，便于以 `AppDatabase.MetaKeys` 访问。 */
    object MetaKeys {
        const val LAST_SYNC_AT = "last_sync_at"
        const val CURRENT_TERM = "current_term"
        const val LAST_USERNAME = "last_username"
        /** 学期第一周的星期一（ISO yyyy-MM-dd）。用于精确计算「第 N 周」(ADR-011)。 */
        const val FIRST_MONDAY = "first_monday"
        /** 学生姓名（同步时从正方响应写入，用于「我的」页） */
        const val STUDENT_NAME = "student_name"
        /** 学号（同步时写入） */
        const val STUDENT_NO = "student_no"
        /** 专业（同步时写入） */
        const val STUDENT_MAJOR = "student_major"
        /** 班级（同步时写入） */
        const val STUDENT_CLASS = "student_class"
        /**
         * 自定义显示名（ADR-012）。
         * 非空则优先显示；为空则回落到 STUDENT_NAME（真名）。
         * 与真名「并存」，可随时清除以恢复真名。
         */
        const val DISPLAY_NAME = "display_name"

        /**
         * 学年名称，如 "2026-2027"（ADR-016，来自 xsxx.XNMC）。
         * 用于周课表标题「AY26-27 Term1」。
         */
        const val ACADEMIC_YEAR_NAME = "academic_year_name"

        /**
         * 学期名称，如 "1"（ADR-016，来自 xsxx.XQMMC）。
         * ⚠️ 不是 XQM（那是编码「3」），取错会显示成 Term3。
         */
        const val TERM_NAME = "term_name"

        /**
         * 假日区间（ADR-060），存 [com.gzuschedule.app.domain.HolidayCalendar.toJson]
         * 产出的 JSON 数组。
         *
         * ⚠️ 为什么塞进 meta 而不是新建一张表：
         *    新建表要升 DB 版本，而当前用的是 `fallbackToDestructiveMigration()`
         *    （见 AppDatabase 注释）—— 升版本会**清库**，用户已同步的课表全丢。
         *    假日数据量小（一学期约 20 天）、结构简单、整体读写，
         *    key-value 存储完全够用，且**零 schema 改动、零数据风险**。
         */
        const val HOLIDAYS = "holidays"
    }
}
