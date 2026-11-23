package com.kompi.app

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry

/** Numeric-only, test-local observations persisted in the host's raw instrumentation artifact.
 * Progress code 2 is deliberately not a JUnit pass/failure result. Dynamic speech, model bodies,
 * credentials and identifiers cannot be passed as metric values through this boundary. */
internal fun recordLiveNumericObservation(caseId: String, stage: String, vararg metrics: Pair<String, Number>) {
    require(caseId.matches(Regex("L[0-9]{2,3}")))
    require(stage.matches(Regex("[a-z][a-z0-9_]*")))
    require(metrics.isNotEmpty() && metrics.map { it.first }.toSet().size == metrics.size)
    metrics.forEach { (name, value) ->
        require(name.matches(Regex("[A-Za-z][A-Za-z0-9_]*")))
        require(value.toDouble().isFinite())
    }
    val line = "PUBLIC_NUMERIC_METRIC case=$caseId stage=$stage " + metrics.joinToString(" ") { (name, value) -> "$name=$value" }
    InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("stream", "$line\n") })
    println(line)
}
