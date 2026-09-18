package com.gzuschedule.app.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gzuschedule.app.data.auth.LoginDiagnostics
import com.gzuschedule.app.data.auth.LyuapAuthRepository
import com.gzuschedule.app.data.auth.RsaPasswordEncoder
import com.gzuschedule.app.data.local.AppDatabase
import com.gzuschedule.app.data.local.CredentialStore
import com.gzuschedule.app.data.local.LocalExamRepository
import com.gzuschedule.app.data.local.LocalGradeRepository
import com.gzuschedule.app.data.local.LocalScheduleRepository
import com.gzuschedule.app.domain.model.AuthError
import com.gzuschedule.app.domain.model.Captcha
import com.gzuschedule.app.domain.usecase.SyncUseCase
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

data class LoginUiState(
    val captcha: Captcha? = null,
    val isLoadingCaptcha: Boolean = false,
    val isSubmitting: Boolean = false,
    /** 当前同步阶段文案，如「正在登录…」「正在拉取课表…」 */
    val progress: String? = null,
)

sealed interface LoginEvent {
    /** 同步成功，可以关掉登录页回到主界面 */
    data class Success(val courseCount: Int) : LoginEvent
    data class ShowError(val message: String) : LoginEvent
    data object ClearError : LoginEvent
}

class LoginViewModel(
    private val authRepository: LyuapAuthRepository,
    private val syncUseCase: SyncUseCase,
    private val credentialStore: CredentialStore,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    private val _events = Channel<LoginEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun refreshCaptcha() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoadingCaptcha = true)
            authRepository.fetchCaptcha()
                .onSuccess { captcha ->
                    _state.value = _state.value.copy(
                        captcha = captcha,
                        isLoadingCaptcha = false,
                    )
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(isLoadingCaptcha = false)
                    _events.send(LoginEvent.ShowError(error.toMessage()))
                }
        }
    }

    /**
     * 登录并同步数据。
     *
     * 流程（ADR-005 + ADR-008）：
     *   1. 本地校验
     *   2. RSA 加密密码（倒序 + 无填充）
     *   3. 走 CAS → 正方票据链 → 拉课表 → 存库 → 登出
     *   4. 凭据默认不落盘；仅当用户勾选「记住账密」并经风险确认后，
     *      用 Keystore 加密保存（ADR-011）。未勾选则同步后清除。
     */
    fun login(
        username: String,
        rawPassword: String,
        captcha: String,
        rememberCredential: Boolean = false,
    ) {
        val localError = validate(username, rawPassword, captcha)
        if (localError != null) {
            viewModelScope.launch { _events.send(LoginEvent.ShowError(localError)) }
            return
        }

        val uid = _state.value.captcha?.uid
        if (uid.isNullOrBlank()) {
            viewModelScope.launch {
                _events.send(LoginEvent.ShowError("验证码未加载，请点击刷新"))
            }
            return
        }

        viewModelScope.launch {
            _events.send(LoginEvent.ClearError)
            _state.value = _state.value.copy(isSubmitting = true, progress = "正在登录…")

            // ⚠️ 密码加密：倒序 + 无填充 RSA + hex（ADR-008 抓包证实）
            val encrypted = runCatching { RsaPasswordEncoder.encode(rawPassword) }
                .getOrElse { e ->
                    _state.value = _state.value.copy(isSubmitting = false, progress = null)
                    _events.send(LoginEvent.ShowError("密码加密失败：${e.message}"))
                    return@launch
                }

            LoginDiagnostics.log("密码 RSA/HEX -> ${encrypted.length} 字符")

            val result = syncUseCase.execute(
                username = username,
                encryptedPassword = encrypted,
                captchaId = uid,
                captchaCode = captcha.trim(),
                onProgress = { p ->
                    _state.value = _state.value.copy(progress = p.toText())
                },
            )

            _state.value = _state.value.copy(isSubmitting = false, progress = null)

            if (result.success) {
                // ---- 凭据保存策略（ADR-011）----
                // 勾选 -> 加密保存；未勾选 -> 清除已有记录。
                // ⚠️ 仅在【同步成功】后处理，避免把错密码存下来。
                runCatching {
                    if (rememberCredential) {
                        credentialStore.save(username, rawPassword)
                        LoginDiagnostics.log("凭据已加密保存")
                    } else {
                        credentialStore.clear()
                        LoginDiagnostics.log("未勾选记住账密，已清除本地凭据")
                    }
                }

                _events.send(LoginEvent.Success(result.courseCount))
            } else {
                _events.send(LoginEvent.ShowError(result.message ?: "同步失败"))
                // 验证码一次性：失败后换新
                refreshCaptcha()
            }
        }
    }

    private fun SyncUseCase.Progress.toText(): String = when (this) {
        SyncUseCase.Progress.LoggingIn -> "正在登录…"
        SyncUseCase.Progress.BootstrapSession -> "正在建立会话…"
        SyncUseCase.Progress.FetchingSchedule -> "正在拉取课表…"
        SyncUseCase.Progress.FetchingGrades -> "正在拉取成绩…"
        SyncUseCase.Progress.FetchingExams -> "正在拉取考试…"
        SyncUseCase.Progress.Saving -> "正在保存…"
        SyncUseCase.Progress.LoggingOut -> "正在安全退出…"
    }

    private fun validate(username: String, password: String, captcha: String): String? = when {
        username.isBlank() -> "请输入学号"
        password.isBlank() -> "请输入密码"
        captcha.isBlank() -> "请输入验证码"
        else -> null
    }

    class Factory(
        private val db: AppDatabase,
        private val appContext: android.content.Context,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val sync = SyncUseCase(
                db = db,
                scheduleRepo = LocalScheduleRepository(db),
                gradeRepo = LocalGradeRepository(db),
                examRepo = LocalExamRepository(db),
            )
            val credentials = CredentialStore(appContext)
            return LoginViewModel(LyuapAuthRepository(), sync, credentials) as T
        }
    }
}

/** 把领域错误翻译成给用户看的中文文案。 */
private fun Throwable.toMessage(): String = when (this) {
    is AuthError -> message ?: "登录失败"
    else -> "登录失败：${message ?: this::class.java.simpleName}"
}
