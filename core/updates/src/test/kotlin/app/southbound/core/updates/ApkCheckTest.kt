package app.southbound.core.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkCheckTest {
    private val key = "k".repeat(64)
    private val sha = "a".repeat(64)

    private fun verify(
        pkg: String? = "app.southbound",
        code: Long = 102,
        signers: Set<String> = setOf(key),
        history: List<String> = emptyList(),
        actual: String = sha,
    ) = ApkCheck.verify("app.southbound", 101, setOf(key), pkg, code, signers, history, sha, actual)

    private fun refused(verdict: ApkCheck.Verdict, words: String) {
        assertTrue("$verdict", verdict is ApkCheck.Verdict.Refused && words in verdict.reason)
    }

    @Test
    fun acceptsANewerBuildOfThisAppWithTheSameKey() {
        assertEquals(ApkCheck.Verdict.Ok, verify())
    }

    @Test
    fun acceptsARotatedKeyWhoseLineageIncludesTheOldOne() {
        assertEquals(ApkCheck.Verdict.Ok, verify(signers = setOf("n".repeat(64)), history = listOf(key, "n".repeat(64))))
    }

    @Test
    fun refusesEverythingElse() {
        refused(verify(actual = "b".repeat(64)), "checksum")
        refused(verify(pkg = null), "isn't an Android app")
        refused(verify(pkg = "com.other"), "not Southbound")
        refused(verify(code = 101), "not newer")
        refused(verify(signers = emptySet()), "couldn't be read")
        refused(verify(signers = setOf("x".repeat(64))), "different key")
    }

    @Test
    fun privateRepositoryAsksForAToken() {
        assertTrue(UpdateErrors.forHttp(404, hasToken = false).needsToken)
        assertTrue(UpdateErrors.forHttp(401, hasToken = true).needsToken)
        assertTrue(!UpdateErrors.forHttp(503, hasToken = true).needsToken)
    }
}
