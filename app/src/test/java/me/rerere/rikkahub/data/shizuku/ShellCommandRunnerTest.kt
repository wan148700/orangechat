package me.rerere.rikkahub.data.shizuku

import org.junit.Assert.*
import org.junit.Test

class ShellCommandRunnerTest {
    @Test fun returnsBothStreamsAndNonzeroExit() {
        val r = ShellCommandRunner.run(listOf("sh", "-c", "printf hello; printf failure >&2; exit 7"), 5, 1000)
        assertEquals("hello", r.stdout)
        assertEquals("failure", r.stderr)
        assertEquals(7, r.exitCode)
        assertFalse(r.timedOut)
    }

    @Test fun drainsLargeOutputsWithoutDeadlock() {
        val r = ShellCommandRunner.run(listOf("sh", "-c", "i=0; while [ \"\$i\" -lt 15000 ]; do printf abcdefgh; printf ijklmnop >&2; i=\$((i+1)); done"), 10, 256)
        assertEquals(0, r.exitCode)
        assertEquals(256, r.stdout.length)
        assertEquals(256, r.stderr.length)
        assertTrue(r.stdoutTruncated)
        assertTrue(r.stderrTruncated)
    }

    @Test fun reportsTimedOutCommand() {
        val r = ShellCommandRunner.run(listOf("timeout", "-k", "1", "1", "sh", "-c", "exec sleep 10"), 1, 256)
        assertTrue(r.timedOut)
        assertTrue(r.durationMs < 5000)
    }
}
