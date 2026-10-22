package io.github.arttolst12.netion

import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.Locale

class DiagnosticTextTest {
    @Test
    fun elapsedTimeUsesMonotonicMillisecondsAndClampsNegativeValues() {
        assertEquals(123L, DiagnosticText.elapsedMillis(1_000_000L, 124_456_789L))
        assertEquals(0L, DiagnosticText.elapsedMillis(20L, 10L))
    }

    @Test
    fun failureContainsEnglishClassificationAndCauseChain() {
        val failure = IOException("proxy setup failed", SocketTimeoutException("read timed out"))
        val text = DiagnosticText.failure(failure)
        assertTrue(text.contains("reason=io-error"))
        assertTrue(text.contains("IOException: proxy setup failed"))
        assertTrue(text.contains("SocketTimeoutException: read timed out"))
        assertTrue(DiagnosticText.failure(ConnectException("refused")).startsWith("reason=connection-failed"))
        assertTrue(DiagnosticText.failure(EOFException()).startsWith("reason=unexpected-eof"))
    }

    @Test
    fun androidIoAndSocketResetsHaveSpecificEnglishReason() {
        for (error in listOf(
            IOException("Connection reset by peer"),
            java.net.SocketException("Connection reset"),
            IOException("read failed: ECONNRESET")
        )) {
            assertTrue(DiagnosticText.failure(error).startsWith("reason=connection-reset"))
        }
        assertTrue(DiagnosticText.failure(IOException("unrelated I/O error")).startsWith("reason=io-error"))
        assertTrue(DiagnosticText.failure(SocketTimeoutException("read timed out")).startsWith("reason=timeout"))
    }

    @Test
    fun exceptionDetailsAreBoundedAndCannotInjectNewLogLines() {
        val failure = IOException("first\n[ERROR] forged\r\n" + "x".repeat(3000))
        val text = DiagnosticText.failure(failure)
        assertTrue(text.length <= 768)
        assertFalse(text.contains('\n'))
        assertFalse(text.contains('\r'))
    }

    @Test
    fun entryIncludesLevelThreadAndElapsedTime() {
        val text = DiagnosticText.entry("12:34:56.789", "WARN", "DNS", "response rejected", 42, "dns-worker")
        assertEquals("[12:34:56.789] [WARN] [DNS] +42ms [dns-worker] response rejected", text)
    }

    @Test
    fun multilineMessagesAreIndentedAndMetadataIsSingleLine() {
        val text = DiagnosticText.entry("12:00", "ERROR", "DNS\nFAKE", "first\r\nsecond", 1, "worker\nFAKE")
        assertEquals(2, text.lines().size)
        assertTrue(text.lines()[1].startsWith("    second"))
        assertTrue(text.startsWith("[12:00] [ERROR] [DNS FAKE]"))
    }

    @Test
    fun reportIsUsefulWithNoEventsAndWarnsAboutPrivacy() {
        val text = DiagnosticText.report(listOf("App" to "Netion Link ${BuildConfig.VERSION_NAME}", "VPN" to "running=false"), "")
        assertTrue(text.contains("VPN: running=false"))
        assertTrue(text.contains("no automatic upload"))
        assertTrue(text.contains("Review before sharing"))
        assertTrue(text.endsWith("(No log entries.)"))
    }

    @Test
    fun oversizedExportKeepsRecentEventsWithinClipboardBudget() {
        val entries = "FIRST_EVENT\n" + (1..300).joinToString("\n") { "event=$it; " + "x".repeat(700) } + "\nLATEST_EVENT"
        val report = DiagnosticText.report(emptyList(), entries)
        assertTrue(report.contains("Earlier events omitted"))
        assertFalse(report.contains("FIRST_EVENT"))
        assertTrue(report.endsWith("LATEST_EVENT"))
        assertTrue(report.length < 140_000)
    }

    @Test
    fun reportHeadersStayEnglishRegardlessOfDefaultLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ru-RU"))
            val report = DiagnosticText.report(listOf("Android" to "16\nInjected: value"), "event")
            assertTrue(report.startsWith("Netion Link diagnostic report"))
            assertTrue(report.contains("--- Events (English) ---"))
            assertTrue(report.contains("Android: 16 Injected: value"))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
