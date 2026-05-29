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
        
        // Observe Partners
        lifecycleScope.launch {
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

        // Backup Banner Button
        binding.btnBackupAction.setOnClickListener {
            val status = com.verifyblind.mobile.backup.CloudBackupManager.getStatus(requireContext())
            if (status.isConnected) {
                (requireActivity() as? MainActivity)?.startSync()
            } else {
                // Navigate to Settings and trigger Cloud Backup selection automatically
                val bundle = android.os.Bundle().apply {
                    putBoolean("auto_open_backup", true)
                }
                
                // Navigate to Settings but pop the History screen from backstack 
                // so it doesn't get stuck in a redirect loop
                val navOptions = androidx.navigation.NavOptions.Builder()
                    .setPopUpTo(com.verifyblind.mobile.R.id.nav_history, true)
                    .build()
                
                findNavController().navigate(com.verifyblind.mobile.R.id.nav_settings, bundle, navOptions)
            }
        }
        
        // Initial Auth check
        if (binding.layoutAuthLock.visibility == View.VISIBLE) {
            performInitialAuth()
        }
    }

    private fun performInitialAuth() {
        lifecycleScope.launch {
            BiometricHelper.authenticate(
                activity = requireActivity() as androidx.fragment.app.FragmentActivity,
                onSuccess = {
                    binding.layoutAuthLock.visibility = View.GONE
                    (requireActivity() as? MainActivity)?.startSync()
                },
                onError = { msg ->
                    android.util.Log.w("HistoryFragment", "Kimlik doğrulama başarısız: $msg")
                    binding.layoutAuthLock.visibility = View.VISIBLE
                    binding.layoutAuthLock.setOnClickListener {
                        performInitialAuth() // Retry auth on click
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
        val status = com.verifyblind.mobile.backup.CloudBackupManager.getStatus(requireContext())
        if (status.isConnected) {
            binding.cardBackup.visibility = View.GONE
            binding.tvHint.setPadding(0, 16, 0, 0) // Adjust hint spacing
        } else {
            binding.cardBackup.visibility = View.VISIBLE
        }
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
                            .setTitle("Kaydı Sil")
                            .setMessage("Bu işlem kaydını silmek istediğinize emin misiniz?")
                            .setPositiveButton("SİL") { _, _ ->
                                lifecycleScope.launch {
                                    repository.deleteById(item.id)
                                    // Trigger Auto-Backup after deletion if connected
                                    (requireActivity() as? MainActivity)?.let { 
                                        it.lifecycleScope.launch { 
                                            // Delay slightly to allow DB to update before snapshot
                                            kotlinx.coroutines.delay(100)
                                            it.startSync()
                                        }
                                    }
                                }
                            }
                            .setNegativeButton("İPTAL") { _, _ ->
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
                            val title = if (isShared) "Doğrulamayı Geri Al" else "Kart Kaydı Rızasını Geri Çek"
                            val message = if (isShared)
                                "Bu doğrulamayla ilgili hizmet sağlayıcıdaki kayıtların silinmesi talep edilecektir.\n\n" +
                                "Ayrıca KVKK kapsamındaki rızanız da geri çekilecektir.\n\n" +
                                "Bu işlem geri alınamaz. Devam etmek istiyor musunuz?"
                            else
                                "Kimlik kartı ekleme işlemi için verdiğiniz KVKK rızası geri çekilecektir.\n\n" +
                                "Bu işlem sonrası kart kaydı uygulamadan silinecektir.\n\n" +
                                "Devam etmek istiyor musunuz?"
                            val confirmBtn = if (isShared) "GERİ AL" else "GERİ ÇEK"

                            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                                .setTitle(title)
                                .setMessage(message)
                                .setPositiveButton(confirmBtn) { _, _ ->
                                    // Registration → parmak izi doğrula (kart silineceği için)
                                    val proceed = {
                                        lifecycleScope.launch {
                                            try {
                                                val response = com.verifyblind.mobile.api.RetrofitClient.api.revoke(
                                                    com.verifyblind.mobile.api.RevokeRequest(nonce = item.nonce)
                                                )
                                            if (response.isSuccessful) {
                                                val now = System.currentTimeMillis()
                                                repository.updateRevokeTime(item.id, now)

                                                if (isRegistration) {
                                                    // Cüzdandan kartı kaldır (geçmiş kaydı silinmez — revokeTime ile "Rıza Geri Çekildi" gösterilir)
                                                    (requireActivity() as? MainActivity)?.clearCard()
                                                }

                                                val toastMsg = if (isShared)
                                                    "Kimlik paylaşımı ve rıza başarıyla geri alındı."
                                                else
                                                    "Rıza geri çekildi ve kimlik kartı kaldırıldı."

                                                android.widget.Toast.makeText(
                                                    requireContext(), toastMsg, android.widget.Toast.LENGTH_LONG
                                                ).show()

                                                (requireActivity() as? MainActivity)?.startSync()
                                            } else {
                                                val errorBody = try {
                                                    val raw = response.errorBody()?.string() ?: ""
                                                    val json = org.json.JSONObject(raw)
                                                    json.optString("error", "Bilinmeyen hata")
                                                } catch (_: Exception) { "Geri çekme işlemi başarısız. Lütfen daha sonra tekrar deneyin." }
                                                (activity as? MainActivity)?.showMessage("İşlem Başarısız", errorBody)
                                                historyAdapter.notifyItemChanged(position)
                                            }
                                        } catch (e: Exception) {
                                            (activity as? MainActivity)?.showMessage("Bağlantı Hatası", "Sunucuya erişilemiyor: ${e.message}")
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
        lifecycleScope.launch {
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
