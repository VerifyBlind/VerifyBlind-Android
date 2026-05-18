package com.verifyblind.mobile.ui

import android.content.Context
import android.os.Bundle
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.verifyblind.mobile.api.ChatEmailCapture
import com.verifyblind.mobile.api.ChatMessageDto
import com.verifyblind.mobile.api.ChatRequest
import com.verifyblind.mobile.api.RetrofitClient
import com.verifyblind.mobile.databinding.FragmentChatbotBinding
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Destek chatbot ekranı. Stateless backend; konuşma listesini cihazda tutuyoruz.
 * Fallback durumunda input bir e-posta girişine dönüşür → submit ile ticket oluşur.
 */
class ChatbotFragment : Fragment() {

    private var _binding: FragmentChatbotBinding? = null
    private val binding get() = _binding!!

    private val items = mutableListOf<ChatTurn>()
    private lateinit var adapter: ChatbotMessageAdapter

    /** Bot fallback verdiğinde ekran "e-posta modu"na geçer. */
    private var requiresEmail = false
    private var originalQuestion: String = ""

    private val gson = Gson()
    private val historyType = object : TypeToken<List<ChatTurn>>() {}.type

    private val language: String by lazy {
        val tag = Locale.getDefault().language.lowercase()
        if (tag.startsWith("en")) "en" else "tr"
    }

    private val welcomeMessage: String
        get() = if (language == "en")
            "Hello! I can answer questions about VerifyBlind. How can I help?"
        else
            "Merhaba! VerifyBlind hakkındaki sorularınızı yanıtlayabilirim. Nasıl yardımcı olabilirim?"

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatbotBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener { findNavController().navigateUp() }
        binding.btnNewChat.setOnClickListener { resetConversation() }

        // Persist edilmiş geçmişi geri yükle (process death / fragment recreate sonrası).
        if (items.isEmpty()) {
            val restored = loadPersistedHistory()
            if (restored.isNotEmpty()) {
                items.addAll(restored)
            } else {
                items.add(ChatTurn("assistant", welcomeMessage))
            }
        }

        adapter = ChatbotMessageAdapter(items)
        binding.rvMessages.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        binding.rvMessages.adapter = adapter

        binding.etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCurrentInput()
                true
            } else false
        }

        binding.btnSend.setOnClickListener { sendCurrentInput() }
        updateHintForMode()
    }

    private fun sendCurrentInput() {
        val text = binding.etInput.text.toString().trim()
        if (text.isEmpty()) return
        if (binding.btnSend.isEnabled.not()) return

        if (requiresEmail) {
            submitEmailCapture(text)
        } else {
            sendChat(text)
        }
    }

    private fun sendChat(userText: String) {
        adapter.append(ChatTurn("user", userText))
        persistHistory()
        scrollToBottom()
        binding.etInput.setText("")
        setBusy(true)

        val historyTurns = items.takeLast(20).map {
            ChatMessageDto(role = it.role, content = it.content)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val res = RetrofitClient.api.chatbotChat(
                    ChatRequest(
                        messages = historyTurns,
                        turnstileToken = null,           // mobile: Turnstile gerekmiyor
                        source = "mobile",
                        language = language
                    )
                )
                val body = res.body()
                if (!res.isSuccessful || body == null) {
                    adapter.append(ChatTurn("assistant", networkErrorMessage()))
                } else {
                    adapter.append(ChatTurn("assistant", body.message))
                    if (body.requiresEmail) {
                        requiresEmail = true
                        originalQuestion = userText
                        updateHintForMode()
                    }
                }
            } catch (t: Throwable) {
                adapter.append(ChatTurn("assistant", networkErrorMessage()))
            } finally {
                persistHistory()
                setBusy(false)
                scrollToBottom()
            }
        }
    }

    private fun submitEmailCapture(email: String) {
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            Toast.makeText(
                requireContext(),
                if (language == "en") "Please enter a valid email." else "Lütfen geçerli bir e-posta girin.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        adapter.append(ChatTurn("user", email))
        persistHistory()
        scrollToBottom()
        binding.etInput.setText("")
        setBusy(true)

        val historyTurns = items.takeLast(20).map {
            ChatMessageDto(role = it.role, content = it.content)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val res = RetrofitClient.api.chatbotChat(
                    ChatRequest(
                        messages = historyTurns,
                        source = "mobile",
                        language = language,
                        emailCapture = ChatEmailCapture(
                            email = email,
                            originalQuestion = originalQuestion
                        )
                    )
                )
                val body = res.body()
                if (!res.isSuccessful || body == null) {
                    adapter.append(ChatTurn("assistant", networkErrorMessage()))
                } else {
                    adapter.append(ChatTurn("assistant", body.message))
                    requiresEmail = false
                    originalQuestion = ""
                    updateHintForMode()
                }
            } catch (t: Throwable) {
                adapter.append(ChatTurn("assistant", networkErrorMessage()))
            } finally {
                persistHistory()
                setBusy(false)
                scrollToBottom()
            }
        }
    }

    private fun resetConversation() {
        items.clear()
        adapter.replaceAll(items)
        requiresEmail = false
        originalQuestion = ""
        adapter.append(ChatTurn("assistant", welcomeMessage))
        clearPersistedHistory()
        updateHintForMode()
        scrollToBottom()
    }

    // ── Persistence ──────────────────────────────────────────────────────────
    // Konuşma geçmişi cihazda SharedPreferences'a JSON olarak yazılır. Fragment
    // recreate, rotation, back-stack pop veya process death sonrası geri yüklenir.
    // Landing widget'ın localStorage ve iOS'un UserDefaults pattern'iyle paralel.

    private fun prefs() =
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun persistHistory() {
        try {
            val trimmed = items.takeLast(MAX_PERSISTED).toList()
            val json = gson.toJson(trimmed)
            prefs().edit().putString(KEY_HISTORY, json).apply()
        } catch (_: Throwable) {
            // Persistence non-critical — UI akışını blok etme.
        }
    }

    private fun loadPersistedHistory(): List<ChatTurn> {
        return try {
            val json = prefs().getString(KEY_HISTORY, null) ?: return emptyList()
            val restored: List<ChatTurn>? = gson.fromJson(json, historyType)
            restored?.takeLast(MAX_PERSISTED) ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun clearPersistedHistory() {
        try {
            prefs().edit().remove(KEY_HISTORY).apply()
        } catch (_: Throwable) { /* no-op */ }
    }

    private companion object {
        const val PREFS_NAME = "VerifyBlind_Prefs"
        const val KEY_HISTORY = "chatbot_history_v1"
        // landing/iOS ile aynı pencere: MAX_HISTORY * 2 + 1 = 21
        const val MAX_PERSISTED = 21
    }

    private fun updateHintForMode() {
        val hint = if (requiresEmail) {
            if (language == "en") "Your email address..." else "E-posta adresiniz..."
        } else {
            if (language == "en") "Ask about VerifyBlind..." else "VerifyBlind hakkında bir şey sorun..."
        }
        binding.etInput.hint = hint
    }

    private fun setBusy(busy: Boolean) {
        binding.btnSend.isEnabled = !busy
        binding.etInput.isEnabled = !busy
        binding.progressTyping.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun scrollToBottom() {
        binding.rvMessages.post {
            val last = adapter.itemCount - 1
            if (last >= 0) binding.rvMessages.scrollToPosition(last)
        }
    }

    private fun networkErrorMessage(): String =
        if (language == "en") "Network issue. Please try again."
        else "Bağlantı sorunu. Lütfen tekrar deneyin."

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
