package com.verifyblind.mobile.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.verifyblind.mobile.databinding.BottomsheetDeleteConfirmBinding

class DeleteConfirmBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomsheetDeleteConfirmBinding? = null
    private val binding get() = _binding!!

    /** @param deleteHistory kullanıcı "geçmişi de sil" kutusunu işaretlediyse true. */
    var onConfirm: ((deleteHistory: Boolean) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomsheetDeleteConfirmBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnConfirmDelete.setOnClickListener {
            // Kutu durumunu dismiss'ten ÖNCE oku: dismiss view'i yok eder ve _binding null olur.
            val deleteHistory = binding.cbDeleteHistory.isChecked
            dismissAllowingStateLoss()
            onConfirm?.invoke(deleteHistory)
        }

        binding.btnCancelDelete.setOnClickListener {
            dismissAllowingStateLoss()
        }
    }

    override fun onStart() {
        super.onStart()
        val bottomSheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        bottomSheet?.let {
            it.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            it.requestLayout()
            val behavior = BottomSheetBehavior.from(it)
            behavior.skipCollapsed = true
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "DeleteConfirmBottomSheet"
    }
}
