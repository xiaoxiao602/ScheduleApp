package com.gzuschedule.app.ui.haptic

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.gzuschedule.app.R
import com.gzuschedule.app.databinding.ActivitySubpageBinding

/**
 * 触感反馈二级页（ADR-094）。
 *
 * ⚠️ 用户反馈：「给 dock 外观调节和震动调节 也做其他设置界面一样的二级页面」
 *    —— 与 DockTuningActivity 同批改造，保持所有设置项形态一致。
 */
class HapticActivity : AppCompatActivity() {

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

        binding.toolbar.title = "触感反馈"
        binding.toolbar.setNavigationOnClickListener { finish() }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, HapticFragment())
                .commit()
        }
    }
}
