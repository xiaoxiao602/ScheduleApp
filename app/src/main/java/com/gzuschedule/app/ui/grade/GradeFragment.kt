package com.gzuschedule.app.ui.grade

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.gzuschedule.app.data.local.AppDatabase
import com.gzuschedule.app.data.local.LocalGradeRepository
import com.gzuschedule.app.databinding.FragmentListBinding
import com.gzuschedule.app.domain.model.Grade
import com.gzuschedule.app.domain.model.Term
import kotlinx.coroutines.launch

/** 成绩页。数据来自本地库。 */
class GradeFragment : Fragment() {

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

        val adapter = GradeAdapter()
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter

        val db = AppDatabase.get(requireContext())
        val repo = LocalGradeRepository(db)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.observeGrades().collect { grades ->
                    binding.progress.visibility = View.GONE
                    val has = grades.isNotEmpty()
                    binding.recycler.visibility = if (has) View.VISIBLE else View.GONE
                    binding.emptyState.visibility = if (has) View.GONE else View.VISIBLE
                    adapter.submit(grades)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

/** 成绩列表适配器（用 RecyclerView.Adapter 直接实现，避免额外依赖）。 */
private class GradeAdapter : androidx.recyclerview.widget.RecyclerView.Adapter<GradeAdapter.VH>() {

    private val items = mutableListOf<Grade>()

    fun submit(list: List<Grade>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(val binding: com.gzuschedule.app.databinding.ItemGradeBinding) :
        androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(
            com.gzuschedule.app.databinding.ItemGradeBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
        )

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val g = items[position]
        holder.binding.tvTitle.text = g.courseName
        holder.binding.tvValue.text = g.score.ifBlank { "-" }

        val meta = buildList {
            if (g.category.isNotBlank()) add(g.category)
            if (g.credit.isNotBlank()) add("${g.credit} 学分")
            if (g.gpa.isNotBlank()) add("${g.gpa} 绩点")
            if (g.term.isNotBlank()) add(Term.displayOf(g.term))
        }.joinToString(" · ")
        holder.binding.tvSubtitle.text = meta
    }
}
