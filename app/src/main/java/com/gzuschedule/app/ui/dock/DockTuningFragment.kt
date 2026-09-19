package com.gzuschedule.app.ui.dock

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.slider.Slider
import com.gzuschedule.app.data.local.DockTuningStore
import com.gzuschedule.app.databinding.FragmentDockTuningBinding
import com.gzuschedule.app.ui.widget.Haptics

/**
 * Dock 外观调节二级页内容（ADR-094）。
 *
 * ⚠️ 从 SettingsFragment.setupDockTuning() 抽出来独立成页。
 *    逻辑完全沿用（含那条重要的踩坑说明：Slider 监听器是**追加**语义，
 *    绑定前必须 clear，否则每次进出页面都会多注册一层，导致
 *    「改不回去 / 只会变大」）。
 */
class DockTuningFragment : Fragment() {

    private var _binding: FragmentDockTuningBinding? = null
    private val binding get() = _binding!!

    /** 防止「程序设置值」触发用户回调（否则会互相覆盖）。 */
    private var isBinding = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentDockTuningBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRows()
        binding.btnDockReset.setOnClickListener { v ->
            Haptics.confirm(v)
            resetToDefaults()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ---------- 参数表 ----------

    /**
     * 一行 = 一个滑块 + 一个标签。
     *
     * ⚠️ `min`/`max` 用 lambda 而不是直接取值 —— 保证与
     *    DockTuningStore 的常量**同源**，改常量时这里自动跟随，
     *    不会出现「滑块范围 ≠ store 范围」的错位。
     */
    private data class Row(
        val slider: Slider,
        val label: TextView,
        val read: () -> Int,
        val write: (Int) -> Unit,
        val format: (Int) -> String,
        val min: () -> Int,
        val max: () -> Int,
    )

    private fun dp(v: Int) = v

    private fun setupRows() {
        val store = DockTuningStore(requireContext())
        val b = binding

        val rows = listOf(
            Row(b.sliderDockHeight, b.tvDockHeightLabel,
                { store.dockBottomOffset }, { store.dockBottomOffset = it },
                { "距屏幕底部 · ${it}dp（位置）" },
                { DockTuningStore.MIN_BOTTOM_OFFSET }, { DockTuningStore.MAX_BOTTOM_OFFSET }),
            Row(b.sliderDockWidth, b.tvDockWidthLabel,
                { store.dockWidth }, { store.dockWidth = it },
                { if (it == 0) "Dock 宽度 · 自适应（左右各 16dp）" else "Dock 宽度 · 左右各留 ${it}dp" },
                { DockTuningStore.MIN_DOCK_WIDTH }, { DockTuningStore.MAX_DOCK_WIDTH }),
            Row(b.sliderDockCorner, b.tvDockCornerLabel,
                { store.dockCorner }, { store.dockCorner = it },
                { "Dock 圆角 · ${it}dp" },
                { DockTuningStore.MIN_CORNER }, { DockTuningStore.MAX_CORNER }),
            Row(b.sliderDockThickness, b.tvDockThicknessLabel,
                { store.dockHeight }, { store.dockHeight = it },
                { "Dock 厚度 · ${it}dp" },
                { DockTuningStore.MIN_HEIGHT }, { DockTuningStore.MAX_HEIGHT }),
            Row(b.sliderSliderHeight, b.tvSliderHeightLabel,
                { store.sliderHeight }, { store.sliderHeight = it },
                { "滑块高度 · ${it}dp" },
                { DockTuningStore.MIN_SLIDER_H }, { DockTuningStore.MAX_SLIDER_H }),
            Row(b.sliderSliderCorner, b.tvSliderCornerLabel,
                { store.sliderCorner }, { store.sliderCorner = it },
                { "滑块圆角 · ${it}dp" },
                { DockTuningStore.MIN_SLIDER_CORNER }, { DockTuningStore.MAX_SLIDER_CORNER }),
            Row(b.sliderSliderWidth, b.tvSliderWidthLabel,
                { store.sliderExtraWidth }, { store.sliderExtraWidth = it },
                { "滑块宽度增量 · ${it}dp（左右各 ${it / 2}）" },
                { DockTuningStore.MIN_SLIDER_W }, { DockTuningStore.MAX_SLIDER_W }),
            Row(b.sliderDockTextSize, b.tvDockTextSizeLabel,
                { store.textSizeTenths }, { store.textSizeTenths = it },
                { "文字大小 · ${it / 10f}sp" },
                { DockTuningStore.MIN_TEXT }, { DockTuningStore.MAX_TEXT }),
            Row(b.sliderAnimDuration, b.tvAnimDurationLabel,
                { store.animDuration }, { store.animDuration = it },
                { "滑动时长 · ${it}ms" },
                { DockTuningStore.MIN_ANIM_MS }, { DockTuningStore.MAX_ANIM_MS }),
            Row(b.sliderDamping, b.tvDampingLabel,
                { store.dampingOvershoot }, { store.dampingOvershoot = it },
                { "阻尼回弹 · ${"%.2f".format(it / 100f)}" },
                { DockTuningStore.MIN_DAMPING }, { DockTuningStore.MAX_DAMPING }),
        )

        // ⚠️ 每次渲染前先清空监听（追加语义的坑）
        rows.forEach { r ->
            r.slider.clearOnChangeListeners()
            r.slider.clearOnSliderTouchListeners()
            r.slider.valueFrom = r.min().toFloat()
            r.slider.valueTo = r.max().toFloat()
        }

        // 先渲染值（此时还没挂监听，不会触发写回）
        rows.forEach { r ->
            isBinding = true
            r.slider.value = r.read().toFloat().coerceIn(r.min().toFloat(), r.max().toFloat())
            r.label.text = r.format(r.read())
            isBinding = false
        }

        // 再挂监听
        rows.forEach { r ->
            r.slider.addOnChangeListener { _, v, fromUser ->
                if (fromUser && !isBinding) {
                    r.write(v.toInt())
                    r.label.text = r.format(v.toInt())
                }
            }
            r.slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) = Unit
                override fun onStopTrackingTouch(slider: Slider) {
                    // 松手时轻震一下，给"已保存"的反馈
                    Haptics.click(slider)
                }
            })
        }
    }

    /** 恢复所有 Dock 参数到默认值，并刷新 UI。 */
    private fun resetToDefaults() {
        val store = DockTuningStore(requireContext())
        store.resetAll()
        // 重建整页（最简单可靠：避免逐个手动同步）
        parentFragmentManager.beginTransaction()
            .replace(com.gzuschedule.app.R.id.container, DockTuningFragment())
            .commit()
    }
}
