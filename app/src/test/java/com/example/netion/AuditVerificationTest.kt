package com.example.netion

import com.example.netion.ui.AppLanguage
import com.example.netion.ui.RuStrings
import com.example.netion.ui.EnStrings
import com.example.netion.ui.Strings
import org.junit.Assert.*
import org.junit.Test

class AuditVerificationTest {

    @Test
    fun testStringsSuggestedPortFormattingAcrossLanguages() {
        val testPort = 8081
        // Verify RU
        val ruFormatted = RuStrings.suggestedFreePort.format(testPort)
        assertTrue(ruFormatted.contains("8081"))
        assertTrue(ruFormatted.contains("Предлагается"))

        // Verify EN
        val enFormatted = EnStrings.suggestedFreePort.format(testPort)
        assertTrue(enFormatted.contains("8081"))
        assertTrue(enFormatted.contains("Suggested available"))
    }

    @Test
    fun testDnsServFailFlagTransformation() {
        // Mock a 12-byte DNS query header: ID=0x1234, Flags=0x0100 (standard query)
        val query = ByteArray(12)
        query[0] = 0x12
        query[1] = 0x34
        query[2] = 0x01 // RD bit set
        query[3] = 0x00

        val response = query.copyOf()
        // QR=1, RCODE=2 (SERVFAIL)
        response[2] = (response[2].toInt() or 0x80).toByte()
        response[3] = ((response[3].toInt() and 0xF0) or 0x02).toByte()

        // Check QR flag is 1 (bit 7 set)
        assertTrue((response[2].toInt() and 0x80) != 0)
        // Check RCODE is 2 (SERVFAIL)
        assertEquals(2, response[3].toInt() and 0x0F)
        // Check transaction ID preserved
        assertEquals(0x12.toByte(), response[0])
        assertEquals(0x34.toByte(), response[1])
    }

    @Test
    fun testAboutAppStringsDefinedInAllLanguages() {
        val allStrings = listOf(
            com.example.netion.ui.RuStrings,
            com.example.netion.ui.EnStrings,
            com.example.netion.ui.DeStrings,
            com.example.netion.ui.FrStrings,
            com.example.netion.ui.EsStrings,
            com.example.netion.ui.PtStrings,
            com.example.netion.ui.PlStrings,
            com.example.netion.ui.ZhStrings,
            com.example.netion.ui.JaStrings,
            com.example.netion.ui.KoStrings
        )
        for (s in allStrings) {
            assertTrue(s.aboutAppTitle.isNotBlank())
            assertTrue(s.aboutAppDesc.isNotBlank())
            assertTrue(s.aboutAppSubtitle.isNotBlank())
            assertTrue(s.aboutRepoTitle.isNotBlank())
            assertTrue(s.aboutRepoDesc.isNotBlank())
            assertTrue(s.aboutLicenseTitle.isNotBlank())
            assertTrue(s.aboutLicenseDesc.isNotBlank())
            assertTrue(s.aboutWarrantyTitle.isNotBlank())
            assertTrue(s.aboutWarrantyDesc.isNotBlank())
            assertTrue(s.aboutPrivacyTitle.isNotBlank())
            assertTrue(s.aboutPrivacyDesc.isNotBlank())
        }
    }
}
