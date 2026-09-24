package com.gzuschedule.app.flutter

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/**
 * 广软课程表 · Flutter 版入口 Activity。
 *
 * ⚠️ 包名必须等于 android/app/build.gradle.kts 的 `namespace`
 *    即 com.gzuschedule.app.flutter（原 Kotlin 版是 com.gzuschedule.app，
 *    Flutter 版加了 .flutter 后缀以区分，两者可共存于同一台设备）。
 *
 * ⚠️ AndroidManifest.xml 用 android:name=".MainActivity" 相对名，
 *    会解析成 namespace + ".MainActivity"。若此类缺失或包名不符，
 *    启动时 ClassNotFoundException -> 立即闪退。
 */
class MainActivity : FlutterActivity() {

    // ============================================================
    // 触感反馈桥（1.2.1+13）—— 复刻原版 Haptics.kt（ADR-092）
    // ============================================================
    //
    // ⚠️ 为什么必须走原生：Flutter 内置 HapticFeedback 只有 5 种常量，
    //    6 个风格里 3 个会被映射成同一效果（用户反馈「好几个效果重复」）。
    //    原版用 HapticFeedbackConstants.CLOCK_TICK/VIRTUAL_KEY/KEYBOARD_TAP/
    //    TEXT_HANDLE_MOVE/CONTEXT_CLICK/LONG_PRESS —— 6 种 OEM 调校的真效果。
    //
    // ⚠️ 强度缩放：performHapticFeedback 不支持调强度，
    //    强度 <100 时用 Vibrator 补一个 8ms 短振（振幅按比例）——
    //    与原版 Haptics.amplify 逐行对齐。
    private val hapticChannel = "gzuschedule/haptic"
    private lateinit var islandChannel: IslandChannel
    private lateinit var alarmScheduler: AlarmScheduler

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        islandChannel = IslandChannel(applicationContext)
        islandChannel.register(flutterEngine)
        alarmScheduler = AlarmScheduler(applicationContext)
        alarmScheduler.register(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, hapticChannel)
            .setMethodCallHandler { call, result ->
                if (call.method == "perform") {
                    val style = call.argument<String>("style") ?: "clockTick"
                    val strength = call.argument<Int>("strength") ?: 100
                    performHaptic(style, strength)
                    result.success(null)
                } else {
                    result.notImplemented()
                }
            }
    }

    private fun constantOf(style: String): Int = when (style) {
        "virtualKey" -> HapticFeedbackConstants.VIRTUAL_KEY
        "keyboardTap" -> HapticFeedbackConstants.KEYBOARD_TAP
        "textHandleMove" -> HapticFeedbackConstants.TEXT_HANDLE_MOVE
        "contextClick" -> HapticFeedbackConstants.CONTEXT_CLICK
        "longPress" -> HapticFeedbackConstants.LONG_PRESS
        else -> HapticFeedbackConstants.CLOCK_TICK
    }

    /**
     * ⚠️ 与原版 Haptics.kt 相同的 flag 语义：
     *   · 不加 FLAG_IGNORE_GLOBAL_SETTING —— 尊重用户系统触感总开关；
     *   · 加 FLAG_IGNORE_VIEW_SETTING —— 开关由 App 自己管。
     * 触感永远不能影响主流程，异常全吞。
     */
    private fun performHaptic(style: String, strength: Int) {
        try {
            window.decorView.performHapticFeedback(
                constantOf(style),
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && strength < 100) {
                amplify(strength)
            }
        } catch (_: Throwable) {
        }
    }

    private fun amplify(strength: Int) {
        try {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val mgr =
                    getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                mgr?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator == null || !vibrator.hasVibrator()) return

            val amp = (strength.coerceIn(30, 100) * 255 / 100).coerceIn(1, 255)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(8L, amp),
                    VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH),
                )
            } else {
                vibrator.vibrate(VibrationEffect.createOneShot(8L, amp))
            }
        } catch (_: Throwable) {
        }
    }
}
