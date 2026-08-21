package app.mosaic.privatevault.core

import app.mosaic.privatevault.core.log.SafeLog
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "No sensitive content in the logs" is a property of the whole source tree,
 * not of one function, so this test checks the tree.
 *
 * Logcat is readable by more actors than the encrypted vault is — adb, bug
 * reports, some OEM diagnostics. A single `Log.d("msg: $text")` added in a
 * hurry would undo the rest of the app's privacy work, and would be invisible
 * in review. This test makes it a build failure instead.
 */
class LoggingHygieneTest {

    private val sourceRoot = File("src/main/java")

    private val sensitiveIdentifiers = listOf(
        "text", "message", "body", "handle", "username", "alias",
        "token", "accessToken", "pin", "passphrase", "displayName",
    )

    private fun kotlinSources(): List<File> {
        assertTrue(
            "source root not found; the test's working directory assumption changed",
            sourceRoot.isDirectory,
        )
        return sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test
    fun `android util Log is only referenced by SafeLog`() {
        val offenders = kotlinSources()
            .filter { it.name != "SafeLog.kt" }
            .filter { file ->
                file.readLines().any { line ->
                    // Importing the class is fine; calling it is what leaks.
                    if (line.trimStart().startsWith("import ")) return@any false
                    val code = line.substringBefore("//")
                    Regex("""\bandroid\.util\.Log\.[vdiwe]\(|(?<![A-Za-z.])Log\.[vdiwe]\(""")
                        .containsMatchIn(code)
                }
            }
            .map { it.name }

        assertEquals(
            "these files log directly instead of going through SafeLog: $offenders",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `no SafeLog call interpolates a sensitive value`() {
        val offenders = mutableListOf<String>()
        val callPattern = Regex("""SafeLog\.[diwe]\(\s*"([^"]*)"""")

        kotlinSources().forEach { file ->
            callPattern.findAll(file.readText()).forEach { match ->
                val literal = match.groupValues[1]
                val interpolated = Regex("""\$\{?([A-Za-z_][A-Za-z0-9_.]*)""")
                    .findAll(literal)
                    .map { it.groupValues[1].substringAfterLast('.') }
                    .toList()

                interpolated.forEach { name ->
                    if (sensitiveIdentifiers.any { it.equals(name, ignoreCase = true) }) {
                        offenders += "${file.name}: \$$name"
                    }
                }
            }
        }

        assertEquals(
            "log statements interpolate sensitive values: $offenders",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `redact never reveals the value`() {
        val secret = "rendez-vous demain a 21h"

        val redacted = SafeLog.redact(secret)

        assertFalse(redacted.contains("rendez"))
        assertFalse(redacted.contains("21h"))
        assertTrue(redacted.startsWith("<${secret.length}ch:"))
    }

    @Test
    fun `redact is stable so two occurrences can be correlated`() {
        assertEquals(SafeLog.redact("camille_r"), SafeLog.redact("camille_r"))
    }

    @Test
    fun `redact distinguishes different values`() {
        assertTrue(SafeLog.redact("camille_r") != SafeLog.redact("camille_s"))
    }

    @Test
    fun `redact handles null and empty without leaking a shape`() {
        assertEquals("<null>", SafeLog.redact(null))
        assertEquals("<empty>", SafeLog.redact(""))
    }
}
