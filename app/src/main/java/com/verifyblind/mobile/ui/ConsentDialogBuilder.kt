package com.verifyblind.mobile.ui

import android.content.Context
import android.content.DialogInterface
import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import com.verifyblind.mobile.R
import com.verifyblind.mobile.api.PartnerInfoResponse

/**
 * ConsentDialogBuilder — Partner onay dialogu UI oluşturma.
 *
 * MainActivity'den ayrıştırılmış sorumluluk:
 * - Logo, partner adı, onay sorusu ve scope listesi ile programatik dialog oluşturma
 */
object ConsentDialogBuilder {

    /**
     * Partner onay dialogunu gösterir.
     *
     * @param context Activity context
     * @param info Partner bilgisi
     * @param logo Partner logosu (nullable — yoksa initials gösterilir)
     * @param onApprove Onaylama callback
     * @param onReject Reddetme callback
     */
    fun show(
        context: Context,
        info: PartnerInfoResponse,
        logo: Bitmap?,
        onApprove: () -> Unit,
        onReject: () -> Unit
    ) {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 20)
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.WHITE)
        }

        // Logo Holder (Square/Initials fallback)
        val logoContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(250, 250).apply {
                setMargins(0, 0, 0, 40)
            }
        }

        if (logo != null) {
            val imgLogo = ImageView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(logo)
            }
            logoContainer.addView(imgLogo)
        } else {
            // Initials Fallback (e.g. "ERCU PROD" -> "EP")
            val initials = try {
                val parts = info.name.trim().split(" ")
                if (parts.size >= 2) {
                    "${parts[0].take(1)}${parts[1].take(1)}".uppercase()
                } else {
                    info.name.take(2).uppercase()
                }
            } catch (e: Exception) { "??" }

            val bgView = View(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(Color.parseColor("#1287BE"))
            }
            logoContainer.addView(bgView)

            val tvInitials = TextView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                text = initials
                textSize = 36f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            }
            logoContainer.addView(tvInitials)
        }
        root.addView(logoContainer)

        // Partner Name
        val tvName = TextView(context).apply {
            text = info.name.uppercase()
            textSize = 24f
            typeface = android.graphics.Typeface.DEFAULT
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, 20)
        }
        root.addView(tvName)

        // Question Text
        val tvQuestion = TextView(context).apply {
            text = context.getString(R.string.consent_question)
            textSize = 15f
            setLineSpacing(0f, 1.2f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 20)
            setTextColor(Color.BLACK)
        }
        root.addView(tvQuestion)

        // Scopes List (Bullet points)
        val scopesContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 0, 0, 20)
            gravity = Gravity.START
        }

        val items = mutableListOf<String>()
        info.validations?.let { v ->
            if (v.isJsonObject) {
                val obj = v.asJsonObject
                if (obj.has("user_id")) items.add(context.getString(R.string.scope_user_id))
                if (obj.has("age")) {
                    val valStr = obj.get("age").asString
                    items.add(context.getString(R.string.scope_age, valStr))
                }
            }
        }
        if (items.isEmpty()) items.add(context.getString(R.string.consent_default_scope))

        items.forEach { s ->
            val tvScope = TextView(context).apply {
                text = "• $s"
                textSize = 14f
                setPadding(0, 4, 0, 4)
                setTextColor(Color.parseColor("#4A4A4A"))
            }
            scopesContainer.addView(tvScope)
        }
        root.addView(scopesContainer)

        // Kapsam Footer
        val tvKapsam = TextView(context).apply {
            text = context.getString(R.string.consent_scope_footer)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, 40)
            setTextColor(Color.GRAY)
        }
        root.addView(tvKapsam)

        val dialog = AlertDialog.Builder(context)
            .setView(root)
            .setPositiveButton(context.getString(R.string.consent_btn_approve)) { _, _ -> onApprove() }
            .setNegativeButton(context.getString(R.string.consent_btn_reject)) { _, _ -> onReject() }
            .setCancelable(false)
            .create()

        dialog.show()

        // Style Buttons after show
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setTextColor(Color.parseColor("#00D9FF"))
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setTextColor(Color.parseColor("#00D9FF"))
    }
}
