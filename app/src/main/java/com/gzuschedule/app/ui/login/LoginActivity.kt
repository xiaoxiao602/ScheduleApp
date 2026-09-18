package com.gzuschedule.app.ui.login

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import com.gzuschedule.app.R
import com.gzuschedule.app.data.auth.LoginDiagnostics
import com.gzuschedule.app.data.auth.LyuapAuthRepository
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.gzuschedule.app.data.local.CredentialStore
import com.gzuschedule.app.data.local.AppDatabase
import com.gzuschedule.app.databinding.ActivityLoginBinding
import com.gzuschedule.app.ui.main.MainActivity
import kotlinx.coroutines.launch

/**
 * 登录页 —— 账密 + 图形验证码。
 *
 * 结构说明：本 Activity 只做「显示」与「事件转发」，
 * 所有逻辑在 [LoginViewModel]，网络细节在 AuthRepository 实现里。
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var viewModel: LoginViewModel

    private lateinit var credentialStore: CredentialStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(
            this,
            LoginViewModel.Factory(
                AppDatabase.get(requireNotNull(this@LoginActivity).applicationContext),
                requireNotNull(this@LoginActivity).applicationContext,
            )
        )[LoginViewModel::class.java]

        credentialStore = CredentialStore(applicationContext)

        setupListeners()
        observeState()
        prefillSavedCredentials()

        // 进入页面即拉一张验证码
        viewModel.refreshCaptcha()
    }

    /**
     * 若上次勾选了「记住账密」，自动填入。
     * ⚠️ 密码只用于填充输入框，仍走内存 → 不上传、不写日志。
     */
    private fun prefillSavedCredentials() {
        if (!credentialStore.hasCredentials) return
        binding.etUsername.setText(credentialStore.username.orEmpty())
        binding.etPassword.setText(credentialStore.password.orEmpty())
        binding.cbRemember.isChecked = true
    }

    /**
     * 勾选「记住账密」时弹出风险提示。
     *
     * 用户明确要求：勾选后提示风险。这里做成「勾选即提示」，
     * 取消勾选则静默（并会在同步后清除）。
     */
    private fun setupRememberCheckbox() {
        binding.cbRemember.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("保存账号密码？")
                    .setMessage(
                        "密码将使用系统密钥库（Android Keystore）加密后保存在本机，\n" +
                            "不会上传到任何服务器。\n\n" +
                            "请注意：\n" +
                            "· 手机丢失或被他人解锁时，存在被读取的风险\n" +
                            "· 如需停止保存，随时可取消勾选，下次同步后即清除\n\n" +
                            "是否继续？",
                    )
                    .setNegativeButton("取消") { _, _ ->
                        binding.cbRemember.isChecked = false
                    }
                    .setPositiveButton("同意并保存") { _, _ -> /* 保持勾选 */ }
                    .show()
            }
        }
    }

    private fun setupListeners() {
        setupRememberCheckbox()

        binding.btnLogin.setOnClickListener {
            viewModel.login(
                username = binding.etUsername.text?.toString().orEmpty().trim(),
                rawPassword = binding.etPassword.text?.toString().orEmpty(),
                captcha = binding.etCaptcha.text?.toString().orEmpty().trim(),
                rememberCredential = binding.cbRemember.isChecked,
            )
        }

        // 点验证码图片刷新
        binding.captchaContainer.setOnClickListener {
            binding.etCaptcha.setText("")
            viewModel.refreshCaptcha()
        }

        // 诊断面板：展开/收起，显示服务端原始反馈
        binding.btnToggleDiag.setOnClickListener {
            val tv = binding.tvDiagnostics
            if (tv.visibility == View.GONE) {
                tv.text = buildString {
                    appendLine("=== 当前协议配置 ===")
                    appendLine(LoginDiagnostics.currentConfig())
                    appendLine()
                    appendLine("=== 请求日志 ===")
                    append(LoginDiagnostics.snapshot())
                }
                tv.visibility = View.VISIBLE
            } else {
                tv.visibility = View.GONE
            }
        }

        // 验证码错误后自动刷新一张，省一次点击
        // （在 observeState 中处理）
    }

    private fun observeState() {
        lifecycleScope.launch {
            viewModel.state.collect { state -> render(state) }
        }
        lifecycleScope.launch {
            viewModel.events.collect { event ->
                when (event) {
                    is LoginEvent.Success -> {
                        // 同步完成，回到主界面看数据（不再跳转到别处）
                        showError("")   // 清空错误区
                        androidx.appcompat.app.AlertDialog.Builder(this@LoginActivity)
                            .setTitle("同步完成")
                            .setMessage("已获取 ${event.courseCount} 条课程数据。\n\n账号密码已从内存中清除，未保存在手机上。")
                            .setPositiveButton("好的") { _, _ ->
                                setResult(RESULT_OK)
                                finish()
                            }
                            .setCancelable(false)
                            .show()
                    }
                    is LoginEvent.ShowError -> showError(event.message)
                    LoginEvent.ClearError -> hideError()
                }
            }
        }
    }

    private fun render(state: LoginUiState) {
        binding.pbLogin.visibility = if (state.isSubmitting) View.VISIBLE else View.GONE
        binding.btnLogin.isEnabled = !state.isSubmitting

        binding.pbCaptcha.visibility =
            if (state.isLoadingCaptcha) View.VISIBLE else View.GONE

        state.captcha?.let { captcha ->
            renderCaptcha(captcha.base64)
        }
    }

    /** 把 base64 PNG 解码成 Bitmap 显示。验证码 100x25，ImageView 自适应。 */
    private fun renderCaptcha(base64: String) {
        runCatching {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.onSuccess { bitmap ->
            if (bitmap != null) {
                binding.ivCaptcha.setImageBitmap(bitmap)
                binding.ivCaptcha.visibility = View.VISIBLE
            } else {
                showCaptchaPlaceholder("图片解码失败，点击重试")
            }
        }.onFailure { e ->
            // 不打印验证码内容/凭据
            Log.w(TAG, "验证码解码失败: ${e::class.java.simpleName}")
            showCaptchaPlaceholder("点击刷新")
        }
    }

    private fun showCaptchaPlaceholder(text: String) {
        binding.ivCaptcha.setImageDrawable(null)
        binding.pbCaptcha.visibility = View.GONE
        // 用 ImageView 的 contentDescription 承载文案，避免额外控件
        binding.ivCaptcha.contentDescription = text
    }

    private fun showError(message: String) {
        binding.tvError.text = message
        binding.tvError.visibility = View.VISIBLE
    }

    private fun hideError() {
        binding.tvError.visibility = View.GONE
    }

    private companion object {
        const val TAG = "LoginActivity"
    }
}
