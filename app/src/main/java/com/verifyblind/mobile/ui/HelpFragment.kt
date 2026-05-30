package com.verifyblind.mobile.ui

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.verifyblind.mobile.BuildConfig
import com.verifyblind.mobile.R
import com.verifyblind.mobile.databinding.FragmentHelpBinding

class HelpFragment : Fragment() {

    private var _binding: FragmentHelpBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHelpBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener { findNavController().navigateUp() }
        binding.tvVersion.text = "VERSION ${BuildConfig.VERSION_NAME.uppercase()}-STABLE"

        buildScreenGuides()
        buildFaq()
    }

    private fun buildScreenGuides() {
        val container = binding.screenGuidesContainer
        for (guide in HelpContent.getScreenGuides(requireContext())) {
            val item = layoutInflater.inflate(R.layout.item_screen_guide_accordion, container, false)

            // Icon and background
            val iconBg = item.findViewById<android.widget.FrameLayout>(R.id.screenGuideIconBg)
            iconBg.background = ContextCompat.getDrawable(requireContext(), guide.iconBgRes)

            val icon = item.findViewById<ImageView>(R.id.screenGuideIcon)
            icon.setImageResource(guide.iconRes)
            icon.setColorFilter(Color.parseColor(guide.iconTintHex))

            // Title
            item.findViewById<TextView>(R.id.screenGuideTitle).text = guide.title

            // Content texts
            item.findViewById<TextView>(R.id.tvPurposeText).text = guide.purpose
            item.findViewById<TextView>(R.id.tvStepsText).text = guide.steps
            item.findViewById<TextView>(R.id.tvTroubleText).text = guide.troubleshooting
            item.findViewById<TextView>(R.id.tvSecurityText).text = guide.securityNote

            // Toggle
            val header = item.findViewById<View>(R.id.screenGuideHeader)
            val content = item.findViewById<View>(R.id.screenGuideContent)
            val arrow = item.findViewById<ImageView>(R.id.screenGuideArrow)
            setupToggle(header, content, arrow)

            container.addView(item)
        }
    }

    private fun buildFaq() {
        val container = binding.faqContainer
        for (category in HelpContent.getFaqCategories(requireContext())) {
            // Category header
            val header = layoutInflater.inflate(R.layout.item_faq_category_header, container, false)
            val titleView = header.findViewById<TextView>(R.id.tvCategoryTitle)
            titleView.text = category.title
            titleView.setTextColor(Color.parseColor(category.titleColorHex))
            container.addView(header)

            // FAQ items
            for (faqItem in category.items) {
                val item = layoutInflater.inflate(R.layout.item_faq_accordion, container, false)
                item.findViewById<TextView>(R.id.faqQuestion).text = faqItem.question
                item.findViewById<TextView>(R.id.faqAnswer).text = faqItem.answer

                val root = item.findViewById<LinearLayout>(R.id.faqAccordionRoot)
                val answer = item.findViewById<TextView>(R.id.faqAnswer)
                val arrow = item.findViewById<ImageView>(R.id.faqArrow)
                setupToggle(root, answer, arrow)

                container.addView(item)
            }
        }
    }

    private fun setupToggle(headerView: View, contentView: View, arrowView: ImageView) {
        headerView.setOnClickListener {
            val isOpen = contentView.visibility == View.VISIBLE
            contentView.visibility = if (isOpen) View.GONE else View.VISIBLE
            arrowView.rotation = if (isOpen) 90f else 270f
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
