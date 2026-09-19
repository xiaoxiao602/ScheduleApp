package com.gzuschedule.app.ui.dock

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.gzuschedule.app.R
import com.gzuschedule.app.databinding.ActivitySubpageBinding

/**
 * Dock 外观调节二级页（ADR-094）。
 *
 * ⚠️ 用户反馈：「给 dock 外观调节和震动调节 也做其他设置界面一样的二级页面」
 *    —— 原来这两个面板是**内嵌在设置页里展开/收起**的，
 *    与「课程外观」的二级页形态不一致，且滑块多时设置页会很长。
 *
 * ⚠️ 照 CourseColorActivity 的既有模式：
 *    复用 activity_subpage.xml（toolbar + container），只换标题和内容 Fragment。
 */
class DockTuningActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySubpageBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivitySubpageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.toolbar.setPadding(0, bars.top, 0, 0)
            binding.container.setPadding(0, 0, 0, bars.bottom)
            insets
        }

        binding.toolbar.title = "Dock 外观调节"
        binding.toolbar.setNavigationOnClickListener { finish() }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, DockTuningFragment())
                .commit()
        }
    }
}
