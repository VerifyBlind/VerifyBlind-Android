package com.verifyblind.mobile.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.verifyblind.mobile.R
import com.verifyblind.mobile.databinding.FragmentSecurityInfoBinding
import java.text.SimpleDateFormat
import java.util.*

class SecurityInfoFragment : Fragment() {

    private var _binding: FragmentSecurityInfoBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSecurityInfoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        binding.btnBack.setOnClickListener {
            findNavController().popBackStack()
        }

        setupUI()
    }

    private fun setupUI() {
        val prefs = requireContext().getSharedPreferences("VerifyBlind_Prefs", Context.MODE_PRIVATE)
        
        val pcr0 = prefs.getString("last_pcr0", "N/A")
        val isVerified = prefs.getBoolean("last_hardware_verified", false)
        val isMock = prefs.getBoolean("last_is_mock", false)
        val lastTime = prefs.getLong("last_attestation_time", 0)

        binding.tvPcr0Value.text = pcr0
        
        if (isVerified) {
            binding.tvVerifyStatus.text = getString(R.string.security_status_verified)
            binding.tvVerifyStatus.setTextColor(0xFF4CAF50.toInt())
            binding.tvGuardStatus.text = getString(R.string.security_guard_active)
            binding.tvGuardStatus.setTextColor(0xFF00BCD4.toInt())
        } else if (isMock) {
            binding.tvVerifyStatus.text = getString(R.string.security_status_mock)
            binding.tvVerifyStatus.setTextColor(0xFFFF9800.toInt())
            binding.tvGuardStatus.text = getString(R.string.security_guard_test)
            binding.tvGuardStatus.setTextColor(0xFFFF9800.toInt())
        } else {
            binding.tvVerifyStatus.text = getString(R.string.security_status_unverified)
            binding.tvVerifyStatus.setTextColor(0xFFF44336.toInt())
            binding.tvGuardStatus.text = getString(R.string.security_guard_risk)
            binding.tvGuardStatus.setTextColor(0xFFF44336.toInt())
        }

        if (lastTime > 0) {
            val sdf = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
            binding.tvLastVerify.text = "${getString(R.string.security_last_check)}${sdf.format(Date(lastTime))}"
        } else {
            binding.tvLastVerify.text = getString(R.string.security_not_checked)
        }

        binding.btnViewSource.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/VerifyBlind/VerifyBlind-Android"))
            startActivity(intent)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
