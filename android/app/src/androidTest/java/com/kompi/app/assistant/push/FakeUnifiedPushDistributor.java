package com.kompi.app.assistant.push;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Base64;

import java.util.UUID;

/** Test-APK-only distributor receiver. Keep framework-only for its standalone process. */
public final class FakeUnifiedPushDistributor extends BroadcastReceiver {
    public static final String ACTION_DELIVER_STAGED = "com.kompi.app.test.DELIVER_STAGED_PUSH";
    public static final String ACTION_STAGE_PAYLOAD = "com.kompi.app.test.STAGE_PUSH_PAYLOAD";
    public static final String ACTION_CLEAR_STAGED = "com.kompi.app.test.CLEAR_STAGED_PUSH";
    public static final String ACTION_REGISTRATION_STATUS = "com.kompi.app.test.PUSH_REGISTRATION_STATUS";
    public static final String EXTRA_CIPHERTEXT = "ciphertext";
    public static final String ACK_STAGED = "staged";
    public static final String ACK_CLEARED = "cleared";
    public static final String ACK_DELIVERED = "delivered";
    public static final String ACK_REGISTERED = "registered";
    public static final String ACK_NOT_REGISTERED = "not_registered";
    public static final String PREFERENCES = "fake_unified_push_distributor";

    private static final String ACK_REJECTED = "rejected";
    private static final String TARGET_PACKAGE = "com.kompi.app";
    private static final int MAX_CIPHERTEXT_BYTES = 4096;
    private static final String ACTION_REGISTER = "org.unifiedpush.android.distributor.REGISTER";
    private static final String ACTION_NEW_ENDPOINT = "org.unifiedpush.android.connector.NEW_ENDPOINT";
    private static final String ACTION_MESSAGE = "org.unifiedpush.android.connector.MESSAGE";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (ACTION_REGISTER.equals(action)) {
            register(context, intent);
        } else if (ACTION_STAGE_PAYLOAD.equals(action)) {
            stagePayload(context, intent);
        } else if (ACTION_CLEAR_STAGED.equals(action)) {
            clearStaged(context, intent);
        } else if (ACTION_DELIVER_STAGED.equals(action)) {
            deliverStaged(context, intent);
        } else if (ACTION_REGISTRATION_STATUS.equals(action)) {
            String application = intent.getStringExtra("application");
            if (!TARGET_PACKAGE.equals(application)) {
                setResult(Activity.RESULT_CANCELED, ACK_REJECTED, null);
                return;
            }
            boolean registered = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                    .contains("token:" + application);
            // Report only whether the SDK's REGISTER arrived, never its private token or endpoint.
            setResult(Activity.RESULT_OK, registered ? ACK_REGISTERED : ACK_NOT_REGISTERED, null);
        }
    }

    private void register(Context context, Intent intent) {
        String application = intent.getStringExtra("application");
        String token = intent.getStringExtra("token");
        if (application == null || token == null) return;
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .putString("token:" + application, token).commit();
        context.sendBroadcast(new Intent(ACTION_NEW_ENDPOINT)
                .setPackage(application)
                .putExtra("token", token)
                .putExtra("endpoint", "https://push.test.invalid/" + UUID.randomUUID()));
    }

    private void stagePayload(Context context, Intent intent) {
        String application = intent.getStringExtra("application");
        String token = application == null ? null : context
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString("token:" + application, null);
        byte[] payload = intent.getByteArrayExtra(EXTRA_CIPHERTEXT);
        if (!TARGET_PACKAGE.equals(application) || token == null || token.trim().isEmpty()
                || payload == null || payload.length < 1 || payload.length > MAX_CIPHERTEXT_BYTES) {
            if (TARGET_PACKAGE.equals(application)) {
                context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                        .remove("payload:" + application).commit();
            }
            setResult(Activity.RESULT_CANCELED, ACK_REJECTED, null);
            return;
        }
        String encoded = Base64.encodeToString(payload, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        boolean stored = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .putString("payload:" + application, encoded).commit();
        setResult(stored ? Activity.RESULT_OK : Activity.RESULT_CANCELED,
                stored ? ACK_STAGED : ACK_REJECTED, null);
    }

    private void clearStaged(Context context, Intent intent) {
        String application = intent.getStringExtra("application");
        if (!TARGET_PACKAGE.equals(application)) {
            setResult(Activity.RESULT_CANCELED, ACK_REJECTED, null);
            return;
        }
        boolean cleared = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .remove("payload:" + application)
                .remove("token:" + application)
                .commit();
        setResult(cleared ? Activity.RESULT_OK : Activity.RESULT_CANCELED,
                cleared ? ACK_CLEARED : ACK_REJECTED, null);
    }

    private void deliverStaged(Context context, Intent intent) {
        String application = intent.getStringExtra("application");
        if (!TARGET_PACKAGE.equals(application)) {
            setResult(Activity.RESULT_CANCELED, ACK_REJECTED, null);
            return;
        }
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        String token = prefs.getString("token:" + application, null);
        String encoded = prefs.getString("payload:" + application, null);
        if (token == null || encoded == null) {
            setResult(Activity.RESULT_CANCELED, ACK_REJECTED, null);
            return;
        }
        final byte[] payload;
        try {
            payload = Base64.decode(encoded, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        } catch (IllegalArgumentException invalidBase64) {
            setResult(Activity.RESULT_CANCELED, ACK_REJECTED, null);
            return;
        }
        if (payload.length < 1 || payload.length > MAX_CIPHERTEXT_BYTES) {
            setResult(Activity.RESULT_CANCELED, ACK_REJECTED, null);
            return;
        }
        context.sendBroadcast(new Intent(ACTION_MESSAGE)
                .setPackage(application)
                .putExtra("token", token)
                .putExtra("bytesMessage", payload)
                .putExtra("id", "cold-start-" + System.currentTimeMillis()));
        // This acknowledges only distributor dispatch, never SDK decrypt or notification posting.
        setResult(Activity.RESULT_OK, ACK_DELIVERED, null);
    }
}
