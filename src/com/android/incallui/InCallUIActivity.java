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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.PowerManager;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewPropertyAnimator;
import android.view.ViewStub;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.android.internal.telephony.Connection.DisconnectCause;
import com.android.internal.widget.multiwaveview.GlowPadView;
import com.android.internal.widget.multiwaveview.GlowPadView.OnTriggerListener;

public class InCallUIActivity extends Activity implements View.OnClickListener,
        View.OnLongClickListener {
    final static boolean DBG = true;
    private final static String LOG_TAG = "InCallUIActivity";

    private final static String ACTION_START_TELEPHONY_SERVICE =
            "android.intent.action.TELEPHONY_SERVICE";

    final static int SCREEN_TYPE_IDLE = 0;
    final static int SCREEN_TYPE_OUT_GOING = 1; // Dialing
    final static int SCREEN_TYPE_IN_COMMING = 2; // Incomming call
    final static int SCREEN_TYPE_HOLDING = 3; // On hold
    final static int SCREEN_TYPE_COFERENCE = 4; // Coference call
    final static int SCREEN_TYPE_DISCONNECTING = 5; // Call ending
    final static int SCREEN_TYPE_DISCONNECTED = 6; // Call ended
    final static int SCREEN_TYPE_ON_GOING = 7;

    private int mCurrentScreen;

    private static final String MUTE_STATE = "MUTE_STATE";

    private ITelephonyService mTelephonyService = null;

    // Top-level subviews of the CallCard
    /** Container for info about the current call(s) */
    private ViewGroup mCallInfoContainer;
    /** Primary "call info" block (the foreground or ringing call) */
    private ViewGroup mPrimaryCallInfo;
    /** "Call banner" for the primary call */
    private ViewGroup mPrimaryCallBanner;
    /** Secondary "call info" block (the background "on hold" call) */
    private ViewStub mSecondaryCallInfo;

    /**
     * Container for both provider info and call state. This will take care of showing/hiding
     * animation for those views.
     */
    private ViewGroup mSecondaryInfoContainer;
    private ViewGroup mProviderInfo;
    private TextView mProviderLabel;
    private TextView mProviderAddress;

    // "Call state" widgets
    private TextView mCallStateLabel;
    private TextView mElapsedTime;

    // Text colors, used for various labels / titles
    private int mTextColorCallTypeSip;

    private boolean mIsResumed;

    // The main block of info about the "primary" or "active" call,
    // including photo / name / phone number / etc.
    private ImageView mPhoto;
    private View mPhotoDimEffect;

    private TextView mName;
    private TextView mPhoneNumber;
    private TextView mLabel;
    private TextView mCallTypeLabel;

    /** UI elements while on a regular call (bottom buttons, DTMF dialpad) */
    private View mInCallControls;

    // UI containers / elements
    private GlowPadView mIncomingCallWidget;  // UI used for an incoming call
    private boolean mIncomingCallWidgetIsFadingOut;
    private boolean mShowInCallControlsDuringHidingAnimation;
    private boolean mIncomingCallWidgetShouldBeReset = true;

    // DTMF Dialer controller and its view:
    private DTMFTwelveKeyDialer mDialer;

    private WakeLockManager mWakeLockManager;

    private static final int MSG_UPDATE_SCREEN = 0;
    private static final int MSG_FINISH = 1;
    private static final int MSG_SOUND_ROUTED = 2;
    private static final int MSG_UPDATE_ELAPSED_TIME = 3;
    private static final int MSG_MUTE_STATE_CHANGED = 4;

    private static final int MSG_SHOW_INCOMMING_SCREEN = 5;
    private static final int MSG_SHOW_OUTGOING_SCREEN = 6;
    private static final int MSG_SHOW_ONGOING_SCREEN = 7;
    private static final int MSG_SHOW_ONHOLD_SCREEN = 8;
    private static final int MSG_SHOW_DISCONNECTING_SCREEN = 9;
    private static final int MSG_SHOW_DISCONNECTED_SCREEN = 10;
    private static final int MSG_ON_FOREGROUND_CALLER_INFO_UPDATED = 11;
    private static final int MSG_ON_BACKGROUND_CALLER_INFO_UPDATED = 12;

    private static final int MSG_INCOMING_CALL_WIDGET_PING = 15;

    // Parameters for the GlowPadView "ping" animation; see triggerPing().
    private static final boolean ENABLE_PING_ON_RING_EVENTS = false;
    private static final boolean ENABLE_PING_AUTO_REPEAT = true;

    private static final long PING_AUTO_REPEAT_DELAY_MSEC = 1200;

    // Incoming call widget targets
    private static final int TRIGGER_ANSWER_CALL_ID = 0;  // drag right
    private static final int TRIGGER_DECLINE_CALL_ID = 2;  // drag left

    private StatusBarManager mStatusBarManager;
    StatusBarHelper mStatusBarHelper;
    private boolean mProximityEnabled;

    private class ActiveCallCookie {
        boolean hasBackgroundCall;
        boolean isEmergency;
        boolean isVoiceMail;
        boolean isConference;

        public ActiveCallCookie(boolean hasBackgroundCall, boolean isEmergency,
                boolean isVoiceMail, boolean isConference) {
            this.hasBackgroundCall = hasBackgroundCall;
            this.isEmergency = isEmergency;
            this.isVoiceMail = isVoiceMail;
            this.isConference = isConference;
        }
    }

    private class HoldCallCookie {
        boolean isVoiceMail;
        boolean isConference;

        public HoldCallCookie(boolean isVoiceMail, boolean isConference) {
            this.isVoiceMail = isVoiceMail;
            this.isConference = isConference;
        }
    }

    /**
     * This implementation is used to receive callbacks from CallService
     */
    private ITelephonyServiceCallBack mCallBack = new ITelephonyServiceCallBack.Stub() {
        public void onSoundRouted(int audioRoute) {
            mHandler.removeMessages(MSG_SOUND_ROUTED);
            Message message = mHandler.obtainMessage(MSG_SOUND_ROUTED);
            message.arg1 = audioRoute;
            message.sendToTarget();
        }

        public void onElapsedTimeUpdated(String elapsedTime) {
            mHandler.sendMessage(mHandler.obtainMessage(MSG_UPDATE_ELAPSED_TIME, elapsedTime));
        }

        public void onMicMuteStateChange(boolean newMuteState) {
            mHandler.removeMessages(MSG_MUTE_STATE_CHANGED);
            Message message = mHandler.obtainMessage(MSG_MUTE_STATE_CHANGED);
            Bundle bundle = new Bundle();
            bundle.putBoolean(MUTE_STATE, newMuteState);
            message.setData(bundle);
            message.sendToTarget();
        }

        public void onIncomming(boolean isWaiting) {
            mHandler.removeMessages(MSG_SHOW_INCOMMING_SCREEN);
            mHandler.sendMessage(mHandler.obtainMessage(MSG_SHOW_INCOMMING_SCREEN,
                    Boolean.valueOf(isWaiting)));
        }

        public void onOutgoing(boolean isAlerting) {
            mHandler.removeMessages(MSG_SHOW_OUTGOING_SCREEN);
            mHandler.sendMessage(mHandler.obtainMessage(MSG_SHOW_OUTGOING_SCREEN,
                    Boolean.valueOf(isAlerting)));
        }

        public void onActive(boolean hasBackgroundCalls, boolean isEmergency,
                boolean isVoiceMail, boolean isConference) {
            mHandler.removeMessages(MSG_SHOW_ONGOING_SCREEN);
            mHandler.sendMessage(mHandler.obtainMessage(MSG_SHOW_ONGOING_SCREEN,
                    new ActiveCallCookie(hasBackgroundCalls, isEmergency,isVoiceMail,
                        isConference)));
        }

        public void onHold(boolean isVoiceMail, boolean isConference) {
            mHandler.removeMessages(MSG_SHOW_ONHOLD_SCREEN);
            mHandler.sendMessage(mHandler.obtainMessage(MSG_SHOW_ONHOLD_SCREEN,
                    new HoldCallCookie(isVoiceMail, isConference)));
        }

        public void onDisconnecting() {
            mHandler.removeMessages(MSG_SHOW_DISCONNECTING_SCREEN);
            mHandler.sendMessage(mHandler.obtainMessage(MSG_SHOW_DISCONNECTING_SCREEN));
        }

        public void onAllCallsDisconnected(int cause) {
            mHandler.removeMessages(MSG_SHOW_DISCONNECTED_SCREEN);
            mHandler.sendMessage(mHandler.obtainMessage(MSG_SHOW_DISCONNECTED_SCREEN, cause, 0));
        }

        public void onForegroundCallerInfoUpdated(String name, String number,
                String typeofnumber, Bitmap photo, int presentation) {
            mHandler.removeMessages(MSG_ON_FOREGROUND_CALLER_INFO_UPDATED);
            mHandler.sendMessage(mHandler.obtainMessage(MSG_ON_FOREGROUND_CALLER_INFO_UPDATED,
                    new CallerInfoCookie(name, number,typeofnumber, photo, presentation)));
        }

        public void onBackgroundCallerInfoUpdated(String name, String number,
                String typeofnumber, Bitmap photo, int presentation) {
            mHandler.removeMessages(MSG_ON_BACKGROUND_CALLER_INFO_UPDATED);
            mHandler.sendMessage(mHandler.obtainMessage(MSG_ON_BACKGROUND_CALLER_INFO_UPDATED,
                    new CallerInfoCookie(name, number,typeofnumber, photo, presentation)));
        }
    };

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch(msg.what) {
                case MSG_UPDATE_ELAPSED_TIME:
                    updateElapsedTimeWidget((String)msg.obj);
                    break;
                case MSG_FINISH:
                    finish();
                    break;
                case MSG_SOUND_ROUTED:
                    updateAudioButton(msg.arg1);
                    break;
                case MSG_INCOMING_CALL_WIDGET_PING:
                    if (DBG) log("INCOMING_CALL_WIDGET_PING...");
                    triggerPing();
                    break;
                case MSG_MUTE_STATE_CHANGED:
                    updateMuteStates(msg);
                    break;
                case MSG_SHOW_INCOMMING_SCREEN:
                    showIncomingCallScreen();
                    break;
                case MSG_SHOW_OUTGOING_SCREEN:
                    showOutgoingCallScreen();
                    break;
                case MSG_SHOW_ONGOING_SCREEN:
                    if (msg.obj instanceof ActiveCallCookie) {
                        ActiveCallCookie cookie = (ActiveCallCookie)msg.obj;
                        showOngoingCallScreen(cookie.hasBackgroundCall, cookie.isEmergency,
                                cookie.isVoiceMail, cookie.isConference);
                    }
                    break;
                case MSG_SHOW_ONHOLD_SCREEN:
                    showOnHoldCallScreen();
                    break;
                case MSG_SHOW_DISCONNECTING_SCREEN:
                    showDisconnectingCallScreen();
                    break;
                case MSG_SHOW_DISCONNECTED_SCREEN:
                    showEndedCallScreen(msg.arg1);
                    sendEmptyMessageDelayed(MSG_FINISH, 500);
                    break;
                case MSG_ON_FOREGROUND_CALLER_INFO_UPDATED:
                    log("update foreground callinfo for mCurrentScreen: " + mCurrentScreen);
                    if (msg.obj instanceof CallerInfoCookie) {
                        CallerInfoCookie callerInfoCookie = (CallerInfoCookie)msg.obj;
                        log("-name: " + callerInfoCookie.name);
                        log("-number: " + callerInfoCookie.number);
                        log("-label: " + callerInfoCookie.typeofnumber);
                        log("-photo: " + callerInfoCookie.photo);
                        mName.setText(callerInfoCookie.name);
                        if (!TextUtils.isEmpty(callerInfoCookie.number)) {
                            mPhoneNumber.setVisibility(View.VISIBLE);
                            mPhoneNumber.setText(callerInfoCookie.number);
                        }
                        if (!TextUtils.isEmpty(callerInfoCookie.typeofnumber)) {
                            mLabel.setText(callerInfoCookie.typeofnumber);
                        }
                        mPhoto.setImageBitmap(callerInfoCookie.photo);
                    }
                    break;
                case MSG_ON_BACKGROUND_CALLER_INFO_UPDATED:
                    log("update background callinfo for mCurrentScreen: " + mCurrentScreen);
                    if (msg.obj instanceof CallerInfoCookie) {
                        CallerInfoCookie callerInfoCookie = (CallerInfoCookie)msg.obj;
                        log("-name: " + callerInfoCookie.name);
                        log("-number: " + callerInfoCookie.number);
                        log("-label: " + callerInfoCookie.typeofnumber);
                        log("-photo: " + callerInfoCookie.photo);
                        mName.setText(callerInfoCookie.name);
                        if (!TextUtils.isEmpty(callerInfoCookie.number)) {
                            mPhoneNumber.setVisibility(View.VISIBLE);
                            mPhoneNumber.setText(callerInfoCookie.number);
                        }
                        if (!TextUtils.isEmpty(callerInfoCookie.typeofnumber)) {
                            mLabel.setText(callerInfoCookie.typeofnumber);
                        }
                        mPhoto.setImageBitmap(callerInfoCookie.photo);
                    }
                    break;
                default:
                    break;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        log("onCreate");

        // before registering for phone state changes
        PowerManager mPowerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);

        // Wake lock used to control proximity sensor behavior.
        if (mPowerManager.isWakeLockLevelSupported(
                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            mProximityEnabled = mPowerManager.newWakeLock(
                    PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, LOG_TAG) !=null;
        }

        // set this flag so this activity will stay in front of the keyguard
        int flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;

        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.flags |= flags;

        if (!mProximityEnabled) {
         // If we don't have a proximity sensor, then the in-call screen explicitly
         // controls user activity.  This is to prevent spurious touches from waking
         // the display.
            lp.inputFeatures |= WindowManager.LayoutParams.INPUT_FEATURE_DISABLE_USER_ACTIVITY;
        }

        getWindow().setAttributes(lp);

        setContentView(R.layout.incall_screen);

        initInCallUi();

        mWakeLockManager = new WakeLockManager(this);
        mWakeLockManager.wakeUpScreen();

        // Bind to telephony service.
        // TODO: Currently we are listening and handling responses from telephony service even if
        //       we are not visible which is not correct and need to be fixed. Probably we should
        //       only be bound to telephony service while we are active.
        Intent serviceIntent = new Intent(ACTION_START_TELEPHONY_SERVICE);
        bindService(serviceIntent, mCallServiceConnection, Context.BIND_AUTO_CREATE);

        mStatusBarManager =
                (StatusBarManager) getSystemService(Context.STATUS_BAR_SERVICE);
        mStatusBarHelper = new StatusBarHelper();
    }

    @Override
    protected void onResume() {
        super.onResume();
        log("onResume");

        mIsResumed = true;

        if (mTelephonyService != null) {
            try {
                mTelephonyService.callUIActived(true);
            } catch (RemoteException e) {
               // There is nothing special we need to do if the service
                // has crashed.
            }
        }

        mDialer.startDialerSession();
    }

    @Override
    protected void onPause() {
        super.onPause();
        log("onPause");

        mIsResumed = false;

        mWakeLockManager.releaseWakeLock();
        if (mTelephonyService != null) {
            try {
                mTelephonyService.callUIActived(false);
            } catch (RemoteException e) {
                // There is nothing special we need to do if the service
                // has crashed.
            }
        }

        mDialer.stopDialerSession();
    }

    @Override
    protected void onDestroy() {
        if (mTelephonyService != null) {
            try {
                mTelephonyService.unregisterCallback(mCallBack);
            } catch (RemoteException e) {
                // There is nothing special we need to do if the service
                // has crashed.
            }
        }
        mDialer.clearInCallScreenReference();
        mDialer = null;
        unbindService(mCallServiceConnection);
        super.onDestroy();
    }

    private void updateNotification(int type) {
        Intent serviceIntent = new Intent();
        serviceIntent.setClassName(this, InCallNotificationService.class.getName());
        serviceIntent.putExtra(InCallNotificationService.UPDATE_TYPE, type);
        startService(serviceIntent);
     }

    private final ServiceConnection mCallServiceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            log("call service connected");
            mTelephonyService = ITelephonyService.Stub.asInterface(service);

            // We want to monitor the service for as long as we are
            // connected to it.
            try {
                mTelephonyService.registerCallback(mCallBack);

                // UI status in call service is not correct due to
                // this connection is connected after resume. The UI status
                // set to call service is lost. Add here!
                mTelephonyService.callUIActived(mIsResumed);

                // TODO: This is just temporary to temporary solve a pretty obvious issue
                // in the UI when demoing. Eventually the telephony service should send
                // out call backs of the current state and information to be shown when
                // a new client connects.
                // Sometimes CallerInfo query have been completed before this connection
                // been setup, then TelephonyService can't notify this client(InCallUI)
                // the CallerInfo in time, this case only appears during the first call,
                // to avoid the problem, request another query once this connection
                // been setup.
                mTelephonyService.requestForegroundCallerInfo(mCallBack);
            } catch (RemoteException e) {
                // In this case the service has crashed before we could even
                // do anything with it; we can count on soon being
                // disconnected (and then reconnected if it can be restarted)
                // so there is no need to do anything here.
            }
        }

        public void onServiceDisconnected(ComponentName className) {
            log("call service disconnected");
            mTelephonyService = null;
        }
    };

    /**
     * Initializes the in-call touch UI on devices that need it.
     */
    private void initInCallUi() {
        mCallInfoContainer = (ViewGroup) findViewById(R.id.call_info_container);
        mPrimaryCallInfo = (ViewGroup) findViewById(R.id.primary_call_info);
        mPrimaryCallBanner = (ViewGroup) findViewById(R.id.primary_call_banner);

        mSecondaryInfoContainer = (ViewGroup) findViewById(R.id.secondary_info_container);
        mProviderInfo = (ViewGroup) findViewById(R.id.providerInfo);
        mProviderLabel = (TextView) findViewById(R.id.providerLabel);
        mProviderAddress = (TextView) findViewById(R.id.providerAddress);
        mCallStateLabel = (TextView) findViewById(R.id.callStateLabel);
        mElapsedTime = (TextView) findViewById(R.id.elapsedTime);

        // Text colors
        mTextColorCallTypeSip = getResources().getColor(R.color.incall_callTypeSip);

        // "Caller info" area, including photo / name / phone numbers / etc
        mPhoto = (ImageView) findViewById(R.id.photo);
        mPhotoDimEffect = findViewById(R.id.dim_effect_for_primary_photo);

        mName = (TextView) findViewById(R.id.name);
        mPhoneNumber = (TextView) findViewById(R.id.phoneNumber);
        mLabel = (TextView) findViewById(R.id.label);
        mCallTypeLabel = (TextView) findViewById(R.id.callTypeLabel);
        // mSocialStatus = (TextView) findViewById(R.id.socialStatus);

        // Secondary info area, for the background ("on hold") call
        mSecondaryCallInfo = (ViewStub) findViewById(R.id.secondary_call_info);

        // Container for the UI elements shown while on a regular call.
        mInCallControls = findViewById(R.id.inCallControls);

        // incoming call ui
        mIncomingCallWidget = (GlowPadView) findViewById(R.id.incomingCallWidget);
        mIncomingCallWidget.setOnTriggerListener(new GlowPadViewOnTriggerListener());

        // Dialerpad.
        ViewStub stub = (ViewStub) findViewById(R.id.dtmf_twelve_key_dialer_stub);
        mDialer = new DTMFTwelveKeyDialer(this, stub);

        initCallUiButton();
    }

    private ImageButton mEndButton;
    private CompoundButton mDialpadButton;

    private void initCallUiButton() {
        // Regular (single-tap) buttons, where we listen for click events:
        // Main cluster of buttons:
        ImageButton addButton = (ImageButton)findViewById(R.id.addButton);
        addButton.setOnClickListener(this);
        addButton.setOnLongClickListener(this);
        ImageButton mergeButton = (ImageButton)findViewById(R.id.mergeButton);
        mergeButton.setOnClickListener(this);
        mergeButton.setOnLongClickListener(this);
        mEndButton = (ImageButton)findViewById(R.id.endButton);
        mEndButton.setOnClickListener(this);
        mDialpadButton = (CompoundButton)findViewById(R.id.dialpadButton);
        mDialpadButton.setOnClickListener(this);
        mDialpadButton.setOnLongClickListener(this);
        CompoundButton muteButton = (CompoundButton)findViewById(R.id.muteButton);
        muteButton.setOnClickListener(this);
        muteButton.setOnLongClickListener(this);
        CompoundButton audioButton = (CompoundButton)findViewById(R.id.audioButton);
        audioButton.setOnClickListener(this);
        audioButton.setOnLongClickListener(this);
        CompoundButton holdButton = (CompoundButton)findViewById(R.id.holdButton);
        holdButton.setOnClickListener(this);
        holdButton.setOnLongClickListener(this);
        ImageButton swapButton = (ImageButton)findViewById(R.id.swapButton);
        swapButton.setOnClickListener(this);
        swapButton.setOnLongClickListener(this);

        // TODO temporary solution, in future the visibility should be decide by call state.
        addButton.setVisibility(View.GONE);
        mergeButton.setVisibility(View.GONE);
        swapButton.setVisibility(View.GONE);

        // TODO this is just temp solution, works for just speaker on and off.
        updateAudioButton(2);
    }

    private void updateAudioButton(int audioRoute) {
        // audioRoute 0 = speaker, 1 = bluetooth, 2 = earpiece
        if (DBG) log("updateAudioButton()...");

        // The various layers of artwork for this button come from
        // btn_compound_audio.xml.  Keep track of which layers we want to be
        // visible:
        //
        // - This selector shows the blue bar below the button icon when
        //   this button is a toggle *and* it's currently "checked".
        boolean showToggleStateIndication = true;
        //
        // - This is visible if the popup menu is enabled:
        boolean showMoreIndicator = false;
        //
        // - Foreground icons for the button.  Exactly one of these is enabled:
        boolean showSpeakerOnIcon = false;
        boolean showSpeakerOffIcon = false;
        boolean showHandsetIcon = false;
        boolean showBluetoothIcon = false;

        // TODO current just two switch between speaker on and off,
        // the whole button should be work as InCallTouchUi do.
        switch (audioRoute) {
            case 0:showSpeakerOnIcon = true;break;
            case 1:showBluetoothIcon = true;break;
            case 2:showSpeakerOffIcon = true;break;
        }
/*            if (inCallControlState.bluetoothEnabled) {
            if (DBG) log("- updateAudioButton: 'popup menu action button' mode...");

            mAudioButton.setEnabled(true);

            // The audio button is NOT a toggle in this state.  (And its
            // setChecked() state is irrelevant since we completely hide the
            // btn_compound_background layer anyway.)

            // Update desired layers:

            showMoreIndicator = true;
            if (inCallControlState.bluetoothIndicatorOn) {
                showBluetoothIcon = true;
            } else if (inCallControlState.speakerOn) {
                showSpeakerOnIcon = true;
            } else {
                showHandsetIcon = true;
                // TODO: if a wired headset is plugged in, that takes precedence
                // over the handset earpiece.  If so, maybe we should show some
                // sort of "wired headset" icon here instead of the "handset
                // earpiece" icon.  (Still need an asset for that, though.)
            }
        } else if (inCallControlState.speakerEnabled) {
            if (DBG) log("- updateAudioButton: 'speaker toggle' mode...");

            mAudioButton.setEnabled(true);

            // The audio button *is* a toggle in this state, and indicates the
            // current state of the speakerphone.
            mAudioButton.setChecked(inCallControlState.speakerOn);

            // Update desired layers:
            showToggleStateIndication = true;

            showSpeakerOnIcon = inCallControlState.speakerOn;
            showSpeakerOffIcon = !inCallControlState.speakerOn;
        } else {
            if (DBG) log("- updateAudioButton: disabled...");

            // The audio button is a toggle in this state, but that's mostly
            // irrelevant since it's always disabled and unchecked.
            mAudioButton.setEnabled(false);
            mAudioButton.setChecked(false);

            // Update desired layers:
            showToggleStateIndication = true;
            showSpeakerOffIcon = true;
        }*/

        // Finally, update the drawable layers (see btn_compound_audio.xml).

        // Constants used below with Drawable.setAlpha():
        final int HIDDEN = 0;
        final int VISIBLE = 255;

        CompoundButton audioButton = (CompoundButton)findViewById(R.id.audioButton);
        //TODO this is just for temp solution
        if (audioRoute == 0)
            audioButton.setChecked(showSpeakerOnIcon);
        LayerDrawable layers = (LayerDrawable) audioButton.getBackground();
        if (DBG) log("- 'layers' drawable: " + layers);

        layers.findDrawableByLayerId(R.id.compoundBackgroundItem)
                .setAlpha(showToggleStateIndication ? VISIBLE : HIDDEN);

        layers.findDrawableByLayerId(R.id.moreIndicatorItem)
                .setAlpha(showMoreIndicator ? VISIBLE : HIDDEN);

        layers.findDrawableByLayerId(R.id.bluetoothItem)
                .setAlpha(showBluetoothIcon ? VISIBLE : HIDDEN);

        layers.findDrawableByLayerId(R.id.handsetItem)
                .setAlpha(showHandsetIcon ? VISIBLE : HIDDEN);

        layers.findDrawableByLayerId(R.id.speakerphoneOnItem)
                .setAlpha(showSpeakerOnIcon ? VISIBLE : HIDDEN);

        layers.findDrawableByLayerId(R.id.speakerphoneOffItem)
                .setAlpha(showSpeakerOffIcon ? VISIBLE : HIDDEN);
    }

    private void updateMuteStates(Message msg) {
        boolean isMuted;
        Bundle bundle = msg.getData();
        isMuted = bundle.getBoolean(MUTE_STATE);
        CompoundButton muteButton = (CompoundButton)findViewById(R.id.muteButton);
        muteButton.setChecked(isMuted);
    }
    /**
     * Updates the overall size and positioning of mCallInfoContainer and
     * the "Call info" blocks, based on the phone state.
     */
    private void updateCallInfoLayout() {
        // Based on the current state, update the overall
        // CallCard layout:

        // - Update the bottom margin of mCallInfoContainer to make sure
        //   the call info area won't overlap with the touchable
        //   controls on the bottom part of the screen.

        int reservedVerticalSpace = getTouchUiHeight();
        ViewGroup.MarginLayoutParams callInfoLp =
                (ViewGroup.MarginLayoutParams) mCallInfoContainer.getLayoutParams();
        callInfoLp.bottomMargin = reservedVerticalSpace;  // Equivalent to setting
                                                          // android:layout_marginBottom in XML
        if (DBG) log("  ==> callInfoLp.bottomMargin: " + reservedVerticalSpace);
        mCallInfoContainer.setLayoutParams(callInfoLp);
    }

    private void updateWakeLock(int screenType) {
        log("updateWakeLock screen type: " + screenType + ", mIsResumed:" + mIsResumed);
        if (!mIsResumed) return;

        // TODO: Add speaker status here to just let screen time out if speaker is on.
        // If the speakerphone is in use.  (If so, we *always* use
        // the default timeout.  Since the user is obviously not holding
        // the phone up to his/her face, we don't need to worry about
        // false touches, and thus don't need to turn the screen off so
        // aggressively.)

        switch(screenType) {
            case SCREEN_TYPE_IN_COMMING:
            case SCREEN_TYPE_OUT_GOING:
            case SCREEN_TYPE_DISCONNECTING:
            case SCREEN_TYPE_DISCONNECTED:
                mWakeLockManager.requestFullWakeLock();
                break;
            default:
                mWakeLockManager.releaseWakeLock();
                break;
        }
    }

    /**
     * Sets up the UI to show outgoing call screen.
     */
    private void showOutgoingCallScreen() {
        log("showOutgoingCallScreen");
        mCurrentScreen = SCREEN_TYPE_OUT_GOING;
        updateWakeLock(mCurrentScreen);
        updateCallInfoLayout();

        if (!mDialer.isOpened()) {
            mCallStateLabel.setVisibility(View.VISIBLE);
            mCallStateLabel.setText("DIALING");

            mPrimaryCallInfo.setVisibility(View.VISIBLE);
            mName.setVisibility(View.VISIBLE);
            mPhoto.setVisibility(View.VISIBLE);
        }

        mElapsedTime.setVisibility(View.GONE);

        mProviderInfo.setVisibility(View.GONE);
        mCallTypeLabel.setVisibility(View.GONE);
        AnimationUtils.Fade.hide(mPhotoDimEffect, View.GONE);

        mInCallControls.setVisibility(View.VISIBLE);
    }

    /**
     * Sets up the UI to show the incoming call screen.
     */
    private void showIncomingCallScreen() {
        log("showIncomingCallScreen");
        mCurrentScreen = SCREEN_TYPE_IN_COMMING;
        updateWakeLock(mCurrentScreen);
        updateCallInfoLayout();

        mCallStateLabel.setVisibility(View.VISIBLE);
        mCallStateLabel.setText("INCOMING CALL");

        mPrimaryCallInfo.setVisibility(View.VISIBLE);
        mName.setVisibility(View.VISIBLE);
        mPhoto.setVisibility(View.VISIBLE);

        mProviderInfo.setVisibility(View.GONE);
        mCallTypeLabel.setVisibility(View.GONE);
        mElapsedTime.setVisibility(View.GONE);
        mProviderInfo.setVisibility(View.GONE);
        mInCallControls.setVisibility(View.GONE);

        AnimationUtils.Fade.hide(mPhotoDimEffect, View.GONE);

        showIncomingCallWidget();

        mStatusBarHelper.enableExpandedView(false);
        mStatusBarHelper.enableSystemBarNavigation(false);
    }

    /**
     * Sets up the UI to show ongoing call screen.
     *
     * @param hasBackgroundCall True if there is a call int he backgroudn that also needs to be
     *                          shown in the UI.
     * @param isEmergency       True if this is a emergency call which if so will disable a few
     *                          things in the UI. TODO: Not used yet though.
     * @param isVoicemail       True if this is a voice mail call.
     * @param isConference      True if this is a confereńce call.
     */
    private void showOngoingCallScreen(boolean hasBackgroundCall, boolean isEmergency,
            boolean isVoiceMail, boolean isConference) {
        log("showOngoingCallScreen, hasBackgroundCall: " + hasBackgroundCall +
                " isEmergency: " + isEmergency + " isVoiceMail: " + isVoiceMail +
                " isConference: " + isConference);
        mCurrentScreen = SCREEN_TYPE_ON_GOING;
        updateWakeLock(mCurrentScreen);

        if (!mDialer.isOpened()) {
            mElapsedTime.setVisibility(View.VISIBLE);
            mInCallControls.setVisibility(View.VISIBLE);
        }

        mCallStateLabel.setVisibility(View.GONE);
        AnimationUtils.Fade.hide(mPhotoDimEffect, View.GONE);
        hideIncomingCallWidget();


        mStatusBarHelper.enableExpandedView(mProximityEnabled);
        mStatusBarHelper.enableSystemBarNavigation(true);
    }

    /**
     * Sets up the UI to show disconnecting call screen. This is called right after the user has
     * requested to end a call. To notify that the call is being dicsonnected but we are still
     * waiting for feedback for it to actually be disconnected. This is only valid for when we are
     * ending a call and do not have any other calls.
     */
    private void showDisconnectingCallScreen() {
        log("showDisconnectingCallScreen");
        mCurrentScreen = SCREEN_TYPE_DISCONNECTING;
        updateWakeLock(mCurrentScreen);

        mCallStateLabel.setVisibility(View.VISIBLE);
        mCallStateLabel.setText("HANG UP");

        AnimationUtils.Fade.hide(mPhotoDimEffect, View.GONE);
        hideIncomingCallWidget();
    }

    /**
     * Sets up the UI to show that all calls are disconnected.
     *
     * @param cause The disconnect cause.
     */
    private void showEndedCallScreen(int cause) {
        log("showEndedCallScreen, cause: " + cause);
        mCurrentScreen = SCREEN_TYPE_DISCONNECTED;
        updateWakeLock(mCurrentScreen);

        mCallStateLabel.setVisibility(View.VISIBLE);

        switch (DisconnectCause.values()[cause]) {
            case BUSY:
                mPhoto.setImageResource(R.drawable.picture_busy);
                break;
            default:
                break;
        }
        mCallStateLabel.setText("CALL ENDED");

        mElapsedTime.setVisibility(View.VISIBLE);
        mEndButton.setEnabled(false);

        AnimationUtils.Fade.hide(mPhotoDimEffect, View.GONE);
        hideIncomingCallWidget();
        mDialer.closeDialer(false);

        mStatusBarHelper.enableExpandedView(true);
        mStatusBarHelper.enableSystemBarNavigation(true);
    }

    /**
     * Sets up the UI to show that a call is on hold. This is only valid for when we have a single
     * call that is on hold.
     */
    private void showOnHoldCallScreen() {
        log("showOnHoldCallScreen");
        mCurrentScreen = SCREEN_TYPE_HOLDING;
        updateWakeLock(mCurrentScreen);

        mCallStateLabel.setVisibility(View.VISIBLE);
        mCallStateLabel.setText("ON HOLD");

        mElapsedTime.setVisibility(View.GONE);
        mInCallControls.setVisibility(View.VISIBLE);

        AnimationUtils.Fade.show(mPhotoDimEffect);
    }

    /**
     * Shows the incoming call widget and cancels any animation that may be fading it out.
     */
    private void showIncomingCallWidget() {
        if (mIncomingCallWidget == null) {
            return;
        }

        ViewPropertyAnimator animator = mIncomingCallWidget.animate();
        if (animator != null) {
            animator.cancel();
            // If animation is cancelled before it's running,
            // onAnimationCancel will not be called and mIncomingCallWidgetIsFadingOut
            // will be alway true. hideIncomingCallWidget() will not be excuted in this case.
            mIncomingCallWidgetIsFadingOut = false;
        }

        mIncomingCallWidget.setAlpha(1.0f);

        // TODO: Add respond via sms.
        final boolean allowRespondViaSms =false;
               // RespondViaSmsManager.allowRespondViaSmsForCall(mInCallScreen, ringingCall);
        final int targetResourceId = allowRespondViaSms
                ? R.array.incoming_call_widget_3way_targets
                : R.array.incoming_call_widget_2way_targets;
        // The widget should be updated only when appropriate; if the previous choice can be reused
        // for this incoming call, we'll just keep using it. Otherwise we'll see UI glitch
        // everytime when this method is called during a single incoming call.
        if (targetResourceId != mIncomingCallWidget.getTargetResourceId()) {
            if (allowRespondViaSms) {
                // The GlowPadView widget is allowed to have all 3 choices:
                // Answer, Decline, and Respond via SMS.
                mIncomingCallWidget.setTargetResources(targetResourceId);
                mIncomingCallWidget.setTargetDescriptionsResourceId(
                        R.array.incoming_call_widget_3way_target_descriptions);
                mIncomingCallWidget.setDirectionDescriptionsResourceId(
                        R.array.incoming_call_widget_3way_direction_descriptions);
            } else {
                // You only get two choices: Answer or Decline.
                mIncomingCallWidget.setTargetResources(targetResourceId);
                mIncomingCallWidget.setTargetDescriptionsResourceId(
                        R.array.incoming_call_widget_2way_target_descriptions);
                mIncomingCallWidget.setDirectionDescriptionsResourceId(
                        R.array.incoming_call_widget_2way_direction_descriptions);
            }

            // This will be used right after this block.
            mIncomingCallWidgetShouldBeReset = true;
        }
        if (mIncomingCallWidgetShouldBeReset) {
            // Watch out: be sure to call reset() and setVisibility() *after*
            // updating the target resources, since otherwise the GlowPadView
            // widget will make the targets visible initially (even before you
            // touch the widget.)
            mIncomingCallWidget.reset(false);
            mIncomingCallWidgetShouldBeReset = false;
        }

        mIncomingCallWidget.setVisibility(View.VISIBLE);

        // Finally, manually trigger a "ping" animation.
        //
        // Normally, the ping animation is triggered by RING events from
        // the telephony layer (see onIncomingRing().)  But that *doesn't*
        // happen for the very first RING event of an incoming call, since
        // the incoming-call UI hasn't been set up yet at that point!
        //
        // So trigger an explicit ping() here, to force the animation to
        // run when the widget first appears.
        //
        mHandler.removeMessages(MSG_INCOMING_CALL_WIDGET_PING);
        mHandler.sendEmptyMessageDelayed(
                MSG_INCOMING_CALL_WIDGET_PING,
                // Visual polish: add a small delay here, to make the
                // GlowPadView widget visible for a brief moment
                // *before* starting the ping animation.
                // This value doesn't need to be very precise.
                250 /* msec */);

    }

    private void hideIncomingCallWidget() {
        if (DBG) log("hideIncomingCallWidget()...");
        if (mIncomingCallWidget.getVisibility() != View.VISIBLE
                || mIncomingCallWidgetIsFadingOut) {
            if (DBG) log("Skipping hideIncomingCallWidget action");
            // Widget is already hidden or in the process of being hidden
            return;
        }

        // Hide the incoming call screen with a transition
        mIncomingCallWidgetIsFadingOut = true;
        ViewPropertyAnimator animator = mIncomingCallWidget.animate();
        animator.cancel();
        animator.setDuration(AnimationUtils.ANIMATION_DURATION);
        animator.setListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                if (mShowInCallControlsDuringHidingAnimation) {
                    if (DBG) log("IncomingCallWidget's hiding animation started");
                    mInCallControls.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (DBG) log("IncomingCallWidget's hiding animation ended");
                mIncomingCallWidget.setAlpha(1);
                mIncomingCallWidget.setVisibility(View.GONE);
                mIncomingCallWidget.animate().setListener(null);
                mShowInCallControlsDuringHidingAnimation = false;
                mIncomingCallWidgetIsFadingOut = false;
                mIncomingCallWidgetShouldBeReset = true;
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                mIncomingCallWidget.animate().setListener(null);
                mShowInCallControlsDuringHidingAnimation = false;
                mIncomingCallWidgetIsFadingOut = false;
                mIncomingCallWidgetShouldBeReset = true;

                // Note: the code which reset this animation should be responsible for
                // alpha and visibility.
            }
        });
        animator.alpha(0f);
    }

    /**
     * Called from DTMFTwelveKeyDialer whenever the dialpad is open or closed.
     *
     * @param animate True if we should animate.
     */
    public void onDialerOpenOrClose(boolean animate) {
        updateWakeLock(mCurrentScreen);
        updateCallInfoLayout();

        if (mDialer.isOpened()) {
            if (animate) {
                AnimationUtils.Fade.hide(mPrimaryCallInfo, View.GONE);
            } else {
                mPrimaryCallInfo.setVisibility(View.GONE);
            }
        } else {
            if (animate) {
                AnimationUtils.Fade.show(mPrimaryCallInfo);
            } else {
                mPrimaryCallInfo.setVisibility(View.VISIBLE);
            }
        }
    }

    /**
     * Runs a single "ping" animation of the GlowPadView widget,
     * or do nothing if the GlowPadView widget is no longer visible.
     *
     * Also, if ENABLE_PING_AUTO_REPEAT is true, schedule the next ping as
     * well (but again, only if the GlowPadView widget is still visible.)
     */
    public void triggerPing() {
        if (DBG) log("triggerPing: mIncomingCallWidget = " + mIncomingCallWidget);

        if (!mIsResumed) {
            // InCallScreen has been dismissed; no need to run a ping *or*
            // schedule another one.
            log("- triggerPing: InCallScreen no longer in foreground; ignoring...");
            return;
        }

        if (mIncomingCallWidget == null) {
            // This shouldn't happen; the GlowPadView widget should
            // always be present in our layout file.
            Log.w(LOG_TAG, "- triggerPing: null mIncomingCallWidget!");
            return;
        }

        if (DBG) log("- triggerPing: mIncomingCallWidget visibility = "
                     + mIncomingCallWidget.getVisibility());

        if (mIncomingCallWidget.getVisibility() != View.VISIBLE) {
            if (DBG) log("- triggerPing: mIncomingCallWidget no longer visible; ignoring...");
            return;
        }

        // Ok, run a ping (and schedule the next one too, if desired...)

        mIncomingCallWidget.ping();

        if (ENABLE_PING_AUTO_REPEAT) {
            // Schedule the next ping.  (ENABLE_PING_AUTO_REPEAT mode
            // allows the ping animation to repeat much faster than in
            // the ENABLE_PING_ON_RING_EVENTS case, since telephony RING
            // events come fairly slowly (about 3 seconds apart.))

            // No need to check here if the call is still ringing, by
            // the way, since we hide mIncomingCallWidget as soon as the
            // ringing stops, or if the user answers.  (And at that
            // point, any future triggerPing() call will be a no-op.)

            // TODO: Rather than having a separate timer here, maybe try
            // having these pings synchronized with the vibrator (see
            // VibratorThread in Ringer.java; we'd just need to get
            // events routed from there to here, probably via the
            // PhoneApp instance.)  (But watch out: make sure pings
            // still work even if the Vibrate setting is turned off!)

            mHandler.sendEmptyMessageDelayed(MSG_INCOMING_CALL_WIDGET_PING,
                                             PING_AUTO_REPEAT_DELAY_MSEC);
        }
    }

    public class GlowPadViewOnTriggerListener implements OnTriggerListener {
        public void onGrabbed(View v, int handle) {
        }
        public void onReleased(View v, int handle) {
        }

        public void onTrigger(View v, int target) {
            if (DBG) log("onTrigger(whichHandle = " + target + ")...");

            // The InCallScreen actually implements all of these actions.
            // Each possible action from the incoming call widget corresponds
            // to an R.id value; we pass those to the InCallScreen's "button
            // click" handler (even though the UI elements aren't actually
            // buttons; see InCallScreen.handleOnscreenButtonClick().)

            mShowInCallControlsDuringHidingAnimation = false;
            switch (target) {
                case TRIGGER_ANSWER_CALL_ID:
                    if (DBG) log("ANSWER_CALL_ID: answer!");
                    if (mTelephonyService != null) {
                        try {
                            mTelephonyService.answerCall();
                        } catch (RemoteException re) {
                        }
                    }
                    mShowInCallControlsDuringHidingAnimation = true;

                    break;

                case TRIGGER_DECLINE_CALL_ID:
                    if (DBG) log("DECLINE_CALL_ID: reject!");
                    if (mTelephonyService != null) {
                        try {
                            mTelephonyService.hangupCall();
                        } catch (RemoteException re) {

                        }
                    }

                    finish();
                    break;

                default:
                    Log.wtf(LOG_TAG, "onDialTrigger: unexpected whichHandle value: " + target);
                    break;
            }

            // On any action by the user, hide the widget.
            //
            // If requested above (i.e. if mShowInCallControlsDuringHidingAnimation is set to true),
            // in-call controls will start being shown too.
            //
            // TODO: The decision to hide this should be made by the controller
            // (InCallScreen), and not this view.
            hideIncomingCallWidget();
        }

        public void onGrabbedStateChange(View v, int handle) {
        }

        public void onFinishFinalAnimation() {

        }
    }

    /**
     * @return the amount of vertical space (in pixels) that needs to be
     * reserved for the button cluster at the bottom of the screen.
     * (The CallCard uses this measurement to determine how big
     * the main "contact photo" area can be.)
     *
     * NOTE that this returns the "canonical height" of the main in-call
     * button cluster, which may not match the amount of vertical space
     * actually used.  Specifically:
     *
     *   - If an incoming call is ringing, the button cluster isn't
     *     visible at all.  (And the GlowPadView widget is actually
     *     much taller than the button cluster.)
     *
     *   - If the InCallTouchUi widget's "extra button row" is visible
     *     (in some rare phone states) the button cluster will actually
     *     be slightly taller than the "canonical height".
     *
     * In either of these cases, we allow the bottom edge of the contact
     * photo to be covered up by whatever UI is actually onscreen.
     */
    public int getTouchUiHeight() {
        // Add up the vertical space consumed by the various rows of buttons.
        int height = 0;

        // - The main row of buttons:
        height += (int) getResources().getDimension(R.dimen.in_call_button_height);

        // - The End button:
        height += (int) getResources().getDimension(R.dimen.in_call_end_button_height);

        // - Note we *don't* consider the InCallTouchUi widget's "extra
        //   button row" here.

        //- And an extra bit of margin:
        height += (int) getResources().getDimension(R.dimen.in_call_touch_ui_upper_margin);

        return height;
    }

    public boolean onLongClick(View v) {
        final int id = v.getId();
        if (DBG) log("onLongClick(View " + v + ", id " + id + ")...");

        switch (id) {
            case R.id.addButton:
            case R.id.mergeButton:
            case R.id.dialpadButton:
            case R.id.muteButton:
            case R.id.holdButton:
            case R.id.swapButton:
            case R.id.audioButton: {
                final CharSequence description = v.getContentDescription();
                if (!TextUtils.isEmpty(description)) {
                    // Show description as ActionBar's menu buttons do.
                    // See also ActionMenuItemView#onLongClick() for the original implementation.
                    final Toast cheatSheet =
                            Toast.makeText(v.getContext(), description, Toast.LENGTH_SHORT);
                    cheatSheet.setGravity(
                            Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, v.getHeight());
                    cheatSheet.show();
                }

                return true;
            }
            default:
                Log.w(LOG_TAG, "onLongClick() with unexpected View " + v + ". Ignoring it.");
                break;
        }
        return false;
    }

    public void onClick(View v) {
        int id = v.getId();
        if (DBG) log("onClick(View " + v + ", id " + id + ")...");

        switch (id) {
            case R.id.addButton:
            case R.id.mergeButton:
                break;
            case R.id.dialpadButton:
                toggledialpad();
                break;
            case R.id.endButton:
                endCallPressed();
                break;
            case R.id.muteButton:
                toggleMute();
                break;
            case R.id.holdButton:
                toggleHoldCall();
            case R.id.swapButton:
            case R.id.cdmaMergeButton:
            case R.id.manageConferenceButton:
                break;

            case R.id.audioButton:
                toggleAudio();
                break;

            default:
                Log.w(LOG_TAG, "onClick: unexpected click: View " + v + ", id " + id);
                break;
        }
    }

    /**
     * Handles button clicks from the InCallTouchUi widget.
     */
    /* package */ void handleOnscreenButtonClick(int id) {

    }

    /**
     * Updates mElapsedTime based on the specified number of seconds.
     */
    private void updateElapsedTimeWidget(String timeElapsed) {
        log("updateElapsedTimeWidget: " + timeElapsed);
        mElapsedTime.setText(timeElapsed);
    }

    /** Helper function to display the resource in the imageview AND ensure its visibility.*/
    private static final void showImage(ImageView view, int resource) {
        showImage(view, view.getContext().getResources().getDrawable(resource));
    }

    private static final void showImage(ImageView view, Bitmap bitmap) {
        showImage(view, new BitmapDrawable(view.getContext().getResources(), bitmap));
    }

    /** Helper function to display the drawable in the imageview AND ensure its visibility.*/
    private static final void showImage(ImageView view, Drawable drawable) {
        Resources res = view.getContext().getResources();
        Drawable current = (Drawable) view.getTag();

        if (current == null) {
            if (DBG) log("Start fade-in animation for " + view);
            view.setImageDrawable(drawable);
            AnimationUtils.Fade.show(view);
            view.setTag(drawable);
        } else {
            AnimationUtils.startCrossFade(view, current, drawable);
            view.setVisibility(View.VISIBLE);
        }
    }

    private void endCallPressed() {
        log("endCallPressed");
        try{
            mTelephonyService.hangupCall();
        } catch(RemoteException re) {

        }
    }

    private void answerCallPressed() {
        log("answerCallPressed");
        try{
            mTelephonyService.answerCall();
        } catch(RemoteException re) {

        }
    }

    private void toggleHoldCall() {
        log("toggleHoldCall");
        try{
            if (mCurrentScreen == SCREEN_TYPE_HOLDING) {
                mTelephonyService.retrieveCall();
                mDialpadButton.setEnabled(true);
            } else {
                mTelephonyService.holdCall();
                mDialer.closeDialer(true);
                mDialpadButton.setChecked(false);
                mDialpadButton.setEnabled(false);
            }
        } catch(RemoteException re) {

        }
    }

    private void toggleMute() {
        log("toggleMute");
        try {
            mTelephonyService.muteMic();
        } catch (RemoteException re) {

        }
    }

    private void toggleAudio() {
        log("toggleAudio");
        try {
            int state = mTelephonyService.getSoundRoute();
            if (state == 0)
                mTelephonyService.routeSound(2);
            else
                mTelephonyService.routeSound(0);
        } catch (RemoteException re) {

        }
    }

    private void toggledialpad() {
        log("toggledialpad");

        if (mDialer.isOpened()) {
            mDialer.closeDialer(true);
        } else {
            mDialer.openDialer(true);
        }
    }

    public void startDtmf(char c) {
        log("startDtmf");
        try {
            mTelephonyService.startDtmf(c);
        } catch (RemoteException re) {

        }

        mDialer.startLocalToneIfNeeded(c);
    }

    public void stopDtmf() {
        log("stopTone");
        try {
            mTelephonyService.stopDtmf();
        } catch (RemoteException re) {

        }
        mDialer.stopLocalToneIfNeeded();
    }

    private static void log(String msg) {
        Log.d(LOG_TAG, msg);
    }


    /**
     * Helper class that's a wrapper around the framework's
     * StatusBarManager.disable() API.
     *
     * This class is used to control features like:
     *
     *   - Disabling the status bar "notification windowshade"
     *     while the in-call UI is up
     *
     *   - Disabling navigation via the system bar (the "soft buttons" at
     *     the bottom of the screen on devices with no hard buttons)
     *
     * We control these features through a single point of control to make
     * sure that the various StatusBarManager.disable() calls don't
     * interfere with each other.
     */
    public class StatusBarHelper {
        // Current desired state of status bar / system bar behavior
        private boolean mIsExpandedViewEnabled = true;
        private boolean mIsSystemBarNavigationEnabled = true;

        private StatusBarHelper () {
        }


        /**
         * Enables or disables the expanded view of the status bar
         * (i.e. the ability to pull down the "notification windowshade").
         *
         * (This feature is disabled by the InCallScreen while the in-call
         * UI is active.)
         */
        public void enableExpandedView(boolean enable) {
            if (mIsExpandedViewEnabled != enable) {
                mIsExpandedViewEnabled = enable;
                updateStatusBar();
            }
        }

        /**
         * Enables or disables the navigation via the system bar (the
         * "soft buttons" at the bottom of the screen)
         *
         * (This feature is disabled while an incoming call is ringing,
         * because it's easy to accidentally touch the system bar while
         * pulling the phone out of your pocket.)
         */
        public void enableSystemBarNavigation(boolean enable) {
            if (mIsSystemBarNavigationEnabled != enable) {
                mIsSystemBarNavigationEnabled = enable;
                updateStatusBar();
            }
        }

        /**
         * Updates the status bar to reflect the current desired state.
         */
        private void updateStatusBar() {
            int state = StatusBarManager.DISABLE_NONE;

            if (!mIsExpandedViewEnabled) {
                state |= StatusBarManager.DISABLE_EXPAND;
            }
            if (!mIsSystemBarNavigationEnabled) {
                // Disable *all* possible navigation via the system bar.
                state |= StatusBarManager.DISABLE_HOME;
                state |= StatusBarManager.DISABLE_RECENT;
                state |= StatusBarManager.DISABLE_BACK;
            }

            if (DBG) log("updateStatusBar: state = 0x" + Integer.toHexString(state));
            mStatusBarManager.disable(state);
        }
    }

    /**
     * Enables or disables the status bar "window shade" based on the current situation.
     */
    private void updateExpandedViewState() {
            //TODO true means promimitsensor is enable, it should be different from devices
            if (true) {
                // We should not enable notification's expanded view on RINGING state.
                //TODO need to get isRinging from callservice, current assume there is no rining
                //     call
                mStatusBarHelper.enableExpandedView(true);
            } else {
                // If proximity sensor is unavailable on the device, disable it to avoid false
                // touches toward notifications.
                mStatusBarHelper.enableExpandedView(false);
            }
    }

}
