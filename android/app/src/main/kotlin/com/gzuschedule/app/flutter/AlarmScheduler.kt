package com.gzuschedule.app.flutter

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.flutter.plugin.common.MethodChannel

/**
 * 闹钟调度器（+25）—— 自己排程，绕开 android_alarm_manager_plus 的过度守卫。
 *
 * ⚠️ 为什么：插件对 setAlarmClock/setExact 都包了 `canScheduleExactAlarms()` 检查，
 *    为否时**只打日志、静默不排程**（用户实测：后台不更新不推送，链路全灭）。
 *    而官方文档明确 **setAlarmClock 不需要任何闹钟权限**且 Doze 免疫 ——
 *    插件的守卫是过度检查。我们直接调系统 API 就没有这个问题。
 *
 * ⚠️ 回调派发**复用插件的 AlarmBroadcastReceiver**：
 *    发出的 Intent 与插件格式完全一致（extras: id / callbackHandle / params-JSON），
 *    接收器照常拉起 Dart 回调（AndroidAlarmManager.initialize 注册的 dispatcher）。
 */
class AlarmScheduler(private val context: Context) {

    companion object {
        const val CHANNEL = "gzuschedule/alarm"
        private const val RECEIVER =
            "dev.fluttercommunity.plus.androidalarmmanager.AlarmBroadcastReceiver"
    }

    fun register(flutterEngine: io.flutter.embedding.engine.FlutterEngine) {
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                try {
                    when (call.method) {
                        "schedule" -> {
                            val id = num(call.argument("id")).toInt()
                            val triggerMs = num(call.argument("triggerMs")).toLong()
                            val callbackHandle = num(call.argument("callbackHandle")).toLong()
                            val params = call.argument<String>("params")
                            schedule(id, triggerMs, callbackHandle, params)
                            result.success(null)
                        }
                        "cancel" -> {
                            cancel(num(call.argument("id")).toInt())
                            result.success(null)
                        }
                        else -> result.notImplemented()
                    }
                } catch (t: Throwable) {
                    result.error("alarm", t.message, null)
                }
            }
    }

    private fun receiverIntent(id: Int, callbackHandle: Long, params: String?): Intent {
        val intent = Intent().setClassName(context, RECEIVER)
        intent.putExtra("id", id)
        intent.putExtra("callbackHandle", callbackHandle)
        if (params != null) intent.putExtra("params", params)
        return intent
    }

    private fun pendingIntent(id: Int, callbackHandle: Long, params: String?): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            id,
            receiverIntent(id, callbackHandle, params),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun schedule(id: Int, triggerMs: Long, callbackHandle: Long, params: String?) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // setAlarmClock 的 showIntent：点状态栏闹钟图标打开 App。
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val show = PendingIntent.getActivity(
            context,
            id,
            launch ?: Intent(),
            PendingIntent.FLAG_IMMUTABLE,
        )
        am.setAlarmClock(
            AlarmManager.AlarmClockInfo(triggerMs, show),
            pendingIntent(id, callbackHandle, params),
        )
    }

    private fun cancel(id: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // PendingIntent 匹配不含 extras —— callbackHandle/params 传什么都相等。
        am.cancel(pendingIntent(id, 0, null))
    }

    private fun num(v: Any?): Number = when (v) {
        is Number -> v
        is String -> v.toDoubleOrNull() ?: 0
        else -> 0
    }
}
