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

import android.content.Context;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.PowerManager.WakeLock;
import android.util.Log;

public class WakeLockManager {
    private static final String TAG = "PowerManagerhandling";
    private static final boolean DBG = true;

    private Context mContext;
    private PowerManager mPowerManager;
    private WakeLock mFullWakeLock;
    private WakeLock mPartialWakeLock;

    WakeLockManager(Context context) {
        mContext = context;

        // before registering for phone state changes
        mPowerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mFullWakeLock = mPowerManager.newWakeLock(PowerManager.FULL_WAKE_LOCK, TAG);
        // lock used to keep the processor awake, when we don't care for the display.
        mPartialWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK
                | PowerManager.ON_AFTER_RELEASE, TAG);
    }

    /* package */void requestFullWakeLock() {
        synchronized (this) {
            if (DBG) {
                Log.d(TAG, "requestFullWakeLock, full wake lock held:" + mFullWakeLock.isHeld()
                        + " ,wake lock held:" + mPartialWakeLock.isHeld());
            }

            if (!mFullWakeLock.isHeld()) {
                mFullWakeLock.acquire();
            }

            if (mPartialWakeLock.isHeld()) {
                mPartialWakeLock.release();
            }
        }
    }

    /* package */void requestPartialWakeLock() {
        synchronized (this) {
            if (DBG) {
                Log.d(TAG, "requestPartialWakeLock, full wake lock held:" + mFullWakeLock.isHeld()
                        + " ,wake lock held:" + mPartialWakeLock.isHeld());
            }

            // acquire the processor wake lock, and release the FULL
            // lock if it is being held.
            if (!mPartialWakeLock.isHeld()) {
                mPartialWakeLock.acquire();
            }

            if (mFullWakeLock.isHeld()) {
                mFullWakeLock.release();
            }
        }
    }

    /* package */void releaseWakeLock() {
        synchronized (this) {
            if (DBG) {
                Log.d(TAG, "releaseWakeLock, full wake lock held:" + mFullWakeLock.isHeld()
                        + " ,wake lock held:" + mPartialWakeLock.isHeld());
            }

            // release both the PARTIAL and FULL locks.
            if (mFullWakeLock.isHeld()) {
                mFullWakeLock.release();
            }

            if (mPartialWakeLock.isHeld()) {
                mPartialWakeLock.release();
            }
        }
    }

    /**
     * If we are not currently keeping the screen on, then poke the power
     * manager to wake up the screen for the user activity timeout duration.
     */
    /* package */ void wakeUpScreen() {
        synchronized (this) {
            if (DBG) {
                Log.d(TAG, "pulse screen lock, full wake lock held:"
                        + mFullWakeLock.isHeld()
                        + " ,wake lock held:"
                        + mPartialWakeLock.isHeld());
            }

            if (!(mFullWakeLock.isHeld() || mPartialWakeLock.isHeld())) {
                mPowerManager.wakeUp(SystemClock.uptimeMillis());
            }
        }
    }
}
