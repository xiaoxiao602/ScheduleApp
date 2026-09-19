package com.gzuschedule.app.ui.haptic

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.google.android.material.slider.Slider
import com.gzuschedule.app.data.local.HapticStore
import com.gzuschedule.app.data.local.Style
import com.gzuschedule.app.databinding.FragmentHapticBinding
import com.gzuschedule.app.ui.widget.Haptics
import com.gzuschedule.app.ui.widget.RoundOptionDialog

/**
 * 触感反馈二级页内容（ADR-094）。
 *
 * ⚠️ 从 SettingsFragment.setupHaptic() 抽出来独立成页。
 *
 * ⚠️ 为什么做成可选：
 *    「小米13 是清脆的反馈，小米15 是咚咚」
 *    —— 震动波形由 OEM 决定，同一份代码在不同机型必然不同。
 *    所以让用户在自己设备上对比着选，而不是替他猜一个"最好"的。
 */
class HapticFragment : Fragment() {

    private var _binding: FragmentHapticBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentHapticBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val store = HapticStore(requireContext())
        val b = binding

        // ---------- 风格选择（ADR-095：圆角弹窗，不用 Spinner） ----------
        // ⚠️ Spinner 的下拉列表是系统绘制的，圆角改不了，与 App 风格不统一。
        //    改用 RoundOptionDialog —— 自带 28dp 圆角 + 去掉系统 Window 背景。
        b.rowHapticStyle.setOnClickListener { v ->
            Haptics.click(v)
            val styles = Style.ALL
            RoundOptionDialog.show(
                context = requireContext(),
                title = "震动风格",
                options = styles.map { RoundOptionDialog.Option(it.label, it.desc) },
                selectedIndex = styles.indexOfFirst { it == store.style },
            ) { index ->
                val picked = styles.getOrNull(index) ?: return@show
                store.style = picked
                renderLabels()
                // 选了立刻试一下，方便对比
                Haptics.preview(b.btnHapticPreview, picked, store.strength)
            }
        }

        // ---------- 「试一下」----------
        // ⚠️ 用 preview() 而非 click()：即使用户关掉总开关也能试出手感，
        //    否则关了开关后就永远没法对比了。
        b.btnHapticPreview.setOnClickListener { v ->
            Haptics.preview(v, store.style, store.strength)
        }

        // ---------- 总开关 ----------
        b.swHapticEnabled.setOnCheckedChangeListener { v, checked ->
            store.enabled = checked
            renderLabels()
            if (checked) Haptics.preview(v, store.style, store.strength)
        }

        // ---------- 场景开关 ----------
        b.swHapticDock.setOnCheckedChangeListener { v, checked ->
            store.onDock = checked
            if (checked) Haptics.preview(v, store.style, store.strength)
        }
        b.swHapticTab.setOnCheckedChangeListener { v, checked ->
            store.onTab = checked
            if (checked) Haptics.preview(v, store.style, store.strength)
        }
        b.swHapticButton.setOnCheckedChangeListener { v, checked ->
            store.onButton = checked
            if (checked) Haptics.preview(v, store.style, store.strength)
        }

        // ---------- 强度 ----------
        // ⚠️ addOnChangeListener 是【追加】语义 —— 绑定前必须 clear，
        //    否则每次进出页面都会多注册一层（和 Dock 面板同一个坑）。
        b.sliderHapticStrength.clearOnChangeListeners()
        b.sliderHapticStrength.clearOnSliderTouchListeners()
        b.sliderHapticStrength.valueFrom = HapticStore.MIN_STRENGTH.toFloat()
        b.sliderHapticStrength.valueTo = HapticStore.MAX_STRENGTH.toFloat()
        b.sliderHapticStrength.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                store.strength = value.toInt()
                renderLabels()
            }
        }
        b.sliderHapticStrength.addOnSliderTouchListener(
            object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) = Unit
                override fun onStopTrackingTouch(slider: Slider) {
                    // 松手试一次当前强度
                    Haptics.preview(b.btnHapticPreview, store.style, store.strength)
                }
            },
        )

        renderLabels()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /** 把 store 的值同步到 UI（下拉选中 / 标签 / 滑块 / 开关）。 */
    private fun renderLabels() {
        val store = HapticStore(requireContext())
        val b = binding

        val style = store.style
        b.tvHapticStyleLabel.text = "当前：${style.label}"
        b.tvHapticStyleDesc.text = style.desc

        // 右下角显示当前风格（点击弹出选择）
        b.tvHapticStyleValue.text = style.label

        b.sliderHapticStrength.value = store.strength
            .coerceIn(HapticStore.MIN_STRENGTH, HapticStore.MAX_STRENGTH).toFloat()

        b.tvHapticStrengthLabel.text =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                "强度 · ${store.strength}%"
            else
                "强度 · ${store.strength}%（Android 12 以下不生效）"

        b.swHapticEnabled.isChecked = store.enabled
        b.swHapticDock.isChecked = store.onDock
        b.swHapticTab.isChecked = store.onTab
        b.swHapticButton.isChecked = store.onButton
    }
}
