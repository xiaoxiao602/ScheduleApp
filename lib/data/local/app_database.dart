import 'dart:io';

import 'package:drift/drift.dart';
import 'package:drift/native.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

import 'tables.dart';

part 'app_database.g.dart';

/// ============================================================
/// 本地数据库（对应原 Android 版 data/local/AppDatabase.kt）
/// ============================================================
///
/// 原版是 Room v3 且用 fallbackToDestructiveMigration()（升版清库）。
/// ⚠️ 说明书 §4.1 明确建议 Flutter 版**改用真正的 migration**，
///    避免用户反复重新同步 —— 故此处用 schemaVersion + onUpgrade。
///
/// ⚠️ 本地库是唯一数据源（核心设计）：所有界面只读本库，
///    同步只是往库里写；断网/教务故障不影响查看。
@DriftDatabase(tables: [Courses, Exams, Grades, Metas, DayOverrides])
class AppDatabase extends _$AppDatabase {
  AppDatabase() : super(_openConnection());

  /// 测试用：传入内存库。
  AppDatabase.forTesting(super.e);

  @override
  int get schemaVersion => 3; // 与原版 Room v3 对齐

  @override
  MigrationStrategy get migration => MigrationStrategy(
        onCreate: (m) async {
          await m.createAll();
        },
        onUpgrade: (m, from, to) async {
          // 预留：将来在此按版本增量迁移。
          // 目前 v3 为首个 Flutter 版 schema，没有历史数据需要迁移。
          //
          // ⚠️ 原 Android 版用 fallbackToDestructiveMigration（清库），
          //    Flutter 版刻意不这么做（见说明书 §4.1 建议）。
          if (from < 3) {
            // 未来版本在此补 ALTER TABLE / 数据迁移
          }
        },
      );
}

/// 元数据键（对应原版 AppDatabase.MetaKeys）。
///
/// ⚠️ 键名必须与原版完全一致，便于将来与原版数据互通。
abstract final class MetaKeys {
  static const lastSyncAt = 'last_sync_at';
  static const currentTerm = 'current_term';
  static const lastUsername = 'last_username'; // 仅方便下次输入，非凭据
  static const firstMonday = 'first_monday'; // 学期第一周星期一（ISO）
  static const studentName = 'student_name';
  static const studentNo = 'student_no';
  static const studentMajor = 'student_major';
  static const studentClass = 'student_class';
  static const displayName = 'display_name';
  static const academicYearName = 'academic_year_name';
  static const termName = 'term_name'; // ⚠️ 来自 XQMMC（值 "1"），不是 XQM（值 "3"）
  static const holidays = 'holidays'; // 假日区间 JSON 数组
}

LazyDatabase _openConnection() {
  return LazyDatabase(() async {
    final dir = await getApplicationDocumentsDirectory();
    final file = File(p.join(dir.path, 'gzuschedule.db'));
    return NativeDatabase.createInBackground(file);
  });
}
