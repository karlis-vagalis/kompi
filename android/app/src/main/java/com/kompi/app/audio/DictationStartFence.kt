package com.kompi.app.audio

/** Main-thread ownership of a user request across suspending microphone handoffs. */
internal class DictationStartFence {
    class Ticket internal constructor()
    private val current = androidx.compose.runtime.mutableStateOf<Ticket?>(null)

    val hasRequest: Boolean get() = current.value != null

    fun request(): Ticket = Ticket().also { current.value = it }
    fun isCurrent(ticket: Ticket): Boolean = current.value === ticket
    fun invalidate() { current.value = null }
}
