import 'package:drift/drift.dart';

import '../../domain/models.dart';
import 'app_database.dart';

/// ============================================================
/// DAO + 仓库（对应 data/local/dao/Daos.kt 与 LocalRepositories.kt）
/// ============================================================
///
/// ⚠️ 核心设计：**本地库是唯一数据源**。
///    所有界面只读本库；同步只是往库里写。断网/教务故障不影响查看。

/// 课程表仓库。
class CourseRepository {
  CourseRepository(this._db);

  final AppDatabase _db;

  /// 按学期观察课表（对应 CourseDao.observeByTerm）。
  ///
  /// ⚠️ 排序 (dayOfWeek, startPeriod) 正好命中复合索引 (term,dayOfWeek,startPeriod)。
  Stream<List<Course>> observeByTerm(String term) {
    final q = _db.select(_db.courses)
      ..where((t) => t.term.equals(term))
      ..orderBy([(t) => OrderingTerm.asc(t.dayOfWeek), (t) => OrderingTerm.asc(t.startPeriod)]);
    return q.watch().map((rows) => rows.map(_toDomain).toList());
  }

  Stream<List<Course>> observeAll() {
    final q = _db.select(_db.courses)
      ..orderBy([(t) => OrderingTerm.asc(t.dayOfWeek), (t) => OrderingTerm.asc(t.startPeriod)]);
    return q.watch().map((rows) => rows.map(_toDomain).toList());
  }

  Future<List<Course>> getByTerm(String term) async {
    final q = _db.select(_db.courses)
      ..where((t) => t.term.equals(term))
      ..orderBy([(t) => OrderingTerm.asc(t.dayOfWeek), (t) => OrderingTerm.asc(t.startPeriod)]);
    final rows = await q.get();
    return rows.map(_toDomain).toList();
  }

  Future<List<Course>> getAll() async {
    final q = _db.select(_db.courses)
      ..orderBy([(t) => OrderingTerm.asc(t.dayOfWeek), (t) => OrderingTerm.asc(t.startPeriod)]);
    final rows = await q.get();
    return rows.map(_toDomain).toList();
  }

  Stream<List<String>> observeTerms() {
    final q = _db.customSelect(
      'SELECT DISTINCT term FROM courses ORDER BY term DESC',
      readsFrom: {_db.courses},
    );
    return q.watch().map((r) => r.map((row) => row.read<String>('term')).toList());
  }

  Future<int> count() async {
    final c = _db.courses.id.count();
    final q = _db.selectOnly(_db.courses)..addColumns([c]);
    final row = await q.getSingle();
    return row.read(c) ?? 0;
  }

  /// 原子替换某学期课表：先清后插，避免半新半旧。
  Future<void> replaceTerm(String term, List<Course> items) async {
    await _db.transaction(() async {
      await (_db.delete(_db.courses)..where((t) => t.term.equals(term))).go();
      await _db.batch((b) {
        b.insertAll(
          _db.courses,
          items.map((c) => _toCompanion(c, term)).toList(),
        );
      });
    });
  }

  Future<void> deleteByTerm(String term) async {
    await (_db.delete(_db.courses)..where((t) => t.term.equals(term))).go();
  }

  Future<void> clear() async => _db.delete(_db.courses).go();

  static Course _toDomain(CourseRow r) => Course(
        id: r.id,
        name: r.name,
        teacher: r.teacher,
        location: r.location,
        dayOfWeek: r.dayOfWeek,
        startPeriod: r.startPeriod,
        endPeriod: r.endPeriod,
        startWeek: r.startWeek,
        endWeek: r.endWeek,
        weekParity: r.weekParity,
        credit: r.credit,
        note: r.note,
      );

  static CoursesCompanion _toCompanion(Course c, String term) => CoursesCompanion(
        name: Value(c.name),
        teacher: Value(c.teacher),
        location: Value(c.location),
        dayOfWeek: Value(c.dayOfWeek),
        startPeriod: Value(c.startPeriod),
        endPeriod: Value(c.endPeriod),
        startWeek: Value(c.startWeek),
        endWeek: Value(c.endWeek),
        weekParity: Value(c.weekParity),
        credit: Value(c.credit),
        note: Value(c.note),
        term: Value(term),
      );
}

/// 考试仓库。
class ExamRepository {
  ExamRepository(this._db);

  final AppDatabase _db;

  Stream<List<Exam>> observeAll() {
    final q = _db.select(_db.exams)
      ..orderBy([(t) => OrderingTerm.asc(t.date), (t) => OrderingTerm.asc(t.startTime)]);
    return q.watch().map((rows) => rows.map(_toDomain).toList());
  }

  Future<List<Exam>> getAll() async {
    final q = _db.select(_db.exams)
      ..orderBy([(t) => OrderingTerm.asc(t.date), (t) => OrderingTerm.asc(t.startTime)]);
    return (await q.get()).map(_toDomain).toList();
  }

  Future<void> replaceAll(List<Exam> items) async {
    await _db.transaction(() async {
      await _db.delete(_db.exams).go();
      await _db.batch((b) {
        b.insertAll(_db.exams, items.map(_toCompanion).toList());
      });
    });
  }

  Future<void> clear() async => _db.delete(_db.exams).go();

  static Exam _toDomain(ExamRow r) => Exam(
        id: r.id,
        courseName: r.courseName,
        date: r.date,
        startTime: r.startTime,
        endTime: r.endTime,
        location: r.location,
        seat: r.seat,
        format: r.format,
        note: r.note,
      );

  static ExamsCompanion _toCompanion(Exam e) => ExamsCompanion(
        courseName: Value(e.courseName),
        date: Value(e.date),
        startTime: Value(e.startTime),
        endTime: Value(e.endTime),
        location: Value(e.location),
        seat: Value(e.seat),
        format: Value(e.format),
        note: Value(e.note),
        term: Value(''),
      );
}

/// 成绩仓库。
class GradeRepository {
  GradeRepository(this._db);

  final AppDatabase _db;

  Stream<List<Grade>> observeAll() {
    final q = _db.select(_db.grades)
      ..orderBy([(t) => OrderingTerm.desc(t.term), (t) => OrderingTerm.asc(t.courseName)]);
    return q.watch().map((rows) => rows.map(_toDomain).toList());
  }

  Future<void> replaceAll(List<Grade> items) async {
    await _db.transaction(() async {
      await _db.delete(_db.grades).go();
      await _db.batch((b) {
        b.insertAll(_db.grades, items.map(_toCompanion).toList());
      });
    });
  }

  Future<void> clear() async => _db.delete(_db.grades).go();

  static Grade _toDomain(GradeRow r) => Grade(
        id: r.id,
        term: r.term,
        courseName: r.courseName,
        score: r.score,
        gpa: r.gpa,
        credit: r.credit,
        category: r.category,
        creditGpa: r.creditGpa,
      );

  static GradesCompanion _toCompanion(Grade g) => GradesCompanion(
        term: Value(g.term),
        courseName: Value(g.courseName),
        score: Value(g.score),
        gpa: Value(g.gpa),
        credit: Value(g.credit),
        category: Value(g.category),
        creditGpa: Value(g.creditGpa),
      );
}

/// 元数据仓库（键值对）。
class MetaRepository {
  MetaRepository(this._db);

  final AppDatabase _db;

  Future<String?> get(String key) async {
    final q = _db.select(_db.metas)..where((t) => t.key.equals(key));
    final row = await q.getSingleOrNull();
    return row?.value;
  }

  Stream<String?> observe(String key) {
    final q = _db.select(_db.metas)..where((t) => t.key.equals(key));
    return q.watchSingleOrNull().map((r) => r?.value);
  }

  Future<void> put(String key, String value) async {
    await _db.into(_db.metas).insertOnConflictUpdate(
          MetasCompanion.insert(key: key, value: value),
        );
  }

  Future<void> remove(String key) async {
    await (_db.delete(_db.metas)..where((t) => t.key.equals(key))).go();
  }

  Future<Map<String, String>> getAll() async {
    final rows = await _db.select(_db.metas).get();
    return {for (final r in rows) r.key: r.value};
  }

  Future<void> clear() async => _db.delete(_db.metas).go();
}

/// 当日调课仓库（对应 DayOverride.kt）。
///
/// date → sourceDay：-1 = 无课；1..7 = 用该星期几的课表。
class DayOverrideRepository {
  DayOverrideRepository(this._db);

  final AppDatabase _db;

  Future<DayOverride> get(String isoDate) async {
    final q = _db.select(_db.dayOverrides)..where((t) => t.date.equals(isoDate));
    final row = await q.getSingleOrNull();
    if (row == null) return DayOverride.none;
    return row.sourceDay == -1 ? DayOverride.noClass : DayOverride.useDay(row.sourceDay);
  }

  Stream<Map<String, int>> observeAll() {
    final q = _db.select(_db.dayOverrides);
    return q.watch().map((rows) => {for (final r in rows) r.date: r.sourceDay});
  }

  Future<void> put(String isoDate, int sourceDay) async {
    await _db.into(_db.dayOverrides).insertOnConflictUpdate(
          DayOverridesCompanion.insert(date: isoDate, sourceDay: sourceDay),
        );
  }

  Future<void> remove(String isoDate) async {
    await (_db.delete(_db.dayOverrides)..where((t) => t.date.equals(isoDate))).go();
  }

  Future<Map<String, int>> getAll() async {
    final rows = await _db.select(_db.dayOverrides).get();
    return {for (final r in rows) r.date: r.sourceDay};
  }
}
