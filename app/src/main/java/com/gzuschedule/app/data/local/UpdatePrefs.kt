package com.gzuschedule.app.data.local

import android.content.Context

/**
 * 设置项：自动检查更新（ADR-107）。
 *
 * ⚠️ 用户需求：
 *    「在设置里加入检查更新的功能 自动访问我的仓库看看软件有没有新版本
 *      可以设置开启或者关闭」
 *
 * 设计：
 *   · 只持久化一个开关（是否自动检查）
 *   · 检查结果不持久化 —— 避免显示陈旧提示
 */
object UpdatePrefs {

    private const val PREF = "update_prefs"
    private const val KEY_AUTO_CHECK = "auto_check"

    /** 默认开启（用户要求"自动访问"，默认开更符合预期）。 */
    private const val DEF_AUTO_CHECK = true

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 是否自动检查更新。 */
    fun isAutoCheck(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_AUTO_CHECK, DEF_AUTO_CHECK)

    fun setAutoCheck(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_AUTO_CHECK, enabled).apply()
    }
}
