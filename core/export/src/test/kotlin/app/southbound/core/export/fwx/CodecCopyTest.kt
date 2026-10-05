package app.southbound.core.export.fwx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * The codec in export/fwx is a copy of the fieldwork program's canonical FWX codec, unchanged apart from its
 * package line and one provenance line (fieldwork codec/README.md). Each file's first line names the canonical
 * SHA-256 of the file without its package line, and `core/export/SOURCES.sha256` lists the same hashes; an edit made
 * here, rather than upstream, fails this test. (Southbound-specific code lives beside the codec, never in it.)
 */
class CodecCopyTest {

    private val header = Regex("""^// FWX codec (\S+), canonical sha256 ([0-9a-f]{64}) \(fieldwork codec/kotlin/src/main/kotlin/fwx/(\w+\.kt) at [0-9a-f]{7,40}\)$""")
    private val testHeader = Regex("""^// FWX codec tests (\S+), copied from fieldwork codec/kotlin/src/test/kotlin/fwx/(\w+\.kt) at [0-9a-f]{7,40} \(package line changed\)$""")

    private fun moduleDir(): File =
        listOf(File("."), File("core/export")).firstOrNull { File(it, "SOURCES.sha256").isFile }
            ?: error("core/export not found from ${File("").absolutePath}")

    private fun sources(): List<File> {
        val dir = File(moduleDir(), "src/main/kotlin/app/southbound/core/export/fwx")
        return dir.listFiles { f -> f.name.endsWith(".kt") }!!.sortedBy { it.name }
    }

    @Test
    fun everyCodecFileMatchesItsCanonicalHash() {
        val listed = File(moduleDir(), "SOURCES.sha256").readLines().filter { it.isNotBlank() }
            .associate { it.substringAfter("  ") to it.substringBefore("  ") }
        val files = sources()
        assertEquals(listed.keys.sorted(), files.map { it.name })
        for (file in files) {
            val lines = file.readText().split('\n')
            val match = header.matchEntire(lines.first()) ?: error("${file.name}: missing the provenance line")
            val (version, hash, name) = match.destructured
            assertEquals(file.name, name)
            assertEquals(Fwx.CODEC_VERSION, version)
            assertEquals("${file.name}: header disagrees with SOURCES.sha256", listed[file.name], hash)
            assertEquals("package app.southbound.core.export.fwx", lines[1])
            val body = lines.drop(1).filterNot { it.startsWith("package ") }.joinToString("\n")
            assertEquals("${file.name} differs from the canonical codec", hash, sha256(body.toByteArray(Charsets.UTF_8)))
        }
    }

    @Test
    fun theCodecIsVersion120() {
        assertEquals("1.2.0", Fwx.CODEC_VERSION)
    }

    @Test
    fun theCopiedTestsKeepTheirProvenanceLine() {
        val dir = File(moduleDir(), "src/test/kotlin/app/southbound/core/export/fwx")
        val tests = dir.listFiles { f -> f.name.endsWith(".kt") && f.name != "CodecCopyTest.kt" }!!.sortedBy { it.name }
        assertEquals(
            listOf("CasesTest.kt", "PrimitiveTest.kt", "TestSupport.kt", "VectorGen.kt", "VectorTest.kt", "WriterReaderTest.kt"),
            tests.map { it.name },
        )
        for (file in tests) {
            val lines = file.readText().split('\n')
            val match = testHeader.matchEntire(lines.first()) ?: error("${file.name}: missing the provenance line")
            assertEquals("1.2.0-r1", match.groupValues[1])
            assertEquals(file.name, match.groupValues[2])
            assertEquals("package app.southbound.core.export.fwx", lines[1])
        }
    }

    /**
     * The codec has no entry point that fixes the salt or nonce prefix (codec/README.md, "No test hooks in
     * production code"): reusing them under one passphrase would leak plaintext. Nothing outside src/test, and the
     * codec's own private Seeds class, may mention the test hooks.
     */
    @Test
    fun noTestHookInProductionCode() {
        var root = moduleDir().absoluteFile.canonicalFile
        while (!File(root, "settings.gradle.kts").isFile) root = root.parentFile ?: error("repository root not found")
        val hook = Regex("""fixedWriter|withFixedRandom|pbkdf2Runs|getDeclaredConstructor|Seeds\(|isAccessible""")
        val offenders = root.walkTopDown()
            .onEnter { it == root || (it.name != "build" && it.name != "third-party" && !it.name.startsWith(".")) }
            .filter { it.isFile && it.extension == "kt" }
            .filter { "/src/test/" !in it.path && "/src/androidTest/" !in it.path && !it.path.endsWith("/fwx/FwxWriter.kt") }
            .filter { hook.containsMatchIn(it.readText()) }
            .map { it.relativeTo(root).path }
            .toList()
        assertTrue("test hooks in production code: $offenders", offenders.isEmpty())
        // The one place Seeds may appear is the codec's own writer, which is hash-checked above.
        val writer = File(moduleDir(), "src/main/kotlin/app/southbound/core/export/fwx/FwxWriter.kt").readText()
        assertTrue("private class Seeds" in writer)
        assertFalse("fixedWriter" in writer)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
