package app.southbound.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

class GuidanceAudioFilesTest {

    @Test
    fun `prompts a process death left behind are swept and nothing else is`() {
        val dir = Files.createTempDirectory("cache").toFile()
        try {
            val left = List(3) { GuidanceAudioFiles.fileFor(dir, UUID.randomUUID().toString()).apply { writeBytes(ByteArray(44)) } }
            val others = listOf("guidance-notes.txt", "guidance-.wav", "voice.wav", "guidance-123.wav").map { File(dir, it).apply { writeText("x") } }
            // A folder with a prompt's name is not a prompt.
            val folder = File(dir, "guidance-${UUID.randomUUID()}.wav").apply { mkdirs() }

            assertEquals(0, GuidanceAudioFiles.sweep(dir))
            left.forEach { assertFalse("${it.name} still there", it.exists()) }
            others.forEach { assertTrue("${it.name} was removed", it.exists()) }
            assertTrue(folder.isDirectory)
            // A second sweep finds nothing, and a missing folder is no error.
            assertEquals(0, GuidanceAudioFiles.sweep(dir))
            assertEquals(0, GuidanceAudioFiles.sweep(File(dir, "missing")))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the engine's file names are the ones the sweep removes`() {
        val file = GuidanceAudioFiles.fileFor(File("/cache"), UUID.randomUUID().toString())
        assertTrue(GuidanceAudioFiles.isPrompt(file))
        assertFalse(GuidanceAudioFiles.isPrompt(File("/cache/guidance-x.wav")))
    }
}
