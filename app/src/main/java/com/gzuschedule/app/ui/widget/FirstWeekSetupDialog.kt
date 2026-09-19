package com.gzuschedule.app.ui.widget

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.gzuschedule.app.R
import com.gzuschedule.app.data.local.AppDatabase
import com.gzuschedule.app.domain.WeekCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 首次同步后的「设置第一周星期一」引导弹窗（ADR-096）。
 *
 * ⚠️ 用户反馈：
 *    「新增一个用户首次同步课表的时候弹窗提示他选择本学期第一周周一的时间，
 *      我给别人用的时候有人不会用」
 *
 * ⚠️ 为什么必须有这个引导：
 *    教务接口只返回**相对周次**（"1-9周"），**不返回学期起始日**。
 *    没有「第一周星期一」就算不出"今天第几周"，课表页的周次和日期全错。
 *    新用户不知道要去设置页手动设，会以为 App 坏了。
 *
 * ⚠️ 圆角（用户强调「你加弹窗一定要注意这点」）：
 *    布局用 bg_dialog_card（28dp），show() 后调 DialogCorner.applyRounded。
 */
object FirstWeekSetupDialog {

    /**
     * 若尚未设置「第一周星期一」，则弹出引导。
     *
     * @param onDone 设置成功或跳过后回调（用于刷新页面）
     */
    fun showIfNeeded(activity: android.app.Activity, onDone: () -> Unit = {}) {
        val db = AppDatabase.get(activity)

        // ⚠️⚠️ 必须用 Activity 的 lifecycleScope，**不能用裸 CoroutineScope**。
        //    裸协程不随 Activity 销毁而取消 —— 用户停在引导页、Activity 被回收后，
        //    协程恢复执行 dialog.show() 会抛 WindowManager$BadTokenException 崩溃。
        //    （这是用户实测崩溃后的加固，见 ADR-097）
        (activity as? androidx.lifecycle.LifecycleOwner)
            ?.lifecycleScope
            ?.launch {
                val existing = runCatching {
                    kotlinx.coroutines.withContext(Dispatchers.IO) {
                        db.metaDao().get(AppDatabase.MetaKeys.FIRST_MONDAY)
                    }
                }.getOrNull()

                // 已经设过就不再打扰
                if (!existing.isNullOrBlank()) return@launch
                // Activity 可能已经销毁
                if (activity.isFinishing || activity.isDestroyed) return@launch

                show(activity, db, onDone)
            }
    }

    private fun show(
        activity: android.app.Activity,
        db: AppDatabase,
        onDone: () -> Unit,
    ) {
        // ⚠️ 一律用 Activity 做弹窗 context —— Application context 会抛 BadTokenException
        val context: Context = activity
        val root = LayoutInflater.from(context)
            .inflate(R.layout.dialog_first_week_setup, null, false)

        val tvValue = root.findViewById<TextView>(R.id.tvMondayValue)
        val rowPick = root.findViewById<View>(R.id.rowPickMonday)
        val btnConfirm = root.findViewById<View>(R.id.btnConfirmMonday)
        val btnSkip = root.findViewById<View>(R.id.btnSkipMonday)

        // 默认值：本周一（多数情况下开学就在最近，减少用户操作）
        var picked: LocalDate = WeekCalculator.mondayOf(LocalDate.now())

        fun render() {
            tvValue.text = "%s（%s）".format(
                picked.toString(),
                when (picked.dayOfWeek) {
                    DayOfWeek.MONDAY -> "星期一"
                    DayOfWeek.TUESDAY -> "星期二"
                    DayOfWeek.WEDNESDAY -> "星期三"
                    DayOfWeek.THURSDAY -> "星期四"
                    DayOfWeek.FRIDAY -> "星期五"
                    DayOfWeek.SATURDAY -> "星期六"
                    else -> "星期日"
                },
            )
        }
        render()

        val dialog = AlertDialog.Builder(context)
            .setView(root)
            // ⚠️ ADR-097：改为可取消 —— 原来 false 会让返回键无效，
            //    用户被「困」在弹窗里只能点按钮。首次引导是**建议**不是强制。
            .setCancelable(true)
            .create()

        rowPick.setOnClickListener { v ->
            Haptics.click(v)
            DatePickerDialog(
                context,
                { _, y, m, d ->
                    val chosen = LocalDate.of(y, m + 1, d)
                    // ⚠️ 必须归到星期一 —— 复用项目已有的 WeekCalculator.mondayOf()
                    //    （不要手写 DayOfWeek 逻辑，避免与周次算法不一致）
                    picked = WeekCalculator.mondayOf(chosen)
                    render()
                },
                picked.year, picked.monthValue - 1, picked.dayOfMonth,
            ).show()
        }

        btnConfirm.setOnClickListener { v ->
            Haptics.confirm(v)
            val date = picked

            // ⚠️ 用 Activity 的 lifecycleScope（不再用裸 CoroutineScope）
            (activity as? androidx.lifecycle.LifecycleOwner)
                ?.lifecycleScope
                ?.launch {
                    runCatching {
                        kotlinx.coroutines.withContext(Dispatchers.IO) {
                            db.metaDao().put(
                                com.gzuschedule.app.data.local.entity.MetaEntity(
                                    AppDatabase.MetaKeys.FIRST_MONDAY,
                                    date.toString(),
                                ),
                            )
                        }
                    }
                    // 写库后 Activity 可能已销毁
                    if (activity.isFinishing || activity.isDestroyed) return@launch
                    // dialog 可能已被系统回收
                    runCatching { if (dialog.isShowing) dialog.dismiss() }
                    onDone()
                }
        }

        btnSkip.setOnClickListener { v ->
            Haptics.click(v)
            dialog.dismiss()
            onDone()
        }

        // ⚠️ 必须在 show() 之后
        dialog.show()
        DialogCorner.applyRounded(dialog)
    }

    /** 判断某个日期是否是合法的「第一周星期一」。供调试/测试用。 */
    fun isValid(d: LocalDate): Boolean = WeekCalculator.isValidFirstMonday(d)
}
