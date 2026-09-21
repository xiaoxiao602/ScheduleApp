import 'package:drift/drift.dart';

/// ============================================================
/// 数据表定义（对应原 Android 版 Room 实体）
/// ============================================================
///
/// 一比一复刻 sources：
///   data/local/entity/Entities.kt       -> Courses / Exams / Grades / Meta
///   data/local/DayOverride.kt           -> DayOverrides
///
/// ⚠️ 索引照搬（ADR-054 性能）：
///   courses: (term, dayOfWeek, startPeriod)
///   exams  : (date, startTime)

/// 课程表条目。
///
/// 原 Kotlin：
///   @Entity(tableName="courses", indices=[Index(value=["term","dayOfWeek","startPeriod"])])
///   data class CourseEntity(id, name, teacher, location, dayOfWeek,
///       startPeriod, endPeriod, startWeek, endWeek, weekParity, credit, note, term)
@DataClassName('CourseRow')
class Courses extends Table {
  IntColumn get id => integer().autoIncrement()();
  TextColumn get name => text()();
  TextColumn get teacher => text().withDefault(const Constant(''))();
  TextColumn get location => text().withDefault(const Constant(''))();
  IntColumn get dayOfWeek => integer()();
  IntColumn get startPeriod => integer()();
  IntColumn get endPeriod => integer()();
  IntColumn get startWeek => integer().withDefault(const Constant(1))();
  IntColumn get endWeek => integer().withDefault(const Constant(20))();
  IntColumn get weekParity => integer().withDefault(const Constant(0))();
  TextColumn get credit => text().withDefault(const Constant(''))();
  TextColumn get note => text().withDefault(const Constant(''))();
  TextColumn get term => text()();
}

/// 考试。
@DataClassName('ExamRow')
class Exams extends Table {
  IntColumn get id => integer().autoIncrement()();
  TextColumn get courseName => text()();
  TextColumn get date => text().nullable()();
  TextColumn get startTime => text().nullable()();
  TextColumn get endTime => text().nullable()();
  TextColumn get location => text().withDefault(const Constant(''))();
  TextColumn get seat => text().withDefault(const Constant(''))();
  TextColumn get format => text().withDefault(const Constant(''))();
  TextColumn get note => text().withDefault(const Constant(''))();
  TextColumn get term => text()();
}

/// 成绩。
@DataClassName('GradeRow')
class Grades extends Table {
  IntColumn get id => integer().autoIncrement()();
  TextColumn get term => text()();
  TextColumn get courseName => text()();
  TextColumn get score => text()();
  TextColumn get gpa => text().withDefault(const Constant(''))();
  TextColumn get credit => text().withDefault(const Constant(''))();
  TextColumn get category => text().withDefault(const Constant(''))();
  TextColumn get creditGpa => text().withDefault(const Constant(''))();
}

/// 元数据键值对（上次同步时间 / 当前学期 / 第一周星期一 / 学生信息 / 假日 JSON…）。
@DataClassName('MetaRow')
class Metas extends Table {
  TextColumn get key => text()();
  TextColumn get value => text()();

  @override
  Set<Column> get primaryKey => {key};
}

/// 当日调课（v1.0.2 新增）。
///
/// 原 Kotlin：
///   @Entity(tableName="day_override")
///   data class DayOverrideEntity(@PrimaryKey val date: String, val sourceDay: Int)
///   sourceDay: -1 = 无课；1..7 = 用该星期几的课表
@DataClassName('DayOverrideRow')
class DayOverrides extends Table {
  TextColumn get date => text()(); // ISO "2026-09-20"
  IntColumn get sourceDay => integer()();

  @override
  Set<Column> get primaryKey => {date};
}
