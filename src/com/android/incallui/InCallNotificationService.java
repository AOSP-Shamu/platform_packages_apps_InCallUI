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

import com.android.internal.telephony.ITelephonyService;
import com.android.internal.telephony.ITelephonyServiceCallBack;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import com.android.internal.telephony.PhoneConstants;

public class InCallNotificationService extends Service{

    private final static boolean DBG = true;
    private final static String TAG = "InCallNotificationService";
    // notification types
    private static final int IN_CALL_NOTIFICATION = 1;

    private final static String ACTION_START_TELEPHONY_SERVICE =
            "android.intent.action.TELEPHONY_SERVICE";
    static final String ACTION_HANG_UP_ONGOING_CALL =
            "com.android.telephony.ACTION_HANG_UP_ONGOING_CALL";
    static final String UPDATE_TYPE = "UPDATE_TYPE";

    private NotificationManager mNotificationManager;
    private boolean mShowingMuteIcon;

    private StatusBarManager mStatusBarManager;
    private boolean mShowingSpeakerphoneIcon;
    // Currently-displayed resource IDs for some status bar icons (or zero
    // if no notification is active):
    private int mInCallResId;

    boolean mMuteState = false;
    CallerInfoCookie mForegroundCallerInfo;
    //TODO this need to reconsider include call time
    long mForegroundCallStartTime = 0;
    // TODO all the call state check shoule be in updateNotification, this is just temp solution.
    PhoneConstants.State mTempState = PhoneConstants.State.IDLE;
    public static ITelephonyService mTelephonyService = null;

    //TODO temp solution, don't think it's good idea for UI to maintain a call state. it should be
    //     get from somewhere
    boolean mHasRingingCall = false;
    boolean mHasActiveCall = false;
    boolean mHasHoldingCall = false;

    private final ServiceConnection mTelephonyServiceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            log("telephony service connected");
            mTelephonyService = ITelephonyService.Stub.asInterface(service);
            // We want to monitor the service for as long as we are
            // connected to it.
            try {
                mTelephonyService.registerCallback(mCallBack);
            } catch (RemoteException e) {
                Log.e(TAG, "register callback fail...");
            }
        }

        public void onServiceDisconnected(ComponentName className) {
            log("telephony service disconnected");
            mTelephonyService = null;
        }
    };

    public static final int MSG_UPDATE_NOTIFICATION = 30;

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_UPDATE_NOTIFICATION:
                    updateInCallNotification();
                    break;
            }
        }
    };

    private ITelephonyServiceCallBack mCallBack = new ITelephonyServiceCallBack.Stub() {
        public void onForegroundCallerInfoUpdated(String name, String number, String typeofnumber,
                Bitmap photo, int presentation) {
            mForegroundCallerInfo = new CallerInfoCookie(name, number, typeofnumber,
                    photo, presentation);
            if (mTempState == PhoneConstants.State.OFFHOOK)
                mHandler.sendEmptyMessage(MSG_UPDATE_NOTIFICATION);
        }

        public void onBackgroundCallerInfoUpdated(String name, String number, String typeofnumber,
                Bitmap photo, int presentation) {
        }

        public void onAllCallsDisconnected(int cause) {
            Log.i(TAG,"onAllCallsDisconnected");
            mTempState = PhoneConstants.State.IDLE;
            mForegroundCallerInfo = null;
            mMuteState = false;
            mHasRingingCall = false;
            mHasActiveCall = false;
            mHasHoldingCall = false;
            mHandler.sendEmptyMessage(MSG_UPDATE_NOTIFICATION);
        }

        public void onElapsedTimeUpdated(String elapsedTime) {}

        public void onSoundRouted(int audioRoute) {
            mHandler.sendEmptyMessage(MSG_UPDATE_NOTIFICATION);
        }

        public void onMicMuteStateChange(boolean isMuted) {
            mMuteState = isMuted;
            mHandler.sendEmptyMessage(MSG_UPDATE_NOTIFICATION);
        }

        public void onIncomming(boolean isWaiting){
            mTempState = PhoneConstants.State.RINGING;
            mHasRingingCall = true;
            mHandler.sendEmptyMessage(MSG_UPDATE_NOTIFICATION);
        }

        public void onOutgoing(boolean isAlerting) {
            mHandler.sendEmptyMessage(MSG_UPDATE_NOTIFICATION);
        }

        public void onHold(boolean isVoiceMail, boolean isConference){
            mHasActiveCall = false;
            mHasHoldingCall = true;
            mHandler.sendEmptyMessage(MSG_UPDATE_NOTIFICATION);
        }

        public void onActive(boolean hasBackgroundCalls, boolean isEmergency, boolean isVoiceMail,
            boolean isConference) {
            // Post a request to update the "in-call" status bar icon.
            //
            // We don't call NotificationMgr.updateInCallNotification()
            // directly here, for two reasons:
            // (1) a single phone state change might actually trigger multiple
            //   onPhoneStateChanged() callbacks, so this prevents redundant
            //   updates of the notification.
            // (2) we suppress the status bar icon while the in-call UI is
            //   visible (see updateInCallNotification()).  But when launching
            //   an outgoing call the phone actually goes OFFHOOK slightly
            //   *before* the InCallScreen comes up, so the delay here avoids a
            //   brief flicker of the icon at that point.

            mTempState = PhoneConstants.State.OFFHOOK;
            mForegroundCallStartTime = System.currentTimeMillis();
            if (DBG) log("- posting UPDATE_IN_CALL_NOTIFICATION request...");
            mHasActiveCall = true;
            mHasRingingCall = false;
            mHasHoldingCall = false;
            // Remove any previous requests in the queue
            mHandler.removeMessages(MSG_UPDATE_NOTIFICATION);
            final int IN_CALL_NOTIFICATION_UPDATE_DELAY = 1000;
            mHandler.sendEmptyMessageDelayed(MSG_UPDATE_NOTIFICATION,
                                    IN_CALL_NOTIFICATION_UPDATE_DELAY);
        }

        public void onDisconnecting(){}
    };

    @Override
    public final int onStartCommand(final Intent intent, final int flags,
            final int startId) {
        super.onStartCommand(intent, flags, startId);
        if (mTelephonyService == null) {

            Intent serviceIntent = new Intent(ACTION_START_TELEPHONY_SERVICE);
            bindService(serviceIntent, mTelephonyServiceConnection, Context.BIND_AUTO_CREATE);

            mNotificationManager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            mStatusBarManager =
                    (StatusBarManager) getSystemService(Context.STATUS_BAR_SERVICE);
        }
        if (mTelephonyService == null) {
            Log.i(TAG,"mCallService is null");
            return Service.START_STICKY;
        }

        if (intent != null)
            mHandler.sendEmptyMessage(MSG_UPDATE_NOTIFICATION);
        return Service.START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (mTelephonyService != null) {
            try {
                mTelephonyService.unregisterCallback(mCallBack);
            } catch (RemoteException e) {
                // There is nothing special we need to do if the service
                // has crashed.
            }
        }
        unbindService(mTelephonyServiceConnection);
        super.onDestroy();
    }

    public InCallNotificationService() {
        // TODO Auto-generated constructor stub
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * Helper method for updateInCallNotification() and
     * updateNotificationAndLaunchIncomingCallUi(): Update the phone app's
     * status bar notification based on the current telephony state, or
     * cancels the notification if the phone is totally idle.
     *
     * @param allowFullScreenIntent If true, *and* an incoming call is
     *   ringing, the notification will include a "fullScreenIntent"
     *   pointing at the InCallScreen (which will cause the InCallScreen
     *   to be launched.)
     *   Watch out: This should be set to true *only* when directly
     *   handling the "new ringing connection" event from the telephony
     *   layer (see updateNotificationAndLaunchIncomingCallUi().)
     */
    private void updateInCallNotification() {
        int resId;

        // If the phone is idle, completely clean up all call-related
        // notifications.
//        PhoneConstants.State state = PhoneConstants.State.OFFHOOK;
        if (mTempState == PhoneConstants.State.IDLE) {
            cancelInCall();
            cancelMute();
            cancelSpeakerphone();
            return;
        }

        // Suppress the in-call notification if the InCallScreen is the
        // foreground activity, since it's already obvious that you're on a
        // call.  (The status bar icon is needed only if you navigate *away*
        // from the in-call UI.)
        boolean suppressNotification = false;
        // if (DBG) log("- suppressNotification: initial value: " + suppressNotification);

        //   - If "voice privacy" mode is active: always show the notification,
        //     since that's the only "voice privacy" indication we have.
        boolean enhancedVoicePrivacy = false;
        // TODO need to get from service
/*        try {
            enhancedVoicePrivacy = mCallService.getVoicePrivacyState();
        } catch (RemoteException re) {
            re.printStackTrace();
        }*/

        if (DBG) log("updateInCallNotification: enhancedVoicePrivacy = " + enhancedVoicePrivacy);
        if (enhancedVoicePrivacy) suppressNotification = false;

        if (suppressNotification) {
            if (DBG) log("- suppressNotification = true; reducing clutter in status bar...");
            cancelInCall();
            // Suppress the mute and speaker status bar icons too
            // (also to reduce clutter in the status bar.)
            cancelSpeakerphone();
            cancelMute();
            return;
        }

        if (mHasRingingCall) {
            // There's an incoming ringing call.
            resId = R.drawable.stat_sys_phone_call;
        } else if (!mHasActiveCall && mHasHoldingCall) {
            // There's only one call, and it's on hold.
            if (enhancedVoicePrivacy) {
                resId = R.drawable.stat_sys_vp_phone_call_on_hold;
            } else {
                resId = R.drawable.stat_sys_phone_call_on_hold;
            }
        } else {
            if (enhancedVoicePrivacy) {
                resId = R.drawable.stat_sys_vp_phone_call;
            } else {
                resId = R.drawable.stat_sys_phone_call;
            }
        }

        // Note we can't just bail out now if (resId == mInCallResId),
        // since even if the status icon hasn't changed, some *other*
        // notification-related info may be different from the last time
        // we were here (like the caller-id info of the foreground call,
        // if the user swapped calls...)

        if (DBG) log("- Updating status bar icon: resId = " + resId);
        mInCallResId = resId;

        final Notification.Builder builder = new Notification.Builder(this);
        builder.setSmallIcon(mInCallResId).setOngoing(true);

        // PendingIntent that can be used to launch the InCallScreen.  The
        // system fires off this intent if the user pulls down the windowshade
        // and clicks the notification's expanded view.  It's also used to
        // launch the InCallScreen immediately when when there's an incoming
        // call (see the "fullScreenIntent" field below).
        PendingIntent inCallPendingIntent =
                PendingIntent.getActivity(this, 0, createInCallIntent(), 0);
        builder.setContentIntent(inCallPendingIntent);

        // this block tries to set Icon. the bitmap should from callerinfo.
        // TODO need to makes query works.
        String expandedViewLine2 = "";
        if(mForegroundCallerInfo != null) {
            builder.setLargeIcon(mForegroundCallerInfo.photo);
            expandedViewLine2 = mForegroundCallerInfo.name != null ?
                mForegroundCallerInfo.name : mForegroundCallerInfo.number;
        } else {
            try {
                mTelephonyService.requestForegroundCallerInfo(mCallBack);
            } catch (RemoteException re) {
                re.printStackTrace();
            }
        }
        // If the connection is valid, then build what we need for the
        // content text of notification, and start the chronometer.
        // Otherwise, don't bother and just stick with content title.
        // This block updates the ContentText
        boolean isConnectionNull = false;
        // TODO this should be state from callservice
/*        try{
        isConnectionNull = mCallService.isConntionNull();
        } catch (RemoteException re) {
            re.printStackTrace();
        }*/

        if (!isConnectionNull) {
            if (DBG) log("- Updating context text and chronometer.");
            if (mHasRingingCall) {
                // Incoming call is ringing.
                builder.setContentText(getString(R.string.notification_incoming_call));
                builder.setUsesChronometer(false);
            } else if (mHasHoldingCall && !mHasActiveCall) {
                // Only one call, and it's on hold.
                builder.setContentText(getString(R.string.notification_on_hold));
                builder.setUsesChronometer(false);
            } else {
                // We show the elapsed time of the current call using Chronometer.
                builder.setUsesChronometer(true);
                builder.setWhen(mForegroundCallStartTime);

                int contextTextId = R.string.notification_ongoing_call;

                boolean isDialing = false;
                boolean canDial = false;
                // TODO those state should be got from callservice
//                if (canDial && isDialing) {
//                  contextTextId = R.string.notification_dialing;
//                }

                builder.setContentText(getString(contextTextId));
            }
        } else if (DBG) {
            Log.w(TAG, "updateInCallNotification: null connection, can't set exp view line 1.");
        }

        if (DBG) log("- Updating expanded view: line 2 '" + /*expandedViewLine2*/ "xxxxxxx" + "'");
        builder.setContentTitle(expandedViewLine2);

        // TODO: We also need to *update* this notification in some cases,
        // like when a call ends on one line but the other is still in use
        // (ie. make sure the caller info here corresponds to the active
        // line), and maybe even when the user swaps calls (ie. if we only
        // show info here for the "current active call".)

        // Activate a couple of special Notification features if an
        // incoming call is ringing:
        if (mHasRingingCall) {
            if (DBG) log("- Using hi-pri notification for ringing call!");

            // This is a high-priority event that should be shown even if the
            // status bar is hidden or if an immersive activity is running.
            builder.setPriority(Notification.PRIORITY_HIGH);

            // If an immersive activity is running, we have room for a single
            // line of text in the small notification popup window.
            // We use expandedViewLine2 for this (i.e. the name or number of
            // the incoming caller), since that's more relevant than
            // expandedViewLine1 (which is something generic like "Incoming
            // call".)
            builder.setTicker(expandedViewLine2);
        } else { // not ringing call
            // Make the notification prioritized over the other normal notifications.
            builder.setPriority(Notification.PRIORITY_HIGH);

            // TODO: use "if (DBG)" for this comment.
            log("Will show \"hang-up\" action in the ongoing active call Notification");
            // TODO: use better asset.
            builder.addAction(R.drawable.stat_sys_phone_call_end,
                    getText(R.string.notification_action_end_call),
                    //TODO
                    createHangUpOngoingCallPendingIntent(this));
        }

        Notification notification = builder.getNotification();
        if (DBG) log("Notifying IN_CALL_NOTIFICATION: " + notification);
        mNotificationManager.notify(IN_CALL_NOTIFICATION, notification);

        // Finally, refresh the mute and speakerphone notifications (since
        // some phone state changes can indirectly affect the mute and/or
        // speaker state).
        updateSpeakerNotification();
        updateMuteNotification();
    }

    /**
     * Take down the in-call notification.
     * @see updateInCallNotification()
     */
    private void cancelInCall() {
        if (DBG) log("cancelInCall()...");
        mNotificationManager.cancel(IN_CALL_NOTIFICATION);
        mInCallResId = 0;
    }

    private void notifyMute() {
        if (!mShowingMuteIcon) {
            mStatusBarManager.setIcon("mute", android.R.drawable.stat_notify_call_mute, 0,
                    getString(R.string.accessibility_call_muted));
            mShowingMuteIcon = true;
        }
    }

    private void cancelMute() {
        if (mShowingMuteIcon) {
            mStatusBarManager.removeIcon("mute");
            mShowingMuteIcon = false;
        }
    }
    /**
     * Shows or hides the "mute" notification in the status bar,
     * based on the current mute state of the Phone.
     *
     * (But note that the status bar icon is *never* shown while the in-call UI
     * is active; it only appears if you bail out to some other activity.)
     */
    void updateMuteNotification() {
        // Suppress the status bar icon if the the InCallScreen is the
        // foreground activity, since the in-call UI already provides an
        // onscreen indication of the mute state.  (This reduces clutter
        // in the status bar.)
     // TODO: mApp.isShowingCallScreen() this need to be updated from CallActivity
/*        CallActivity callActivity = CallActivity.getInstance();
        if (callActivity != null && callActivity.isForegroundActivity()) {
            cancelMute();
            return;
        }*/
//TODO PhoneConstants.State.OFFHOOK
        PhoneConstants.State state = PhoneConstants.State.OFFHOOK;

/*       try {
            //TODO this state should get from mcallservice
//            state = mCallService.getStateFromCallManager();
            mMuteState = mCallService.getMute();
        } catch (RemoteException re) {
            re.printStackTrace();
        }*/
        if ((state == PhoneConstants.State.OFFHOOK) && mMuteState) {
            if (DBG) log("updateMuteNotification: MUTED");
            notifyMute();
        } else {
            if (DBG) log("updateMuteNotification: not muted (or not offhook)");
            cancelMute();
        }
    }

    private void notifySpeakerphone() {
        if (!mShowingSpeakerphoneIcon) {
            mStatusBarManager.setIcon("speakerphone", android.R.drawable.stat_sys_speakerphone, 0,
                    getString(R.string.accessibility_speakerphone_enabled));
            mShowingSpeakerphoneIcon = true;
        }
    }

    private void cancelSpeakerphone() {
        if (mShowingSpeakerphoneIcon) {
            mStatusBarManager.removeIcon("speakerphone");
            mShowingSpeakerphoneIcon = false;
        }
    }

    /**
     * Shows or hides the "speakerphone" notification in the status bar,
     * based on the actual current state of the speaker.
     *
     * If you already know the current speaker state (e.g. if you just
     * called AudioManager.setSpeakerphoneOn() yourself) then you should
     * directly call {@link #updateSpeakerNotification(boolean)} instead.
     *
     * (But note that the status bar icon is *never* shown while the in-call UI
     * is active; it only appears if you bail out to some other activity.)
     */
    private void updateSpeakerNotification() {
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        // TODO state should get from callservice
        PhoneConstants.State state = PhoneConstants.State.OFFHOOK;
//        try {
//            state = mCallService.getStateFromPhone();
//        } catch (RemoteException re) {
//            re.printStackTrace();
//        }
        boolean showNotification =
//                TODO PhoneConstants.State.OFFHOOK
                (state == PhoneConstants.State.OFFHOOK) && audioManager.isSpeakerphoneOn();

        if (DBG) log(showNotification
                     ? "updateSpeakerNotification: speaker ON"
                     : "updateSpeakerNotification: speaker OFF (or not offhook)");

        updateSpeakerNotification(showNotification);
    }

    /**
     * Shows or hides the "speakerphone" notification in the status bar.
     *
     * @param showNotification if true, call notifySpeakerphone();
     *                         if false, call cancelSpeakerphone().
     *
     * Use {@link updateSpeakerNotification()} to update the status bar
     * based on the actual current state of the speaker.
     *
     * (But note that the status bar icon is *never* shown while the in-call UI
     * is active; it only appears if you bail out to some other activity.)
     */
    public void updateSpeakerNotification(boolean showNotification) {
        if (DBG) log("updateSpeakerNotification(" + showNotification + ")...");

        // Regardless of the value of the showNotification param, suppress
        // the status bar icon if the the InCallScreen is the foreground
        // activity, since the in-call UI already provides an onscreen
        // indication of the speaker state.  (This reduces clutter in the
        // status bar.)
        // TODO: mApp.isShowingCallScreen() this need to be updated from CallActivity

        if (showNotification) {
            notifySpeakerphone();
        } else {
            cancelSpeakerphone();
        }
    }

    /**
     * Return an Intent that can be used to bring up the in-call screen.
     *
     * This intent can only be used from within the Phone app, since the
     * InCallScreen is not exported from our AndroidManifest.
     */
    /* package */ static Intent createInCallIntent() {
        Intent intent = new Intent(Intent.ACTION_MAIN, null);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        intent.setClassName("com.android.incallui", InCallUIActivity.class.getName());
        return intent;
    }

    /**
     * Returns PendingIntent for hanging up ongoing phone call. This will typically be used from
     * Notification context.
     */
    /* package */ static PendingIntent createHangUpOngoingCallPendingIntent(Context context) {
        // TODO this is different with origin way, need more check.
        Intent intent = new Intent(ACTION_HANG_UP_ONGOING_CALL);
        return PendingIntent.getBroadcast(context, 0, intent, 0);
    }

    public void log(String message) {
        Log.i(TAG, message);
    }
    /**
     * Accepts broadcast Intents which will be prepared by {@link NotificationMgr} and thus
     * sent from framework's notification mechanism (which is outside Phone context).
     * This should be visible from outside, but shouldn't be in "exported" state.
     *
     * TODO: If possible merge this into PhoneAppBroadcastReceiver.
     */
    public static class NotificationBroadcastReceiver extends BroadcastReceiver {
        static final String LOG_TAG = "NotificationBroadcastReceiver";
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            // TODO: use "if (VDBG)" here.
            Log.d(LOG_TAG, "Broadcast from Notification: " + action);

            if (action.equals(ACTION_HANG_UP_ONGOING_CALL)) {
                try {
                    mTelephonyService.hangupCall();
                } catch (Exception e) {
                    e.printStackTrace();
                }

            }
        }
    }
}
