package com.gzuschedule.app.ui.exam

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.gzuschedule.app.data.local.AppDatabase
import com.gzuschedule.app.data.local.LocalExamRepository
import com.gzuschedule.app.databinding.FragmentListBinding
import com.gzuschedule.app.databinding.ItemGradeBinding
import com.gzuschedule.app.domain.model.Exam
import kotlinx.coroutines.launch

/** 考试页。数据来自本地库。 */
class ExamFragment : Fragment() {

    private var _binding: FragmentListBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.title = "考试"

        val adapter = ExamAdapter()
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter

        val db = AppDatabase.get(requireContext())
        val repo = LocalExamRepository(db)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.observeExams().collect { exams ->
                    binding.progress.visibility = View.GONE
                    val has = exams.isNotEmpty()
                    binding.recycler.visibility = if (has) View.VISIBLE else View.GONE
                    binding.emptyState.visibility = if (has) View.GONE else View.VISIBLE
                    adapter.submit(exams)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

private class ExamAdapter : androidx.recyclerview.widget.RecyclerView.Adapter<ExamAdapter.VH>() {

    private val items = mutableListOf<Exam>()

    fun submit(list: List<Exam>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(val binding: ItemGradeBinding) :
        androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemGradeBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = items[position]
        holder.binding.tvTitle.text = e.courseName

        // 右侧显示日期（月/日），更醒目
        holder.binding.tvValue.text = e.date?.let { "${it.monthValue}/${it.dayOfMonth}" } ?: "-"

        val meta = buildList {
            val time = when {
                e.startTime != null && e.endTime != null ->
                    "${e.startTime} - ${e.endTime}"
                e.startTime != null -> e.startTime.toString()
                else -> ""
            }
            if (time.isNotBlank()) add(time)
            if (e.location.isNotBlank()) add(e.location)
            if (e.seat.isNotBlank()) add("座位 ${e.seat}")
            if (e.format.isNotBlank()) add(e.format)
        }.joinToString(" · ")
        holder.binding.tvSubtitle.text = meta
    }
}
