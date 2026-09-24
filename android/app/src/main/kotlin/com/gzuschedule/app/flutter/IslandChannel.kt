package com.gzuschedule.app.flutter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import io.flutter.plugin.common.MethodChannel

/**
 * 超级岛通知桥（1.2.1+15，路线①探针版）。
 *
 * ⚠️ 为什么要原生：flutter_local_notifications 不能注入任意 extras，
 *    而小米超级岛靠 `miui.focus.param`（JSON）定义岛内容 —— 必须原生构造。
 *
 * ⚠️ 白名单：系统按焦点通知白名单卡「谁能上岛」。用户已 root + 模块解锁。
 *    **无权限环境下带参数的通知会退化为普通通知，不会坏**（官方
 *    filterWhenNoPermission 默认 false）——所以本探针对任何环境都安全。
 *
 * 探针目的（A/B）：同内容发两条 —— island=true（带岛参数）/ island=false
 * （普通），对比哪条/如何上岛，决定后续全量模板怎么写（HyperIsland 类模块
 * 对「已是超级岛」的通知会跳过转换，参数可能改变渲染路径）。
 */
class IslandChannel(private val context: Context) {

    companion object {
        const val CHANNEL = "gzuschedule/island"
        private const val CH_HIGH = "cls_remind"
        private const val CH_LOW = "cls_count"
    }

    private val manager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun register(flutterEngine: io.flutter.embedding.engine.FlutterEngine) {
        ensureChannels()
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "show" -> {
                        try {
                            show(call.arguments as? Map<*, *>)
                            result.success(null)
                        } catch (t: Throwable) {
                            result.error("island", t.message, null)
                        }
                    }
                    "cancel" -> {
                        val id = (call.argument<Number>("id"))?.toInt() ?: 0
                        manager.cancel(id)
                        result.success(null)
                    }
                    else -> result.notImplemented()
                }
            }
    }

    private fun ensureChannels() {
        val high = NotificationChannel(
            CH_HIGH, "上课提醒", NotificationManager.IMPORTANCE_HIGH
        )
        val low = NotificationChannel(
            CH_LOW, "课程倒计时", NotificationManager.IMPORTANCE_LOW
        ).apply {
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(high)
        manager.createNotificationChannel(low)
    }

    private fun show(args: Map<*, *>?) {
        if (args == null) return
        val id = num(args["id"]).toInt()
        val title = args["title"] as? String ?: ""
        val body = args["body"] as? String ?: ""
        val whenMs = num(args["whenMs"]).toLong()
        val chronoDown = args["chronoDown"] as? Boolean ?: true
        val hasProgress = args["hasProgress"] as? Boolean ?: false
        val progress = num(args["progress"]).toInt()
        val lowChannel = args["lowChannel"] as? Boolean ?: true
        val island = args["island"] as? Boolean ?: false
        val stage = args["stage"] as? String ?: ""
        val ticker = args["ticker"] as? String ?: ""
        val aodTitle = args["aodTitle"] as? String ?: ""
        val bigTitle = args["bigTitle"] as? String ?: title
        val bigContent = args["bigContent"] as? String ?: body
        val bigFoot = args["bigFoot"] as? String ?: ""

        val builder = Notification.Builder(context, if (lowChannel) CH_LOW else CH_HIGH)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setWhen(if (whenMs > 0) whenMs else System.currentTimeMillis())
            .setShowWhen(true)
            .setOngoing(false)
            .setAutoCancel(false)
            .setOnlyAlertOnce(lowChannel) // HIGH 首次响铃；LOW 静默
            .setVisibility(Notification.VISIBILITY_PUBLIC) // 锁屏可见

        if (whenMs > 0) {
            builder.setUsesChronometer(true).setChronometerCountDown(chronoDown)
        }
        if (hasProgress) {
            builder.setProgress(100, progress.coerceIn(0, 100), false)
        }

        if (island) {
            // 岛参数：无权限环境自动退化为普通通知（安全）。
            builder.extras.putString(
                "miui.focus.param",
                focusJson(stage, ticker, aodTitle, bigTitle, bigContent, bigFoot),
            )
        }

        manager.notify(id, builder.build())
    }

    /**
     * 焦点通知/超级岛参数（文本模板探针）。
     * 字段语义参照小米超级岛文档（param_v2 / param_island / baseInfo），
     * 正式版将按模板库精修三阶段样式。
     */
    private fun focusJson(
        stage: String,
        ticker: String,
        aodTitle: String,
        bigTitle: String,
        bigContent: String,
        bigFoot: String,
    ): String {
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        val front = when (stage) {
            "before" -> "快上课"
            "in" -> "上课中"
            "after" -> "已下课"
            else -> ""
        }
        return """
        {
          "param_v2": {
            "protocol": 1,
            "business": "course",
            "enableFloat": true,
            "updatable": true,
            "ticker": "${esc(ticker)}",
            "aodTitle": "${esc(aodTitle)}",
            "param_island": {
              "islandProperty": 1,
              "bigIslandArea": {
                "imageTextInfoLeft": {
                  "type": 1,
                  "textInfo": {
                    "frontTitle": "${esc(front)}",
                    "title": "${esc(bigTitle)}",
                    "content": "${esc(bigContent)}",
                    "useHighLight": true
                  }
                }
              },
              "smallIslandArea": {
                "textInfo": {
                  "frontTitle": "",
                  "title": "${esc(front)}",
                  "content": "${esc(bigFoot)}",
                  "useHighLight": true
                }
              }
            },
            "baseInfo": {
              "title": "${esc(bigTitle)}",
              "content": "${esc(bigContent)}",
              "type": 2
            },
            "hintInfo": {
              "type": 1,
              "title": "${esc(bigFoot)}"
            }
          }
        }
        """.trimIndent()
    }

    private fun num(v: Any?): Number = when (v) {
        is Number -> v
        is String -> v.toDoubleOrNull() ?: 0
        else -> 0
    }
}
