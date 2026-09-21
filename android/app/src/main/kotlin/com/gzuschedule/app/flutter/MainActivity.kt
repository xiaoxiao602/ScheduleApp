package com.gzuschedule.app.flutter

import io.flutter.embedding.android.FlutterActivity

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
class MainActivity : FlutterActivity()
