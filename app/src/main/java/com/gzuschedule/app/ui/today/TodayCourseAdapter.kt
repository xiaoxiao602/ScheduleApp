package com.gzuschedule.app.ui.today

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.gzuschedule.app.databinding.ItemCourseBinding
import com.gzuschedule.app.domain.CourseStatus
import com.gzuschedule.app.domain.PeriodTime
import com.gzuschedule.app.domain.model.Course
import com.gzuschedule.app.ui.widget.CourseDetailDialog
import com.gzuschedule.app.ui.widget.CourseProgressBinder
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 今日课程适配器（ADR-049 拆到独立文件）。
 *
 * ⚠️ 为什么要单独一个文件：
 *   原来它与 [TodayFragment] 同在 TodayFragment.kt。kapt 为每个 .kt 生成 Java stub
 *   时，同文件的第二个顶层类会与文件门面类冲突，报
 *     `错误: 类重复: com.gzuschedule.app.ui.today.TodayFragment`
 *   把适配器移出即可根治。
 */
class TodayCourseAdapter :
    RecyclerView.Adapter<TodayCourseAdapter.VH>() {

    private val items = mutableListOf<Course>()

    fun submit(list: List<Course>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(val binding: ItemCourseBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemCourseBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    /**
     * ⚠️ ADR-043：新增 payload 重载。
     *
     * 旧版只有 (holder, position) 一个签名，`notifyItemRangeChanged(..., PAYLOAD_PROGRESS)`
     * 传的 payload 无人接收 → 每次刷新都**全量重建 item 视图** →
     * 进度条被重置到 0 再重新动画 = 看着像闪。加了 payload 分支后
     * 只更新进度条本身，视图不重建。
     */
    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.contains(PAYLOAD_PROGRESS)) {
            bindProgress(holder, position)
            bindDoneBadge(holder, position)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = items[position]
        holder.binding.tvName.text = c.name

        // 节次 + 上下课时间（ADR-013）
        holder.binding.tvPeriod.text = PeriodTime.periodsLabel(c.startPeriod, c.endPeriod)
        val time = PeriodTime.range(c.startPeriod, c.endPeriod)
        holder.binding.tvTime.text = time.replace(" - ", "\n")
        holder.binding.tvTime.visibility = if (time.isBlank()) android.view.View.GONE
        else android.view.View.VISIBLE

        // 地点 · 教师
        holder.binding.tvMeta.text = buildList {
            if (c.location.isNotBlank()) add(c.location)
            if (c.teacher.isNotBlank()) add(c.teacher)
        }.joinToString(" · ").ifBlank { "—" }

        // 点击查看课程详情（ADR-017）—— 与周课表共用同一个弹窗
        holder.binding.root.setOnClickListener {
            CourseDetailDialog.show(holder.itemView.context, c)
        }

        // 上课进度条（ADR-031）：仅在进行中显示，随时间推进
        bindProgress(holder, position)

        // 已上完 → 蓝色对勾（ADR-051）
        bindDoneBadge(holder, position)
    }

    /** 只刷新进度条（payload 路径与全量路径共用）。 */
    private fun bindProgress(holder: VH, position: Int) {
        val c = items.getOrNull(position) ?: return
        CourseProgressBinder.bind(
            row = holder.binding.progressRow,
            bar = holder.binding.progressCourse,
            label = holder.binding.tvProgress,
            date = LocalDate.now(),
            startPeriod = c.startPeriod,
            endPeriod = c.endPeriod,
            now = LocalDateTime.now(),
        )
    }

    /**
     * 已上完的课显示蓝色对勾（ADR-051）。
     *
     * ⚠️ 用户要求：「上完的课这里显示一个 和卡片一样的蓝色的勾 勾可以大一点点」。
     *
     * 与进度条互斥（见 [CourseStatus]）：正在上 → 进度条；已上完 → 对勾。
     * 由心跳定时调用 —— 下课那一刻会自动从进度条切成对勾。
     */
    private fun bindDoneBadge(holder: VH, position: Int) {
        val c = items.getOrNull(position) ?: return
        val done = CourseStatus.isDone(
            date = LocalDate.now(),
            startPeriod = c.startPeriod,
            endPeriod = c.endPeriod,
            now = LocalDateTime.now(),
        )
        holder.binding.doneBadge.visibility =
            if (done) android.view.View.VISIBLE else android.view.View.GONE
    }

    /** 刷新所有可见项的进度（由 Fragment 定时驱动）。 */
    fun refreshProgress() {
        notifyItemRangeChanged(0, items.size, PAYLOAD_PROGRESS)
    }

    companion object {
        const val PAYLOAD_PROGRESS = "progress"
    }
}
