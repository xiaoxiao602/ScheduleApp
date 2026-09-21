// 领域逻辑冒烟测试（替换 Flutter 模板遗留的 Counter 测试——
// 它引用的 MyApp 早就不存在了，导致 `flutter test` 一直编译失败）。
//
// ⚠️ 只测**纯函数**（不碰 Flutter bindings / 数据库），保证 CI 可稳定通过。

import 'package:flutter_test/flutter_test.dart';

import 'package:gzuschedule/domain/colors.dart';

void main() {
  test('CourseColorAssigner：同名同色、不同课不同色、与输入顺序无关', () {
    final m = CourseColorAssigner.assign(['高数', '英语', '体育', '物理'], 10);
    expect(m.length, 4);
    // 4 门课 < 10 色 ⇒ 必不相同
    expect(m.values.toSet().length, 4);
    // 顺序不影响结果（保证「今天」与「周课表」颜色一致）
    final m2 = CourseColorAssigner.assign(['物理', '英语', '高数', '体育'], 10);
    expect(m2, equals(m));
  });

  test('CourseColorAssigner：课程数超过色数时仍能分配（允许复用）', () {
    final names = List.generate(15, (i) => '课程$i');
    final m = CourseColorAssigner.assign(names, 10);
    expect(m.length, 15);
    expect(m.values.every((v) => v >= 0 && v < 10), isTrue);
  });

  test('CoursePalette：24 个预设色（复刻原版 1.0.3）+ 10 个自动配色', () {
    expect(CoursePalette.presets.length, 24);
    expect(CoursePalette.autoAssign.length, 10);
  });
}
