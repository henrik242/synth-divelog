package no.synth.divelog.core.logbook

/** What went wrong, for a message to the user. */
val Throwable.reason: String get() = message ?: this::class.simpleName ?: "unknown error"
