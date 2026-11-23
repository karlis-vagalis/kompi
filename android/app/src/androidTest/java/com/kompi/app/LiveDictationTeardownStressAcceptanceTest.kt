package com.kompi.app

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test

/** Run this selector alone in a fresh runner-owned VM, separate from normal native acceptance.
 * The explicit host flag prevents accidental inclusion in an ordinary whole-class/group run.
 * This preserves immediate-stop word/ownership assertions while identifying the SDK-delivery
 * stress boundary honestly: injectAudio RPC return is not an AudioRecord/decoder drain barrier. */
internal class LiveDictationTeardownStressAcceptanceTest : NativeMicrophoneScenario() {
    @Test fun l71_immediateDictationStopAfterInjectionIsIsolatedSdkTeardownStress() {
        require(InstrumentationRegistry.getArguments().getString("kompiIsolatedSdkStress") == "true") {
            "Run this selector alone in a fresh disposable VM with -e kompiIsolatedSdkStress true"
        }
        runNativeDictationHandoff(compose, h, audio, NativeDictationHandoffTiming.StopAtInjectionReturnStress,
            ::startListening, ::spoken, ::microphoneStopped)
    }
}
