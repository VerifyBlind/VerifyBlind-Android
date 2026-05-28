package com.verifyblind.mobile.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.UnderlineSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.verifyblind.mobile.R
import com.verifyblind.mobile.api.PartnerInfoResponse
import com.verifyblind.mobile.api.RetrofitClient
import com.verifyblind.mobile.databinding.BottomsheetConsentBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ConsentBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomsheetConsentBinding? = null
    private val binding get() = _binding!!

    var info: PartnerInfoResponse? = null
    var logo: Bitmap? = null
    var onApprove: (() -> Unit)? = null
    var onReject: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomsheetConsentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val partnerInfo = info ?: return

        // Logo or initials
        val logoBitmap = logo
        if (logoBitmap != null) {
            binding.logoContainer.background = null
            binding.ivPartnerLogo.setImageBitmap(logoBitmap)
            binding.ivPartnerLogo.visibility = View.VISIBLE
            binding.tvPartnerInitials.visibility = View.GONE
        } else {
            val parts = partnerInfo.name.trim().split(" ")
            val initials = if (parts.size >= 2) {
                "${parts[0].take(1)}${parts[1].take(1)}".uppercase()
            } else {
                partnerInfo.name.take(2).uppercase()
            }
            binding.tvPartnerInitials.text = initials
            binding.tvPartnerInitials.visibility = View.VISIBLE
            binding.ivPartnerLogo.visibility = View.GONE
            binding.logoContainer.setBackgroundColor(Color.parseColor("#1287BE"))
        }

        // Partner name
        binding.tvPartnerName.text = partnerInfo.name

        // Build scope items (same logic as old ConsentDialogBuilder)
        val items = mutableListOf<String>()
        partnerInfo.validations?.let { v ->
            if (v.isJsonObject) {
                val obj = v.asJsonObject
                if (obj.has("user_id")) items.add(requireContext().getString(R.string.scope_user_id))
                if (obj.has("age")) items.add(requireContext().getString(R.string.scope_age, obj.get("age").asString))
            }
        }
        if (items.isEmpty()) items.add(requireContext().getString(R.string.consent_default_scope))

        items.forEach { s ->
            addScopeItem(title = s)
        }

        addPrivacyNoticeLink()

        // KVKK checkbox — daha önce onaylanmışsa otomatik tıkla
        val prefs = requireContext().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val previouslyAccepted = prefs.getBoolean("kvkk_consent_accepted", false)

        binding.cbKvkkConsent.isChecked = previouslyAccepted
        binding.btnApprove.isEnabled = previouslyAccepted
        binding.btnApprove.alpha = if (previouslyAccepted) 1.0f else 0.5f

        binding.cbKvkkConsent.setOnCheckedChangeListener { _, isChecked ->
            binding.btnApprove.isEnabled = isChecked
            binding.btnApprove.alpha = if (isChecked) 1.0f else 0.5f
        }

        binding.btnApprove.setOnClickListener {
            // İlk onayda kalıcı olarak kaydet
            prefs.edit().putBoolean("kvkk_consent_accepted", true).apply()
            dismiss()
            onApprove?.invoke()
        }

        binding.btnReject.setOnClickListener {
            dismiss()
            onReject?.invoke()
        }
    }

    private fun addScopeItem(title: String) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density

        val tv = TextView(ctx).apply {
            text = "• $title"
            textSize = 14f
            setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
            setTextColor(ContextCompat.getColor(ctx, R.color.sv_on_surface_variant))
        }
        binding.layoutScopeItems.addView(tv)
    }

    private fun addPrivacyNoticeLink() {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density

        val label = getString(R.string.read_privacy_notice)
        val spannable = SpannableString(label).apply {
            setSpan(UnderlineSpan(), 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        val tv = TextView(ctx).apply {
            text = spannable
            textSize = 13f
            setTextColor(ContextCompat.getColor(ctx, R.color.accent_blue))
            setPadding(0, (8 * dp).toInt(), 0, (4 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { fetchAndShowPrivacyNotice() }
        }
        binding.layoutScopeItems.addView(tv)
    }

    private fun fetchAndShowPrivacyNotice() {
        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.api.getPrivacyNotice(format = "text")
                }

                if (!isAdded) return@launch

                if (response.isSuccessful && response.body()?.has("text") == true) {
                    showPrivacyNoticeDialog(response.body()!!.get("text").asString)
                } else {
                    showPrivacyNoticeDialog(getString(R.string.privacy_notice_load_error))
                }
            } catch (e: Exception) {
                if (isAdded) {
                    showPrivacyNoticeDialog(getString(R.string.privacy_notice_load_failed))
                }
            }
        }
    }

    private fun showPrivacyNoticeDialog(content: String) {
        val ctx = context ?: return
        val dp = resources.displayMetrics.density

        val scrollView = ScrollView(ctx)
        val tv = TextView(ctx).apply {
            text = content
            textSize = 13f
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
            setTextColor(ContextCompat.getColor(ctx, R.color.sv_on_surface))
        }
        scrollView.addView(tv)

        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.privacy_notice_title))
            .setView(scrollView)
            .setPositiveButton(getString(R.string.btn_close), null)
            .show()
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
        const val TAG = "ConsentBottomSheet"
    }
}
