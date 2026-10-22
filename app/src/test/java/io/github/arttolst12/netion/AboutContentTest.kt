package io.github.arttolst12.netion

import io.github.arttolst12.netion.ui.DeStrings
import io.github.arttolst12.netion.ui.EnStrings
import io.github.arttolst12.netion.ui.EsStrings
import io.github.arttolst12.netion.ui.FrStrings
import io.github.arttolst12.netion.ui.JaStrings
import io.github.arttolst12.netion.ui.KoStrings
import io.github.arttolst12.netion.ui.PlStrings
import io.github.arttolst12.netion.ui.PtStrings
import io.github.arttolst12.netion.ui.RuStrings
import io.github.arttolst12.netion.ui.ZhStrings
import org.junit.Assert.*
import org.junit.Test

class AboutContentTest {
    private val translations = listOf(RuStrings, EnStrings, DeStrings, FrStrings, EsStrings, PtStrings, PlStrings, ZhStrings, JaStrings, KoStrings)

    @Test
    fun newAboutFieldsAreProvidedForAllTenLanguages() {
        assertEquals(10, translations.size)
        for (strings in translations) {
            assertTrue(strings.aboutVersionLabel.isNotBlank())
            assertTrue(strings.aboutOpenRepository.isNotBlank())
            assertTrue(strings.aboutLinkError.contains("github.com/ArtTolst12/Netion-Link"))
            assertTrue(strings.aboutThanksTitle.isNotBlank())
            assertTrue(strings.aboutThanksDesc.contains("Google"))
            assertTrue(strings.aboutThanksDesc.contains("Anthropic"))
            assertTrue(strings.aboutTrademarksDesc.contains("Google"))
            assertTrue(strings.aboutTrademarksDesc.contains("Anthropic"))
        }
    }

    @Test
    fun existingAgplLicenseAndWarrantyNoticesAreRetained() {
        for (strings in translations) {
            assertTrue(strings.aboutLicenseDesc.contains("AGPL-3.0"))
            assertTrue(strings.aboutWarrantyDesc.isNotBlank())
            assertTrue(strings.aboutPrivacyDesc.isNotBlank())
        }
    }
}
