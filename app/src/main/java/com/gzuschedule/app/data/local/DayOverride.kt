package com.gzuschedule.app.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 临时调课（ADR-105）。
 *
 * ⚠️ 用户需求：
 *    「当日的调课 —— 原本的课表不动，只是有一天突然调了。
 *      比如星期一的课，那我就选择周一，那天的课表就会临时变成周一的课。
 *      适用于周六日调课」
 *    「今天页改变，那一周的课程页看也改变」
 *    「如果周四五突然没课，我也可以在今日课表里选择无，那一天就没课了」
 *
 * 语义：
 *   · [sourceDay] = -1   → **无课**（那天临时清空）
 *   · [sourceDay] = 1..7 → 用该星期几的课表替换（ISO：周一=1 … 周日=7）
 *   · 无记录            → 不调课（原样显示）
 *
 * ⚠️ 只影响 [date] 当天 —— 相邻周次的同一天不受影响。
 */
@Entity(tableName = "day_override")
data class DayOverrideEntity(
    /** 生效日期（ISO yyyy-MM-dd）。主键 —— 一天最多一条调课。 */
    @PrimaryKey val date: String,

    /** 用哪天的课表替换。见类注释：-1 = 无课，1..7 = 周几。 */
    val sourceDay: Int,
)

@Dao
interface DayOverrideDao {

    /** 观察所有调课（供 ViewModel 合并进状态）。 */
    @Query("SELECT * FROM day_override")
    fun observeAll(): Flow<List<DayOverrideEntity>>

    /** 取某天的调课（无则 null）。 */
    @Query("SELECT * FROM day_override WHERE date = :date LIMIT 1")
    suspend fun get(date: String): DayOverrideEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(item: DayOverrideEntity)

    /** 取消某天的调课（恢复原样）。 */
    @Query("DELETE FROM day_override WHERE date = :date")
    suspend fun remove(date: String)

    /** 清空所有调课（用于「重新同步」时重置）。 */
    @Query("DELETE FROM day_override")
    suspend fun clearAll()
}

/** 调课源：一个日期被替换成什么。 */
sealed interface DayOverride {

    /** 该日无调课，按原课表显示。 */
    data object None : DayOverride

    /** 该日临时无课。 */
    data object NoClass : DayOverride

    /** 该日按 [sourceDay]（ISO 1..7）的课表上课。 */
    data class UseDay(val sourceDay: Int) : DayOverride

    companion object {
        /** 从数据库实体转换。 */
        fun from(entity: DayOverrideEntity?): DayOverride = when {
            entity == null -> None
            entity.sourceDay == SOURCE_NONE -> NoClass
            entity.sourceDay in 1..7 -> UseDay(entity.sourceDay)
            else -> None
        }

        /** 「无课」在数据库里的表示。 */
        const val SOURCE_NONE = -1

        /** 星期几的中文名（ISO 1..7）。 */
        fun dayName(iso: Int): String = when (iso) {
            1 -> "周一"
            2 -> "周二"
            3 -> "周三"
            4 -> "周四"
            5 -> "周五"
            6 -> "周六"
            7 -> "周日"
            else -> "未知"
        }
    }
}

// ---------- Room 数据库挂载（在 AppDatabase 里加） ----------

/**
 * ⚠️ 使用说明（供 AppDatabase 集成）：
 *
 * ```kotlin
 * @Database(
 *     entities = [..., DayOverrideEntity::class],
 *     version = N + 1,            // ⚠️ 必须 +1
 *     exportSchema = false,
 * )
 * abstract class AppDatabase : RoomDatabase() {
 *     abstract fun dayOverrideDao(): DayOverrideDao
 * }
 * ```
 *
 * ⚠️ 版本升级：新增表必须改 version，否则运行时报
 *    "Room cannot verify the data integrity"。
 *    ⚠️ 当前项目**没有 Migration**，用的是 fallbackToDestructiveMigration
 *       （见 AppDatabase 构建处）—— 升级会**清空本地数据**。
 *       这次改动会清掉已有课表缓存，用户需重新同步一次。
 */
object DayOverrideSchemaNotes
