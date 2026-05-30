package com.verifyblind.mobile.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.verifyblind.mobile.databinding.BottomsheetBiometricConsentBinding

class BiometricConsentBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomsheetBiometricConsentBinding? = null
    private val binding get() = _binding!!

    var onApprove: (() -> Unit)? = null
    var onReject: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomsheetBiometricConsentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnApprove.isEnabled = false
        binding.btnApprove.alpha = 0.5f

        binding.cbBiometricConsent.setOnCheckedChangeListener { _, isChecked ->
            binding.btnApprove.isEnabled = isChecked
            binding.btnApprove.alpha = if (isChecked) 1.0f else 0.5f
        }

        binding.btnApprove.setOnClickListener {
            dismiss()
            onApprove?.invoke()
        }

        binding.btnReject.setOnClickListener {
            dismiss()
            onReject?.invoke()
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog {
        return super.onCreateDialog(savedInstanceState).also {
            it.setCancelable(false)
            it.setCanceledOnTouchOutside(false)
        }
    }

    override fun onStart() {
        super.onStart()
        val bottomSheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        bottomSheet?.let {
            val behavior = BottomSheetBehavior.from(it)
            behavior.skipCollapsed = true
            behavior.isDraggable = false
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "BiometricConsentBottomSheet"
    }
}
