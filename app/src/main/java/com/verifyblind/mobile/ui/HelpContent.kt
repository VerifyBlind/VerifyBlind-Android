package com.verifyblind.mobile.ui

import android.content.Context
import com.verifyblind.mobile.R

data class ScreenGuide(
    val title: String,
    val iconRes: Int,
    val iconBgRes: Int,
    val iconTintHex: String,
    val purpose: String,
    val steps: String,
    val troubleshooting: String,
    val securityNote: String
)

data class FaqCategory(
    val title: String,
    val titleColorHex: String,
    val items: List<FaqItem>
)

data class FaqItem(
    val question: String,
    val answer: String
)

object HelpContent {

    fun getScreenGuides(context: Context): List<ScreenGuide> = listOf(

        ScreenGuide(
            title = context.getString(R.string.guide_wallet_title),
            iconRes = R.drawable.ic_wallet,
            iconBgRes = R.drawable.bg_step_icon_blue,
            iconTintHex = "#0060AA",
            purpose = context.getString(R.string.guide_wallet_purpose),
            steps = context.getString(R.string.guide_wallet_steps),
            troubleshooting = context.getString(R.string.guide_wallet_troubleshooting),
            securityNote = context.getString(R.string.guide_wallet_security_note)
        ),

        ScreenGuide(
            title = context.getString(R.string.guide_add_card_title),
            iconRes = R.drawable.ic_add_card,
            iconBgRes = R.drawable.bg_step_icon_teal,
            iconTintHex = "#00897B",
            purpose = context.getString(R.string.guide_add_card_purpose),
            steps = context.getString(R.string.guide_add_card_steps),
            troubleshooting = context.getString(R.string.guide_add_card_troubleshooting),
            securityNote = context.getString(R.string.guide_add_card_security_note)
        ),

        ScreenGuide(
            title = context.getString(R.string.guide_nfc_title),
            iconRes = R.drawable.ic_nfc_waves,
            iconBgRes = R.drawable.bg_step_icon_teal,
            iconTintHex = "#00897B",
            purpose = context.getString(R.string.guide_nfc_purpose),
            steps = context.getString(R.string.guide_nfc_steps),
            troubleshooting = context.getString(R.string.guide_nfc_troubleshooting),
            securityNote = context.getString(R.string.guide_nfc_security_note)
        ),

        ScreenGuide(
            title = context.getString(R.string.guide_liveness_title),
            iconRes = R.drawable.ic_face_smile,
            iconBgRes = R.drawable.bg_step_icon_green,
            iconTintHex = "#2E7D32",
            purpose = context.getString(R.string.guide_liveness_purpose),
            steps = context.getString(R.string.guide_liveness_steps),
            troubleshooting = context.getString(R.string.guide_liveness_troubleshooting),
            securityNote = context.getString(R.string.guide_liveness_security_note)
        ),

        ScreenGuide(
            title = context.getString(R.string.guide_qr_login_title),
            iconRes = R.drawable.bg_qr_scan,
            iconBgRes = R.drawable.bg_step_icon_purple,
            iconTintHex = "#6A1B9A",
            purpose = context.getString(R.string.guide_qr_login_purpose),
            steps = context.getString(R.string.guide_qr_login_steps),
            troubleshooting = context.getString(R.string.guide_qr_login_troubleshooting),
            securityNote = context.getString(R.string.guide_qr_login_security_note)
        ),

        ScreenGuide(
            title = context.getString(R.string.guide_history_title),
            iconRes = R.drawable.ic_history,
            iconBgRes = R.drawable.bg_step_icon_blue,
            iconTintHex = "#0060AA",
            purpose = context.getString(R.string.guide_history_purpose),
            steps = context.getString(R.string.guide_history_steps),
            troubleshooting = context.getString(R.string.guide_history_troubleshooting),
            securityNote = context.getString(R.string.guide_history_security_note)
        ),

        ScreenGuide(
            title = context.getString(R.string.guide_settings_title),
            iconRes = R.drawable.ic_settings,
            iconBgRes = R.drawable.bg_step_icon_teal,
            iconTintHex = "#00897B",
            purpose = context.getString(R.string.guide_settings_purpose),
            steps = context.getString(R.string.guide_settings_steps),
            troubleshooting = context.getString(R.string.guide_settings_troubleshooting),
            securityNote = context.getString(R.string.guide_settings_security_note)
        ),

        ScreenGuide(
            title = context.getString(R.string.guide_security_info_title),
            iconRes = R.drawable.ic_shield,
            iconBgRes = R.drawable.bg_step_icon_green,
            iconTintHex = "#2E7D32",
            purpose = context.getString(R.string.guide_security_info_purpose),
            steps = context.getString(R.string.guide_security_info_steps),
            troubleshooting = context.getString(R.string.guide_security_info_troubleshooting),
            securityNote = context.getString(R.string.guide_security_info_security_note)
        ),

        ScreenGuide(
            title = context.getString(R.string.guide_backup_title),
            iconRes = R.drawable.ic_lock_small,
            iconBgRes = R.drawable.bg_step_icon_teal,
            iconTintHex = "#00897B",
            purpose = context.getString(R.string.guide_backup_purpose),
            steps = context.getString(R.string.guide_backup_steps),
            troubleshooting = context.getString(R.string.guide_backup_troubleshooting),
            securityNote = context.getString(R.string.guide_backup_security_note)
        )
    )

    fun getFaqCategories(context: Context): List<FaqCategory> = listOf(

        FaqCategory(
            title = context.getString(R.string.faq_cat_security_title),
            titleColorHex = "#0060AA",
            items = buildFaqItems(
                context,
                R.array.faq_cat_security_questions,
                R.array.faq_cat_security_answers
            )
        ),

        FaqCategory(
            title = context.getString(R.string.faq_cat_usage_title),
            titleColorHex = "#2E7D32",
            items = buildFaqItems(
                context,
                R.array.faq_cat_usage_questions,
                R.array.faq_cat_usage_answers
            )
        ),

        FaqCategory(
            title = context.getString(R.string.faq_cat_troubleshooting_title),
            titleColorHex = "#D67400",
            items = buildFaqItems(
                context,
                R.array.faq_cat_troubleshooting_questions,
                R.array.faq_cat_troubleshooting_answers
            )
        ),

        FaqCategory(
            title = context.getString(R.string.faq_cat_support_title),
            titleColorHex = "#6A1B9A",
            items = buildFaqItems(
                context,
                R.array.faq_cat_support_questions,
                R.array.faq_cat_support_answers
            )
        )
    )

    private fun buildFaqItems(context: Context, questionsRes: Int, answersRes: Int): List<FaqItem> {
        val questions = context.resources.getStringArray(questionsRes)
        val answers = context.resources.getStringArray(answersRes)
        return questions.zip(answers).map { (q, a) -> FaqItem(question = q, answer = a) }
    }
}
