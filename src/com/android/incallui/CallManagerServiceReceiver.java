/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.incallui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

public class CallManagerServiceReceiver extends BroadcastReceiver {
    private static final String LOG_TAG = "CallManagerServiceReceiver";

    static final String ACTION_TELEPHONY_SERVICE_STATE_CHANGED =
            "com.android.telephony.TELEPHONY_SERVICE_STATE_CHANGED";
    static final String EXTRA_TELEPHONY_SERVICE_STATE = "TELEPHONY_SERVICE_STATE";

    /* State of TelephonyServcie */
    // The service is started but idle, I.E handles no calls.
    static final int TELEPHONY_SERVICE_STATE_IDLE = 0;
    // The service is handling calls.
    static final int TELEPHONY_SERVICE_STATE_ACTIVE = 1;
    // The service is stopped.
    static final int TELEPHONY_SERVICE_STATE_STOPPED = 2;

    @Override
    public void onReceive(Context context, Intent intent) {
        // Below "ui" check is just temporary for demo purpose and will be removed soon.
        int ui = intent.getIntExtra("ui", 0);
        if (ui != 2) {
            String action = intent.getAction();
            int serviceState = intent.getIntExtra(EXTRA_TELEPHONY_SERVICE_STATE,
                    TELEPHONY_SERVICE_STATE_IDLE);

            Log.i(LOG_TAG, "action: " + action + " service state: " + serviceState);

            if (ACTION_TELEPHONY_SERVICE_STATE_CHANGED.equals(action)
                    && serviceState == TELEPHONY_SERVICE_STATE_ACTIVE) {
                Log.i(LOG_TAG, "launch UI");
                Intent startIntent = new Intent(Intent.ACTION_MAIN, null);
                startIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                        | Intent.FLAG_ACTIVITY_NO_USER_ACTION
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startIntent.setClassName("com.android.incallui", InCallUIActivity.class.getName());
                startIntent.putExtras(intent);
                context.startActivity(startIntent);

                Intent serviceIntent = new Intent();
                serviceIntent.setClassName("com.android.incallui",
                        InCallNotificationService.class.getName());
                context.startService(serviceIntent);
            }
        }
    }

}
