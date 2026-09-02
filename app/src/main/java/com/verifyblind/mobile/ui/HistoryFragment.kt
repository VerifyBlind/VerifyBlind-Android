package com.verifyblind.mobile.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.verifyblind.mobile.MainActivity
import com.verifyblind.mobile.R
import com.verifyblind.mobile.data.AppDatabase
import com.verifyblind.mobile.data.HistoryEntity
import com.verifyblind.mobile.data.HistoryRepository
import com.verifyblind.mobile.databinding.FragmentHistoryBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.verifyblind.mobile.util.BiometricHelper
import androidx.navigation.fragment.findNavController

class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!

    private lateinit var repository: HistoryRepository
    private val historyAdapter = HistoryAdapter() 

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize Repository (Ideally via DI, but manual for now)
        val db = AppDatabase.getDatabase(requireContext())
        val dao = db.historyDao()
        repository = HistoryRepository(dao)

        com.verifyblind.mobile.data.PartnerManager.init(requireContext())

        setupViews()
        setupRecyclerView()
        
        // Observe Partners — view ömrüne bağlı: onDestroyView'da iptal olur.
        viewLifecycleOwner.lifecycleScope.launch {
            com.verifyblind.mobile.data.PartnerManager.partners.collectLatest { map ->
                historyAdapter.updatePartners(map)
            }
        }
        
        observeHistory()
    }
    
    private fun setupViews() {
        // Back
        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }

        // Yedekle / Geri Yükle ekranı
        binding.btnBackupAction.setOnClickListener {
            findNavController().navigate(com.verifyblind.mobile.R.id.nav_backup_settings)
        }

        // Initial Auth check
        if (binding.layoutAuthLock.visibility == View.VISIBLE) {
            performInitialAuth()
        }
    }

    private fun performInitialAuth() {
        // NOT: Burada scope'u view'a bağlamak YETMEZ. BiometricHelper.authenticate suspend değil;
        // BiometricPrompt callback'lerini framework'ün executor'ından çağırır, yani coroutine iptal
        // edilse bile tetiklenirler. Kullanıcı prompt açıkken geri giderse view yıkılır, _binding
        // null olur ve callback NullPointerException atardı (Sentry 136420671). Tek doğru koruma
        // callback'in İÇİNDE null kontrolü.
        viewLifecycleOwner.lifecycleScope.launch {
            BiometricHelper.authenticate(
                activity = requireActivity() as androidx.fragment.app.FragmentActivity,
                onSuccess = {
                    _binding?.layoutAuthLock?.visibility = View.GONE
                },
                onError = { msg ->
                    android.util.Log.w("HistoryFragment", "Kimlik doğrulama başarısız: $msg")
                    _binding?.let { b ->
                        b.layoutAuthLock.visibility = View.VISIBLE
                        b.layoutAuthLock.setOnClickListener {
                            performInitialAuth() // Retry auth on click
                        }
                    }
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        checkBackupBanner()
    }

    private fun checkBackupBanner() {
        // Manuel Yedekle/Geri Yükle modelinde banner her zaman görünür bir giriş noktasıdır.
        binding.cardBackup.visibility = View.VISIBLE
    }

    private fun setupRecyclerView() {
        binding.rvHistory.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = historyAdapter
            addItemDecoration(DividerItemDecoration(context, DividerItemDecoration.VERTICAL))
        }
        
        // Swipe to Delete (Left) or Cancel (Right)
        val swipeHandler = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                if (position != RecyclerView.NO_POSITION && position < historyAdapter.currentList.size) {
                    val item = historyAdapter.currentList[position]
                    
                    if (direction == ItemTouchHelper.LEFT) {
                        // Delete Confirmation
                        androidx.appcompat.app.AlertDialog.Builder(requireContext())
                            .setTitle(getString(R.string.delete_record_title))
                            .setMessage(getString(R.string.delete_record_message))
                            .setPositiveButton(getString(R.string.btn_delete_confirm)) { _, _ ->
                                lifecycleScope.launch {
                                    repository.deleteById(item.id)
                                }
                            }
                            .setNegativeButton(getString(R.string.btn_cancel_upper)) { _, _ ->
                                historyAdapter.notifyItemChanged(position)
                            }
                            .setCancelable(false)
                            .show()
                    } else {
                        // RIGHT SWIPE: Unified revoke — API handles type detection
                        val isShared = item.actionType == com.verifyblind.mobile.data.HistoryAction.SHARED_IDENTITY
                        val isRegistration = item.actionType == com.verifyblind.mobile.data.HistoryAction.REGISTRATION

                        // Already revoked/withdrawn → ignore swipe
                        if (item.revokeTime != null) {
                            historyAdapter.notifyItemChanged(position)
                        } else if ((isShared || isRegistration) && !item.nonce.isNullOrEmpty()) {
                            val title = if (isShared) getString(R.string.revoke_verification_title) else getString(R.string.revoke_registration_title)
                            val message = if (isShared) getString(R.string.revoke_verification_message) else getString(R.string.revoke_registration_message)
                            val confirmBtn = if (isShared) getString(R.string.btn_revoke) else getString(R.string.btn_withdraw)

                            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                                .setTitle(title)
                                .setMessage(message)
                                .setPositiveButton(confirmBtn) { _, _ ->
                                    // Registration → parmak izi doğrula (kart silineceği için)
                                    val proceed = {
                                        lifecycleScope.launch {
                                            try {
                                                // INTEGRITY_CHECK_ENABLED açıkken backend integrity token'sız revoke'u
                                                // 400 ile reddeder (KVKK geri çekme kırılır). Login deseniyle aynı:
                                                // requestHash = nonce.
                                                val integrityToken = try {
                                                    com.verifyblind.mobile.util.IntegrityManagerHelper
                                                        .requestIntegrityToken(requireContext(), item.nonce) ?: ""
                                                } catch (e: Exception) {
                                                    android.util.Log.w("HistoryFragment", "Play Integrity token alınamadı (revoke): ${e.message}")
                                                    ""
                                                }
                                                val response = com.verifyblind.mobile.api.RetrofitClient.api.revoke(
                                                    com.verifyblind.mobile.api.RevokeRequest(nonce = item.nonce, integrityToken = integrityToken)
                                                )
                                            if (response.isSuccessful) {
                                                val now = System.currentTimeMillis()
                                                repository.updateRevokeTime(item.id, now)

                                                if (isRegistration) {
                                                    // Cüzdandan kartı kaldır (geçmiş kaydı silinmez — revokeTime ile "Rıza Geri Çekildi" gösterilir)
                                                    (requireActivity() as? MainActivity)?.clearCard()
                                                }

                                                val toastMsg = if (isShared)
                                                    getString(R.string.revoke_shared_success)
                                                else
                                                    getString(R.string.revoke_registration_success)

                                                android.widget.Toast.makeText(
                                                    requireContext(), toastMsg, android.widget.Toast.LENGTH_LONG
                                                ).show()
                                            } else {
                                                val raw = try { response.errorBody()?.string() ?: "" } catch (_: Exception) { "" }
                                                val code = try { org.json.JSONObject(raw).optString("code", "") } catch (_: Exception) { "" }

                                                // Eski kayıtlar (sunucu tarafında consent kaydı olmayan) için REVOKE_NOT_FOUND
                                                // döner. Sunucuda silinecek kayıt yoktur; sadece yerel temizlik yapılır.
                                                if (isRegistration && response.code() == 404 && code == "REVOKE_NOT_FOUND") {
                                                    val now = System.currentTimeMillis()
                                                    repository.updateRevokeTime(item.id, now)
                                                    (requireActivity() as? MainActivity)?.clearCard()
                                                    android.widget.Toast.makeText(
                                                        requireContext(), getString(R.string.revoke_registration_success), android.widget.Toast.LENGTH_LONG
                                                    ).show()
                                                } else {
                                                    // 5xx = sunucu/altyapı tarafı → nazik, spesifik mesaj + başlık (genel "işlem başarısız" değil);
                                                    // diğer (4xx) için sunucunun döndürdüğü 'error' alanı daha açıklayıcı.
                                                    val serverMsg = com.verifyblind.mobile.util.ServerErrorMessages.serverErrorOrNull(requireContext(), response.code())
                                                    val title = if (serverMsg != null) getString(R.string.error_server_unavailable_title)
                                                                else getString(R.string.operation_failed_title)
                                                    val errorMsg = serverMsg
                                                        ?: try { org.json.JSONObject(raw).optString("error", getString(R.string.error_unknown)) } catch (_: Exception) { getString(R.string.revoke_failed_message) }
                                                    (activity as? MainActivity)?.showMessage(title, errorMsg)
                                                    historyAdapter.notifyItemChanged(position)
                                                }
                                            }
                                        } catch (e: Exception) {
                                            // HTTP cevabı YOK = bağlantı sorunu → kanonik mesaj (iOS .network ile aynı).
                                            (activity as? MainActivity)?.showMessage(getString(R.string.connection_error_title), com.verifyblind.mobile.util.ServerErrorMessages.connectionFailed(requireContext()))
                                            historyAdapter.notifyItemChanged(position)
                                        }
                                    }
                                    }

                                    if (isRegistration) {
                                        BiometricHelper.authenticate(
                                            activity = requireActivity() as androidx.fragment.app.FragmentActivity,
                                            onSuccess = { proceed() },
                                            onError = {
                                                historyAdapter.notifyItemChanged(position)
                                            }
                                        )
                                    } else {
                                        proceed()
                                    }
                                }
                                .setNegativeButton("İPTAL") { _, _ ->
                                    historyAdapter.notifyItemChanged(position)
                                }
                                .setCancelable(false)
                                .show()
                        } else {
                            // Other action types, just reset
                            historyAdapter.notifyItemChanged(position)
                        }
                    }
                }
            }
        }
        ItemTouchHelper(swipeHandler).attachToRecyclerView(binding.rvHistory)
    }

    private fun observeHistory() {
        // viewLifecycleOwner: akış onDestroyView'da İPTAL edilir. Fragment'ın lifecycleScope'u view'dan
        // uzun yaşadığı için, aşağıdaki withContext(IO) askısından sonra view yıkılmış olabiliyordu ve
        // binding'e dokunan ilk satır NullPointerException atıyordu (Sentry 136420687).
        viewLifecycleOwner.lifecycleScope.launch {
            // Use raw (unencrypted) flow — DB already orders by timestamp DESC
            repository.allHistoryRaw.collectLatest { rawList ->
                android.util.Log.d("VerifyBlind_History", "Ham liste alındı. Adet: ${rawList.size}")

                // Filter: only show items belonging to the currently registered card.
                // Items with an empty cardId (generic events) are always shown.
                val currentCardId = com.verifyblind.mobile.util.SecureStore.getCardId(requireContext())
                val filteredList = rawList.filter { item ->
                    item.cardId.isEmpty() || item.cardId == (currentCardId ?: "")
                }

                android.util.Log.d("VerifyBlind_History", "Filtrelenmiş liste. Adet: ${filteredList.size}, currentCardId: $currentCardId")

                if (filteredList.isEmpty()) {
                    binding.layoutEmpty.visibility = View.VISIBLE
                    binding.rvHistory.visibility = View.GONE
                    historyAdapter.setLoadingState(false)
                    historyAdapter.submitList(emptyList())
                    return@collectLatest
                }

                binding.layoutEmpty.visibility = View.GONE
                binding.rvHistory.visibility = View.VISIBLE

                // Reset adapter and show loading footer
                historyAdapter.submitList(emptyList())
                historyAdapter.setLoadingState(true)

                // Decrypt items one-by-one on IO, add to list progressively (newest first)
                val decryptedItems = mutableListOf<HistoryEntity>()
                for (item in filteredList) {
                    val decrypted = withContext(Dispatchers.IO) {
                        repository.decryptItemPublic(item)
                    }
                    decryptedItems.add(decrypted)
                    historyAdapter.submitList(decryptedItems.toList())
                }

                // Birden fazla farklı cihaz varsa satırlarda cihaz adını göster (yoksa gizle).
                historyAdapter.setShowDevice(HistoryDisplay.shouldShowDevice(decryptedItems))

                // All done — remove loading footer
                historyAdapter.setLoadingState(false)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
