package com.gzuschedule.app.ui.grade

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.gzuschedule.app.R
import com.gzuschedule.app.databinding.ActivitySubpageBinding

/** 成绩二级页容器（ADR-006：成绩/考试在「设置」里的二级入口）。 */
class GradeListActivity : AppCompatActivity() {

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

        binding.toolbar.title = "成绩"
        binding.toolbar.setNavigationOnClickListener { finish() }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, GradeFragment())
                .commit()
        }
    }
}
