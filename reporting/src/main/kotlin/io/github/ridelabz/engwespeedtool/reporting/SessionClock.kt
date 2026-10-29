package io.github.ridelabz.engwespeedtool.reporting

import java.time.LocalDate

/** Time source for logs: only the date and the time elapsed since the session started are ever recorded. */
public interface SessionClock {
    public fun today(): LocalDate

    public fun elapsedNanos(): Long

    public companion object {
        public fun system(): SessionClock =
            object : SessionClock {
                private val start = System.nanoTime()

                override fun today(): LocalDate = LocalDate.now()

                override fun elapsedNanos(): Long = System.nanoTime() - start
            }
    }
}
