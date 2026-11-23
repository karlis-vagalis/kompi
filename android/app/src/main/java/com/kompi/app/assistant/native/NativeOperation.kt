package com.kompi.app.assistant.native

import android.app.Activity
import android.content.Context
import com.kompi.app.assistant.actions.PhoneActionAccess
import org.json.JSONObject

/**
 * A side-effecting or private-data operation parsed from one bounded phone-action request.
 * Parsing must be side-effect free; execute is called only after checking the computer's grant.
 */
internal interface NativeOperation {
    val access: PhoneActionAccess
    val actionName: String
    val confirmation: String
    val guidance: String
    suspend fun execute(context: Context, activity: Activity): JSONObject
}

data class NativeCapability(
    val name: String,
    val available: Boolean,
    val reason: String,
)
