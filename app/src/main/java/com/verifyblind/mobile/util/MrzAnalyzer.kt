package com.verifyblind.mobile.util

import android.annotation.SuppressLint
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class MrzAnalyzer(private val onResult: (String, String, String, String) -> Unit) : ImageAnalysis.Analyzer { 

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var isAnalyzing = false

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null && !isAnalyzing) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            isAnalyzing = true

            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    processText(visionText.text)
                }
                .addOnFailureListener { e ->
                    e.printStackTrace()
                }
                .addOnCompleteListener {
                    isAnalyzing = false
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    private fun processText(text: String) {
        val lines = text.split("\n").filter { it.length > 20 }
        
var docNo: String? = null
        var dob: String? = null
        var expiry: String? = null
        var documentType = "ID" // Default: ID card (TD1)

        // ── Try TD3 (Passport) first: 2 lines × 44 chars ──
        // Line 1: P<[Country3][SURNAME<<GIVENNAMES<<<...] (44 chars)
        // Line 2: [DocNo9][Check1][Nationality3][DOB6][Check1][Sex1][Expiry6][Check1]... (44 chars)
        
        for (line in lines) {
            val cleanerLine = line.replace(" ", "").uppercase()
            
            // Detect TD3 Line 1: starts with P followed by < or a letter, then 3-letter country code
            val td3Line1Regex = Regex("^P[<A-Z]([A-Z]{3})")
            val td3Match = td3Line1Regex.find(cleanerLine)
            
            if (td3Match != null && cleanerLine.length >= 40) {
                // This is a passport MRZ Line 1 — mark as passport
                documentType = "PASSPORT"
            }
        }
        
        // ── Parse fields based on detected type ──
        for (line in lines) {
            val cleanerLine = line.replace(" ", "").uppercase()
            
            if (documentType == "PASSPORT") {
                // TD3 Line 2: [DocNo9][Check1][Nationality3][DOB6][Check1][Sex1][Expiry6][Check1]...
                // Try to match TD3 Line 2 pattern
                if (cleanerLine.length >= 28 && docNo == null) {
                    // TD3 Line 2 starts with DocNo (9 chars) + check digit
                    // Then Nationality (3), DOB (6), check, Sex (1), Expiry (6), check
                    val td3Line2Regex = Regex("^([A-Z0-9]{9})([0-9])([A-Z]{3})([0-9]{6})([0-9])([MF<])([0-9]{6})([0-9])")
                    val td3L2Match = td3Line2Regex.find(cleanerLine)
                    
                    if (td3L2Match != null) {
                        val rawDocNo = td3L2Match.groupValues[1]
                        val docCheck = td3L2Match.groupValues[2].firstOrNull() ?: ' '
                        val rawDob = td3L2Match.groupValues[4]
                        val dobCheck = td3L2Match.groupValues[5].firstOrNull() ?: ' '
                        val rawExpiry = td3L2Match.groupValues[7]
                        val expCheck = td3L2Match.groupValues[8].firstOrNull() ?: ' '
                        
                        if (isValidDate(rawDob) && isValidDate(rawExpiry)) {
                            val fixedDocNo = validateAndFix(rawDocNo, docCheck)
                            val fixedDob = validateAndFix(rawDob, dobCheck)
                            val fixedExp = validateAndFix(rawExpiry, expCheck)
                            
                            if (fixedDocNo != null && fixedDob != null && fixedExp != null) {
                                docNo = fixedDocNo
                                dob = fixedDob
                                expiry = fixedExp
                            }
                        }
                    }
                }
            } else {
                // ── TD1 (ID Card): 3 lines × 30 chars ──
                // Line 1: [DocType1-2]<[Country3][DocNo9][Check1][OptData15]
                // Line 2: [DOB6][Check1][Sex1][Expiry6][Check1][Nationality3]...
                
                // Match TD1 Line 1: starts with I<, A<, or C< followed by 3-letter country code
                val td1Line1Regex = Regex("^([IAC])<([A-Z]{3})")
                val td1Match = td1Line1Regex.find(cleanerLine)
                
                if (td1Match != null) {
                    if (cleanerLine.length >= 14) {
                        val rawDocNo = cleanerLine.substring(5, 14)
                        val rawCheck = cleanerLine[14]
                        
                        val fixedDocNo = validateAndFix(rawDocNo, rawCheck)
                        if (fixedDocNo != null) {
                            docNo = fixedDocNo
                        }
                    }
                }
                
                // TD1 Line 2: [DOB6][Check1][Sex1][Expiry6][Check1]...
                val regex = Regex("([0-9]{6})([0-9])([MF<])([0-9]{6})([0-9])")
                val match = regex.find(cleanerLine)
                
                if (match != null) {
                    val rawDob = match.groupValues[1]
                    val dobCheck = match.groupValues[2].firstOrNull() ?: ' '
                    val rawExpiry = match.groupValues[4]
                    val expCheck = match.groupValues[5].firstOrNull() ?: ' '
                    
                    if (isValidDate(rawDob) && isValidDate(rawExpiry)) {
                        val fixedDob = validateAndFix(rawDob, dobCheck)
                        val fixedExp = validateAndFix(rawExpiry, expCheck)
                        
                        if (fixedDob != null && fixedExp != null) {
                            dob = fixedDob
                            expiry = fixedExp
                        } 
                    }
                }
            }
        }
        
        if (docNo != null && dob != null && expiry != null) {
val currentResult = MrzResult(docNo!!, dob!!, expiry!!, documentType) 
            
            if (currentResult == lastResult) {
                stabilityCounter++
            } else {
                stabilityCounter = 1
                lastResult = currentResult
            }
            
            // Require 3 consecutive identical reads to confirm stability (Focus check)
            if (stabilityCounter >= 3) {
onResult(docNo, dob, expiry, documentType) 
                // Reset to avoid multiple callbacks
                lastResult = null
                stabilityCounter = 0
            }
        }
    }
    
    // MRZ Check Digit Calculation (Weights: 7, 3, 1)
    private fun calculateCheckDigit(input: String): Int {
        var sum = 0
        val weights = listOf(7, 3, 1)
        
        for ((index, char) in input.withIndex()) {
            val value = when(char) {
                in '0'..'9' -> char - '0'
                in 'A'..'Z' -> char - 'A' + 10
                '<' -> 0
                else -> 0
            }
            sum += value * weights[index % 3]
        }
        return sum % 10
    }
    
    // Auto-Corrects common OCR errors (S->5, O->0, etc) by verifying against Checksum
    private fun validateAndFix(value: String, checkChar: Char): String? {
        val targetCheck = checkChar.toString().toIntOrNull() ?: return null
        
        // 1. Try Original
        if (calculateCheckDigit(value) == targetCheck) return value
        
// 2. Try Fixing Common Ambiguities 
        val ambiguousIndices = mutableListOf<Int>()
        for ((i, c) in value.withIndex()) {
            if ("S5O0QUDZ2B8".contains(c)) ambiguousIndices.add(i)
        }
        
        // If too many ambiguous chars, don't burn CPU (limit to 3 corrections)
        if (ambiguousIndices.size > 3) return null 
        
 
        for (idx in ambiguousIndices) {
            val originalChar = value[idx]
            val replacements = getReplacements(originalChar)
            
            for (replacement in replacements) {
                val chars = value.toCharArray()
                chars[idx] = replacement
                val candidate = String(chars)
                if (calculateCheckDigit(candidate) == targetCheck) {
return candidate 
                }
            }
        }
        
        return null // Checksum failed and couldn't be fixed
    }
    
    private fun getReplacements(c: Char): List<Char> {
        return when (c) {
            'S', 's' -> listOf('5')
            '5' -> listOf('S')
            'O', 'o', 'Q', 'U', 'D' -> listOf('0')
            '0' -> listOf('O')
            'Z', 'z' -> listOf('2')
            '2' -> listOf('Z')
            'B', 'b' -> listOf('8')
            '8' -> listOf('B')
            else -> emptyList()
        }
    }
    
    private fun isValidDate(dateStr: String): Boolean {
        if (dateStr.length != 6) return false
        val month = dateStr.substring(2, 4).toIntOrNull() ?: return false
        val day = dateStr.substring(4, 6).toIntOrNull() ?: return false
        
        return month in 1..12 && day in 1..31
    }
    
    // Stability State
private data class MrzResult(val docNo: String, val dob: String, val expiry: String, val documentType: String)
    private var lastResult: MrzResult? = null
    private var stabilityCounter = 0
} 
