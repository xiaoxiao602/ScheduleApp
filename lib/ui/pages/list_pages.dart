import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../domain/models.dart' show Exam, Grade;
import '../providers.dart';
import '../theme.dart';

/// ============================================================
/// 成绩 / 考试列表页（一比一对应 fragment_list.xml + item_grade/item_exam）
/// ============================================================
///
/// 原版共用一个 fragment_list.xml（toolbar + recycler + emptyState + progress），
/// 成绩与考试只是「标题 + 数据 + item 布局」不同，故这里也共用一个骨架。

/// 成绩查询页。
class GradePage extends ConsumerWidget {
  const GradePage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final colors = AppColors.of(context);
    final async = ref.watch(gradesProvider);

    return Scaffold(
      backgroundColor: colors.surface,
      appBar: AppBar(title: const Text('成绩查询')),
      body: async.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(child: Text('读取失败：$e')),
        data: (grades) {
          if (grades.isEmpty) {
            return const _EmptyState(
              title: '还没有成绩数据',
              hint: '去设置页同步一次',
            );
          }
          final sorted = [...grades]
            ..sort((a, b) => b.term.compareTo(a.term));
          return ListView.builder(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 40),
            itemCount: sorted.length,
            itemBuilder: (context, i) => _GradeRow(grade: sorted[i]),
          );
        },
      ),
    );
  }
}

/// 考试安排页。
class ExamPage extends ConsumerWidget {
  const ExamPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final colors = AppColors.of(context);
    final async = ref.watch(examsProvider);

    return Scaffold(
      backgroundColor: colors.surface,
      appBar: AppBar(title: const Text('考试安排')),
      body: async.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(child: Text('读取失败：$e')),
        data: (exams) {
          if (exams.isEmpty) {
            return const _EmptyState(
              title: '还没有考试数据',
              hint: '去设置页同步一次',
            );
          }
          // 日期升序（无日期的排最后）
          final sorted = [...exams]..sort((a, b) {
              final da = a.date ?? '9';
              final db = b.date ?? '9';
              return da.compareTo(db);
            });
          return ListView.builder(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 40),
            itemCount: sorted.length,
            itemBuilder: (context, i) => _ExamRow(exam: sorted[i]),
          );
        },
      ),
    );
  }
}

/// 空状态（对应 fragment_list.xml 的 emptyState）。
class _EmptyState extends StatelessWidget {
  const _EmptyState({required this.title, required this.hint});

  final String title;
  final String hint;

  @override
  Widget build(BuildContext context) {
    final colors = AppColors.of(context);
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(Icons.inbox_outlined,
              size: 44, color: colors.onSurfaceVariant),
          const SizedBox(height: 12),
          Text(title,
              style: TextStyle(fontSize: 15, color: colors.onSurface)),
          const SizedBox(height: 4),
          Text(hint,
              style: TextStyle(fontSize: 12, color: colors.onSurfaceVariant)),
        ],
      ),
    );
  }
}

/// 成绩行（对应 item_grade.xml）：左标题+副标题，右侧分数。
class _GradeRow extends StatelessWidget {
  const _GradeRow({required this.grade});

  final Grade grade;

  @override
  Widget build(BuildContext context) {
    final colors = AppColors.of(context);

    // 副标题：必修 · 4.0 学分 · 3.5 绩点
    final parts = <String>[];
    if (grade.category.isNotEmpty) parts.add(grade.category);
    if (grade.credit.isNotEmpty) parts.add('${grade.credit} 学分');
    if (grade.gpa.isNotEmpty) parts.add('${grade.gpa} 绩点');

    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: colors.surfaceContainerHigh,
        borderRadius: BorderRadius.circular(AppTheme.cardRadius),
      ),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  grade.courseName,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 15,
                    fontWeight: FontWeight.w600,
                    color: colors.onSurface,
                  ),
                ),
                if (parts.isNotEmpty) ...[
                  const SizedBox(height: 3),
                  Text(
                    parts.join(' · '),
                    style: TextStyle(
                        fontSize: 12, color: colors.onSurfaceVariant),
                  ),
                ],
              ],
            ),
          ),
          const SizedBox(width: 10),
          Text(
            grade.score,
            style: TextStyle(
              fontSize: 20,
              fontWeight: FontWeight.w700,
              color: colors.primary,
            ),
          ),
        ],
      ),
    );
  }
}

/// 考试行（对应 item_exam_home.xml）。
class _ExamRow extends StatelessWidget {
  const _ExamRow({required this.exam});

  final Exam exam;

  @override
  Widget build(BuildContext context) {
    final colors = AppColors.of(context);

    // 副标题：1月5日 09:00 · A101
    final meta = <String>[];
    if ((exam.date ?? '').isNotEmpty) meta.add(exam.date!);
    if ((exam.startTime ?? '').isNotEmpty) meta.add(exam.startTime!);
    if (exam.location.isNotEmpty) meta.add(exam.location);

    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: colors.surfaceContainerHigh,
        borderRadius: BorderRadius.circular(AppTheme.cardRadius),
        border: Border(left: BorderSide(color: colors.tertiary, width: 5)),
      ),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  exam.courseName,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 15,
                    fontWeight: FontWeight.w600,
                    color: colors.onSurface,
                  ),
                ),
                const SizedBox(height: 3),
                Text(
                  [
                    ...meta,
                    if (exam.seat.isNotEmpty) '座 ${exam.seat}',
                  ].join(' · '),
                  style: TextStyle(fontSize: 12, color: colors.onSurfaceVariant),
                ),
              ],
            ),
          ),
          if (exam.format.isNotEmpty)
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
              decoration: BoxDecoration(
                color: colors.tertiaryContainer,
                borderRadius: BorderRadius.circular(8),
              ),
              child: Text(
                exam.format,
                style: TextStyle(
                    fontSize: 11, color: colors.onTertiaryContainer),
              ),
            ),
        ],
      ),
    );
  }
}