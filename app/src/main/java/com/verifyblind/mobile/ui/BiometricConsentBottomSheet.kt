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

    // Demo akışı: checkbox otomatik işaretlenir ve 3sn sonra "Onayla" otomatik tıklanır.
    // Normal kart-ekleme akışında DAİMA false → ne ön-seçim ne oto-tıklama olur.
    var demoAutoApprove: Boolean = false

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

        // dismissAllowingStateLoss: dismiss() bir fragment transaction commit'idir ve
        // onSaveInstanceState SONRASI (ör. uygulama arka plana atılınca) IllegalStateException
        // fırlatır. Dialog kapatmada saklanacak durum yok → state kaybı zararsız, doğru API bu.
        binding.btnApprove.setOnClickListener {
            dismissAllowingStateLoss()
            onApprove?.invoke()
        }

        binding.btnReject.setOnClickListener {
            dismissAllowingStateLoss()
            onReject?.invoke()
        }

        if (demoAutoApprove) {
            binding.cbBiometricConsent.isChecked = true
            binding.root.postDelayed({
                // isResumed şart: uygulama 3sn gecikme boyunca arka plana atıldıysa oto-onay
                // TETİKLENMEMELİ — hem raporlanan çökmenin tetikleyicisi buydu, hem de arka planda
                // demo akışını ilerletmek (onApprove) mantıksız. isAdded/_binding tek başına yetmez:
                // view yıkılmasa da state kaydedilmiş olabilir.
                if (isResumed && _binding != null) binding.btnApprove.performClick()
            }, 3000)
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog {
        return super.onCreateDialog(savedInstanceState).also {
            it.setCanceledOnTouchOutside(false)
            // `setCancelable(false)` burada ETKİSİZ — DialogFragment kendi
            // mCancelable'ını sonradan dialog'a yazıyor (bkz. ConsentBottomSheet).
            // Geri tuşu bu yüzden engellenmiyor, REDDETME'ye bağlanıyor.
        }
    }

    /**
     * Geri tuşu = REDDET. Sayfa geri tuşuyla zaten kapanıyordu; `onReject`
     * çağrılmayınca akış yarıda kalıyor ve çağıran taraf ne onay ne ret alıyordu.
     */
    override fun onCancel(dialog: android.content.DialogInterface) {
        super.onCancel(dialog)
        onReject?.invoke()
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
