/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.vibrator;

import static android.os.VibrationEffect.VibrationParameter.targetAmplitude;
import static android.os.VibrationEffect.VibrationParameter.targetFrequency;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.vibrator.IVibrator;
import android.os.BatteryStats;
import android.os.Binder;
import android.os.Build;
import android.os.CombinedVibration;
import android.os.ExternalVibration;
import android.os.Handler;
import android.os.IBinder;
import android.os.IExternalVibratorService;
import android.os.IVibratorManagerService;
import android.os.IVibratorStateListener;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.os.Trace;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.VibratorInfo;
import android.os.vibrator.PrebakedSegment;
import android.os.vibrator.VibrationEffectSegment;
import android.text.TextUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IBatteryStats;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.SystemService;

import libcore.util.NativeAllocationRegistry;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
//add by AAC for RichTap support
import com.android.server.vibrator.RichTapVibratorService;
import com.android.server.vibrator.RichTapVibratorService.SenderId;
import vendor.aac.hardware.richtap.vibrator.IRichtapVibrator;//aidl
import vendor.aac.hardware.richtap.vibrator.IRichtapCallback;
import android.hardware.vibrator.IVibratorCallback;
import android.os.RichTapVibrationEffect;
//end add by AAC
/** System implementation of {@link IVibratorManagerService}. */
public class VibratorManagerService extends IVibratorManagerService.Stub {
    private static final String TAG = "VibratorManagerService";
    private static final String EXTERNAL_VIBRATOR_SERVICE = "external_vibrator_service";
    private static final boolean DEBUG = false;
    private static final VibrationAttributes DEFAULT_ATTRIBUTES =
            new VibrationAttributes.Builder().build();
    private static final int ATTRIBUTES_ALL_BYPASS_FLAGS =
            VibrationAttributes.FLAG_BYPASS_INTERRUPTION_POLICY
                    | VibrationAttributes.FLAG_BYPASS_USER_VIBRATION_INTENSITY_OFF;

    /** Fixed large duration used to note repeating vibrations to {@link IBatteryStats}. */
    private static final long BATTERY_STATS_REPEATING_VIBRATION_DURATION = 5_000;

    /**
     * Maximum millis to wait for a vibration thread cancellation to "clean up" and finish, when
     * blocking for an external vibration. In practice, this should be plenty.
     */
    private static final long VIBRATION_CANCEL_WAIT_MILLIS = 5000;

    /** Lifecycle responsible for initializing this class at the right system server phases. */
    public static class Lifecycle extends SystemService {
        private VibratorManagerService mService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            mService = new VibratorManagerService(getContext(), new Injector());
            publishBinderService(Context.VIBRATOR_MANAGER_SERVICE, mService);
        }

        @Override
        public void onBootPhase(int phase) {
            if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
                mService.systemReady();
            }
        }
    }

    // Used to generate globally unique vibration ids.
    private final AtomicInteger mNextVibrationId = new AtomicInteger(1); // 0 = no callback

    private final Object mLock = new Object();
    private final Context mContext;
    private final PowerManager.WakeLock mWakeLock;
    private final IBatteryStats mBatteryStatsService;
    private final Handler mHandler;
    private final VibrationThread mVibrationThread;
    private final AppOpsManager mAppOps;
    private final NativeWrapper mNativeWrapper;
    private final VibratorManagerRecords mVibratorManagerRecords;
    private final long mCapabilities;
    private final int[] mVibratorIds;
    private final SparseArray<VibratorController> mVibrators;
    private final VibrationThreadCallbacks mVibrationThreadCallbacks =
            new VibrationThreadCallbacks();
    @GuardedBy("mLock")
    private final SparseArray<AlwaysOnVibration> mAlwaysOnEffects = new SparseArray<>();
    @GuardedBy("mLock")
    private VibrationStepConductor mCurrentVibration;
    @GuardedBy("mLock")
    private VibrationStepConductor mNextVibration;
    @GuardedBy("mLock")
// add by AAC for Richtap support
    private final static boolean RICHTAP_JAVA_TO_HIDL = true;
    IRichtapCallback mRichtapAidlCallback = new RichtapCallback();

    private final class RichtapCallback extends IRichtapCallback.Stub {
        public void onCallback(int result) {
            if (DEBUG){
               Slog.d(TAG, " result:"+result);
            }
        }
        public int getInterfaceVersion() {
            Slog.d(TAG, "getInterfaceVersion");
            return 1;
        }
        public String getInterfaceHash() {
            Slog.d(TAG, "getInterfaceHash");
            return "aac_richtap";
        }
    };
    private RichTapVibratorService richTapService = new RichTapVibratorService(RICHTAP_JAVA_TO_HIDL, mRichtapAidlCallback);
//end modify by AAC
    private ExternalVibrationHolder mCurrentExternalVibration;
    @GuardedBy("mLock")
    private boolean mServiceReady;

    private final VibrationSettings mVibrationSettings;
    private final VibrationScaler mVibrationScaler;
    private final InputDeviceDelegate mInputDeviceDelegate;
    private final DeviceVibrationEffectAdapter mDeviceVibrationEffectAdapter;

    private BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                synchronized (mLock) {
                    // When the system is entering a non-interactive state, we want to cancel
                    // vibrations in case a misbehaving app has abandoned them.
                    if (shouldCancelOnScreenOffLocked(mNextVibration)) {
                        clearNextVibrationLocked(Vibration.Status.CANCELLED_BY_SCREEN_OFF);
                    }
                    if (shouldCancelOnScreenOffLocked(mCurrentVibration)) {
                        mCurrentVibration.notifyCancelled(Vibration.Status.CANCELLED_BY_SCREEN_OFF,
                                /* immediate= */ false);
                    }
                }
            } else if(intent.getAction().equals(RichTapVibratorService.ACTION_CHANGE_MODE)) {
                int mode = intent.getIntExtra("mode", -1);
                Slog.i(TAG, "richtap-mode, rec ACTION_CHANGE_MODE, mode:" + mode);
                if ( -1 == mode || null == richTapService) {
                    Slog.e(TAG, " invalid mode or status!");
                    return;
                }
                synchronized (mLock) {
                    richTapService.richTapSetVibrationMode(mode);
                }
            }
        }
    };

    static native long nativeInit(OnSyncedVibrationCompleteListener listener);

    static native long nativeGetFinalizer();

    static native long nativeGetCapabilities(long nativeServicePtr);

    static native int[] nativeGetVibratorIds(long nativeServicePtr);

    static native boolean nativePrepareSynced(long nativeServicePtr, int[] vibratorIds);

    static native boolean nativeTriggerSynced(long nativeServicePtr, long vibrationId);

    static native void nativeCancelSynced(long nativeServicePtr);

    @VisibleForTesting
    VibratorManagerService(Context context, Injector injector) {
        mContext = context;
        mHandler = injector.createHandler(Looper.myLooper());

        mVibrationSettings = new VibrationSettings(mContext, mHandler);
        mVibrationScaler = new VibrationScaler(mContext, mVibrationSettings);
        mInputDeviceDelegate = new InputDeviceDelegate(mContext, mHandler);
        mDeviceVibrationEffectAdapter = new DeviceVibrationEffectAdapter(mVibrationSettings);

        VibrationCompleteListener listener = new VibrationCompleteListener(this);
        mNativeWrapper = injector.getNativeWrapper();
        mNativeWrapper.init(listener);

        int dumpLimit = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_previousVibrationsDumpLimit);
        mVibratorManagerRecords = new VibratorManagerRecords(dumpLimit);

        mBatteryStatsService = injector.getBatteryStatsService();

        mAppOps = mContext.getSystemService(AppOpsManager.class);

        PowerManager pm = context.getSystemService(PowerManager.class);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "*vibrator*");
        mWakeLock.setReferenceCounted(true);
        mVibrationThread = new VibrationThread(mWakeLock, mVibrationThreadCallbacks);
        mVibrationThread.start();

        // Load vibrator hardware info. The vibrator ids and manager capabilities are loaded only
        // once and assumed unchanged for the lifecycle of this service. Each individual vibrator
        // can still retry loading each individual vibrator hardware spec once more at systemReady.
        mCapabilities = mNativeWrapper.getCapabilities();
        int[] vibratorIds = mNativeWrapper.getVibratorIds();
        if (vibratorIds == null) {
            mVibratorIds = new int[0];
            mVibrators = new SparseArray<>(0);
        } else {
            // Keep original vibrator id order, which might be meaningful.
            mVibratorIds = vibratorIds;
            mVibrators = new SparseArray<>(mVibratorIds.length);
            for (int vibratorId : vibratorIds) {
                mVibrators.put(vibratorId, injector.createVibratorController(vibratorId, listener));
            }
        }

        // Reset the hardware to a default state, in case this is a runtime restart instead of a
        // fresh boot.
        mNativeWrapper.cancelSynced();
        for (int i = 0; i < mVibrators.size(); i++) {
            mVibrators.valueAt(i).reset();
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        if (RICHTAP_JAVA_TO_HIDL) {
            filter.addAction(RichTapVibratorService.ACTION_CHANGE_MODE);
        }
        context.registerReceiver(mIntentReceiver, filter, Context.RECEIVER_NOT_EXPORTED);

        injector.addService(EXTERNAL_VIBRATOR_SERVICE, new ExternalVibratorService());
    }

    /** Finish initialization at boot phase {@link SystemService#PHASE_SYSTEM_SERVICES_READY}. */
    @VisibleForTesting
    void systemReady() {
        Slog.v(TAG, "Initializing VibratorManager service...");
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "systemReady");
        try {
            // Will retry to load each vibrator's info, if any request have failed.
            for (int i = 0; i < mVibrators.size(); i++) {
                mVibrators.valueAt(i).reloadVibratorInfoIfNeeded();
            }

            mVibrationSettings.onSystemReady();
            mInputDeviceDelegate.onSystemReady();

            mVibrationSettings.addListener(this::updateServiceState);

            // Will update settings and input devices.
            updateServiceState();
        } finally {
            synchronized (mLock) {
                mServiceReady = true;
            }
            Slog.v(TAG, "VibratorManager service initialized");
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    @Override // Binder call
    public int[] getVibratorIds() {
        return Arrays.copyOf(mVibratorIds, mVibratorIds.length);
    }

    @Override // Binder call
    @Nullable
    public VibratorInfo getVibratorInfo(int vibratorId) {
        final VibratorController controller = mVibrators.get(vibratorId);
        if (controller == null) {
            return null;
        }
        final VibratorInfo info = controller.getVibratorInfo();
        synchronized (mLock) {
            if (mServiceReady) {
                return info;
            }
        }
        // If the service is not ready and the load was unsuccessful then return null while waiting
        // for the service to be ready. It will retry to load the complete info from the HAL.
        return controller.isVibratorInfoLoadSuccessful() ? info : null;
    }

    @Override // Binder call
    public boolean isVibrating(int vibratorId) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_VIBRATOR_STATE,
                "isVibrating");
        VibratorController controller = mVibrators.get(vibratorId);
        return controller != null && controller.isVibrating();
    }

    @Override // Binder call
    public boolean registerVibratorStateListener(int vibratorId, IVibratorStateListener listener) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_VIBRATOR_STATE,
                "registerVibratorStateListener");
        VibratorController controller = mVibrators.get(vibratorId);
        if (controller == null) {
            return false;
        }
        return controller.registerVibratorStateListener(listener);
    }

    @Override // Binder call
    public boolean unregisterVibratorStateListener(int vibratorId,
            IVibratorStateListener listener) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_VIBRATOR_STATE,
                "unregisterVibratorStateListener");
        VibratorController controller = mVibrators.get(vibratorId);
        if (controller == null) {
            return false;
        }
        return controller.unregisterVibratorStateListener(listener);
    }

    @Override // Binder call
    public boolean setAlwaysOnEffect(int uid, String opPkg, int alwaysOnId,
            @Nullable CombinedVibration effect, @Nullable VibrationAttributes attrs) {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "setAlwaysOnEffect");
        try {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.VIBRATE_ALWAYS_ON,
                    "setAlwaysOnEffect");

            if (effect == null) {
                synchronized (mLock) {
                    mAlwaysOnEffects.delete(alwaysOnId);
                    onAllVibratorsLocked(v -> {
                        if (v.hasCapability(IVibrator.CAP_ALWAYS_ON_CONTROL)) {
                            v.updateAlwaysOn(alwaysOnId, /* effect= */ null);
                        }
                    });
                }
                return true;
            }
            if (!isEffectValid(effect)) {
                return false;
            }
            attrs = fixupVibrationAttributes(attrs, effect);
            synchronized (mLock) {
                SparseArray<PrebakedSegment> effects = fixupAlwaysOnEffectsLocked(effect);
                if (effects == null) {
                    // Invalid effects set in CombinedVibrationEffect, or always-on capability is
                    // missing on individual vibrators.
                    return false;
                }
                AlwaysOnVibration alwaysOnVibration = new AlwaysOnVibration(
                        alwaysOnId, uid, opPkg, attrs, effects);
                mAlwaysOnEffects.put(alwaysOnId, alwaysOnVibration);
                updateAlwaysOnLocked(alwaysOnVibration);
            }
            return true;
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    @Override // Binder call
    public void vibrate(int uid, String opPkg, @NonNull CombinedVibration effect,
            @Nullable VibrationAttributes attrs, String reason, IBinder token) {
        vibrateInternal(uid, opPkg, effect, attrs, reason, token);
    }

    /**
     * An internal-only version of vibrate that allows the caller access to the {@link Vibration}.
     * The Vibration is only returned if it is ongoing after this method returns.
     */
    @Nullable
    private Vibration vibrateInternal(int uid, String opPkg, @NonNull CombinedVibration effect,
            @Nullable VibrationAttributes attrs, String reason, IBinder token) {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "vibrate, reason = " + reason);
        try {
            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.VIBRATE, "vibrate");

            if (token == null) {
                Slog.e(TAG, "token must not be null");
                return null;
            }
            enforceUpdateAppOpsStatsPermission(uid);
            if (!isEffectValid(effect)) {
                return null;
            }
            // add by AAC for Richtap support
            if(richTapService.disposeRichtapEffectParams(effect)) {
                return null;
            }
            //end modify by AAC
            attrs = fixupVibrationAttributes(attrs, effect);
            Vibration vib = new Vibration(token, mNextVibrationId.getAndIncrement(), effect, attrs,
                    uid, opPkg, reason);
            fillVibrationFallbacks(vib, effect);

            if (attrs.isFlagSet(VibrationAttributes.FLAG_INVALIDATE_SETTINGS_CACHE)) {
                // Force update of user settings before checking if this vibration effect should
                // be ignored or scaled.
                mVibrationSettings.mSettingObserver.onChange(false);
            }

            synchronized (mLock) {
                if (DEBUG) {
                    Slog.d(TAG, "Starting vibrate for vibration  " + vib.id);
                }
                Vibration.Status ignoreStatus = shouldIgnoreVibrationLocked(
                        vib.uid, vib.opPkg, vib.attrs);

                if (ignoreStatus == null) {
                    ignoreStatus = shouldIgnoreVibrationForOngoingLocked(vib);
                }

                if (ignoreStatus != null) {
                    endVibrationLocked(vib, ignoreStatus);
                    return vib;
                }

                final long ident = Binder.clearCallingIdentity();
                try {
                    if(RICHTAP_JAVA_TO_HIDL && effect instanceof CombinedVibration.Mono) {
                        VibrationEffect vibrEffect = ((CombinedVibration.Mono)effect).getEffect();
                        if(!richTapService.checkIfRichTapEffect(vibrEffect, reason)) {
                            doStopVibrateLocked();
                        }
                    } else {
                        doStopVibrateLocked();
                    }
                    if (mCurrentVibration != null) {
                        mCurrentVibration.notifyCancelled(Vibration.Status.CANCELLED_SUPERSEDED,
                                /* immediate= */ false);
                    }
                    Vibration.Status status = startVibrationLocked(vib);
                    if (status != Vibration.Status.RUNNING) {
                        endVibrationLocked(vib, status);
                    }
                    return vib;
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    @Override // Binder call
    public void cancelVibrate(int usageFilter, IBinder token) {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "cancelVibrate");
        try {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.VIBRATE,
                    "cancelVibrate");

            synchronized (mLock) {
                if (DEBUG) {
                    Slog.d(TAG, "Canceling vibration");
                }
                final long ident = Binder.clearCallingIdentity();
                try {
                    doStopVibrateLocked();
                    if (mNextVibration != null
                            && shouldCancelVibration(mNextVibration.getVibration(),
                            usageFilter, token)) {
                        clearNextVibrationLocked(Vibration.Status.CANCELLED_BY_USER);
                    }
                    if (mCurrentVibration != null
                            && shouldCancelVibration(mCurrentVibration.getVibration(),
                            usageFilter, token)) {
                        mCurrentVibration.notifyCancelled(Vibration.Status.CANCELLED_BY_USER,
                                /* immediate= */false);
                    }
                    if (mCurrentExternalVibration != null
                            && shouldCancelVibration(
                            mCurrentExternalVibration.externalVibration.getVibrationAttributes(),
                            usageFilter)) {
                        mCurrentExternalVibration.externalVibration.mute();
                        endExternalVibrateLocked(Vibration.Status.CANCELLED_BY_USER,
                                /* continueExternalControl= */ false);
                    }
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

        final long ident = Binder.clearCallingIdentity();

        boolean isDumpProto = false;
        for (String arg : args) {
            if (arg.equals("--proto")) {
                isDumpProto = true;
            }
        }
        try {
            if (isDumpProto) {
                dumpProto(fd);
            } else {
                dumpText(pw);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void dumpText(PrintWriter pw) {
        if (DEBUG) {
            Slog.d(TAG, "Dumping vibrator manager service to text...");
        }
        synchronized (mLock) {
            pw.println("Vibrator Manager Service:");
            pw.println("  mVibrationSettings:");
            pw.println("    " + mVibrationSettings);
            pw.println();
            pw.println("  mVibratorControllers:");
            for (int i = 0; i < mVibrators.size(); i++) {
                pw.println("    " + mVibrators.valueAt(i));
            }
            pw.println();
            pw.println("  mCurrentVibration:");
            pw.println("    " + (mCurrentVibration == null
                    ? null : mCurrentVibration.getVibration().getDebugInfo()));
            pw.println();
            pw.println("  mNextVibration:");
            pw.println("    " + (mNextVibration == null
                    ? null : mNextVibration.getVibration().getDebugInfo()));
            pw.println();
            pw.println("  mCurrentExternalVibration:");
            pw.println("    " + (mCurrentExternalVibration == null
                    ? null : mCurrentExternalVibration.getDebugInfo()));
            pw.println();
        }
        mVibratorManagerRecords.dumpText(pw);
    }

    synchronized void dumpProto(FileDescriptor fd) {
        final ProtoOutputStream proto = new ProtoOutputStream(fd);
        if (DEBUG) {
            Slog.d(TAG, "Dumping vibrator manager service to proto...");
        }
        synchronized (mLock) {
            mVibrationSettings.dumpProto(proto);
            if (mCurrentVibration != null) {
                mCurrentVibration.getVibration().getDebugInfo().dumpProto(proto,
                        VibratorManagerServiceDumpProto.CURRENT_VIBRATION);
            }
            if (mCurrentExternalVibration != null) {
                mCurrentExternalVibration.getDebugInfo().dumpProto(proto,
                        VibratorManagerServiceDumpProto.CURRENT_EXTERNAL_VIBRATION);
            }

            boolean isVibrating = false;
            boolean isUnderExternalControl = false;
            for (int i = 0; i < mVibrators.size(); i++) {
                proto.write(VibratorManagerServiceDumpProto.VIBRATOR_IDS, mVibrators.keyAt(i));
                isVibrating |= mVibrators.valueAt(i).isVibrating();
                isUnderExternalControl |= mVibrators.valueAt(i).isUnderExternalControl();
            }
            proto.write(VibratorManagerServiceDumpProto.IS_VIBRATING, isVibrating);
            proto.write(VibratorManagerServiceDumpProto.VIBRATOR_UNDER_EXTERNAL_CONTROL,
                    isUnderExternalControl);
        }
        mVibratorManagerRecords.dumpProto(proto);
        proto.flush();
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ShellCallback cb, ResultReceiver resultReceiver) {
        new VibratorManagerShellCommand(cb.getShellCallbackBinder())
                .exec(this, in, out, err, args, cb, resultReceiver);
    }

    @VisibleForTesting
    void updateServiceState() {
        synchronized (mLock) {
            if (DEBUG) {
                Slog.d(TAG, "Updating device state...");
            }
            boolean inputDevicesChanged = mInputDeviceDelegate.updateInputDeviceVibrators(
                    mVibrationSettings.shouldVibrateInputDevices());

            for (int i = 0; i < mAlwaysOnEffects.size(); i++) {
                updateAlwaysOnLocked(mAlwaysOnEffects.valueAt(i));
            }

            if (mCurrentVibration == null) {
                return;
            }

            Vibration vib = mCurrentVibration.getVibration();
            Vibration.Status ignoreStatus = shouldIgnoreVibrationLocked(
                    vib.uid, vib.opPkg, vib.attrs);

            if (inputDevicesChanged || (ignoreStatus != null)) {
                if (DEBUG) {
                    Slog.d(TAG, "Canceling vibration because settings changed: "
                            + (inputDevicesChanged ? "input devices changed" : ignoreStatus));
                }
                mCurrentVibration.notifyCancelled(Vibration.Status.CANCELLED_BY_SETTINGS_UPDATE,
                        /* immediate= */ false);
            }
        }
    }

    private void setExternalControl(boolean externalControl) {
        for (int i = 0; i < mVibrators.size(); i++) {
            mVibrators.valueAt(i).setExternalControl(externalControl);
        }
    }

    @GuardedBy("mLock")
    private void updateAlwaysOnLocked(AlwaysOnVibration vib) {
        for (int i = 0; i < vib.effects.size(); i++) {
            VibratorController vibrator = mVibrators.get(vib.effects.keyAt(i));
            PrebakedSegment effect = vib.effects.valueAt(i);
            if (vibrator == null) {
                continue;
            }
            Vibration.Status ignoreStatus = shouldIgnoreVibrationLocked(
                    vib.uid, vib.opPkg, vib.attrs);
            if (ignoreStatus == null) {
                effect = mVibrationScaler.scale(effect, vib.attrs.getUsage());
            } else {
                // Vibration should not run, use null effect to remove registered effect.
                effect = null;
            }
            vibrator.updateAlwaysOn(vib.alwaysOnId, effect);
        }
    }
    
    //add by AAC for Richtap support
    private void doVibratorOnEnvelope(int[] relativeTime, int[] scaleArr, int[] freqArr, boolean steepMode, int amplitude, int uid, VibrationAttributes attrs) {
        synchronized (richTapService) {
            richTapService.richTapVibratorOnEnvelope(relativeTime, scaleArr, freqArr, steepMode, amplitude);
        }
    }

    private long doVibratorOnPatternHe(VibrationEffect effect, int uid){
        synchronized (richTapService){
            richTapService.richTapVibratorOnPatternHe(effect);
            return 0;
        }
    }

    private void doStopVibrateLocked() {
        synchronized (richTapService) {
            if (DEBUG){
                Slog.d(TAG, "time before vibratorStop : " + System.currentTimeMillis());
            }
            richTapService.richTapVibratorStop();
            if (DEBUG){
                Slog.d(TAG, "time after vibratorStop : " + System.currentTimeMillis());
            }
        }
    }

    @GuardedBy("mLock")
    private long doVibratorOnExtPrebakedEffectLocked(VibrationEffect effect) {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "doVibratorOnExtPrebakedEffectLocked");
        try {
            final RichTapVibrationEffect.ExtPrebaked prebaked = (RichTapVibrationEffect.ExtPrebaked) effect;
            richTapService.richTapVibratorSetAmplitude(255);
            richTapService.richTapVibratorPerform((int)prebaked.getId(), (byte)prebaked.getScale());
            return 0;
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }
    // end modify by AAC

    @GuardedBy("mLock")
    private Vibration.Status startVibrationLocked(Vibration vib) {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "startVibrationLocked");
        try {
            CombinedVibration combEffect = vib.getEffect();
            if (combEffect instanceof CombinedVibration.Mono) {
                VibrationEffect vibrEffect = ((CombinedVibration.Mono)combEffect).getEffect();
                if (vibrEffect instanceof RichTapVibrationEffect.ExtPrebaked) {
                    if(richTapService != null) {
                        doVibratorOnExtPrebakedEffectLocked(vibrEffect);
                        return Vibration.Status.RUNNING;
                    } else {
                        Slog.d(TAG, "richTapService is null or current is calling state, ignore current ExtPrebaked");
                        return Vibration.Status.IGNORED_FOR_RINGTONE;
                    }
                } else if (vibrEffect instanceof RichTapVibrationEffect.Envelope) {
                    if(richTapService != null){
                        RichTapVibrationEffect.Envelope envelope = (RichTapVibrationEffect.Envelope) vibrEffect;
                        doVibratorOnEnvelope(envelope.getRelativeTimeArr(), envelope.getScaleArr(), envelope.getFreqArr(),
                                envelope.isSteepMode(), envelope.getAmplitude(), vib.uid, vib.attrs);
                        return Vibration.Status.RUNNING;
                    } else {
                        Slog.d(TAG, "richTapService is null or current is calling state, ignore current Envelope");
                        return Vibration.Status.IGNORED_FOR_RINGTONE;
                    }
                } else if (vibrEffect instanceof RichTapVibrationEffect.PatternHe) {
                    if(richTapService != null){
                        RichTapVibrationEffect.PatternHe patternHe = (RichTapVibrationEffect.PatternHe) vibrEffect;
                        Slog.d(TAG, "vibratorservice play he");
                        doVibratorOnPatternHe(patternHe, vib.uid);
                        return Vibration.Status.RUNNING;
                    }else{
                        Slog.d(TAG, "richTapService is null or current is calling state, ignore current PatternHe");
                        return Vibration.Status.IGNORED_FOR_RINGTONE;
                    }
                }
            }
            vib.updateEffects(effect -> mVibrationScaler.scale(effect, vib.attrs.getUsage()));
            boolean inputDevicesAvailable = mInputDeviceDelegate.vibrateIfAvailable(
                    vib.uid, vib.opPkg, vib.getEffect(), vib.reason, vib.attrs);
            if (inputDevicesAvailable) {
                return Vibration.Status.FORWARDED_TO_INPUT_DEVICES;
            }

            VibrationStepConductor conductor = new VibrationStepConductor(vib, mVibrationSettings,
                    mDeviceVibrationEffectAdapter, mVibrators, mVibrationThreadCallbacks);
            if (mCurrentVibration == null) {
                return startVibrationOnThreadLocked(conductor);
            }
            // If there's already a vibration queued (waiting for the previous one to finish
            // cancelling), end it cleanly and replace it with the new one.
            clearNextVibrationLocked(Vibration.Status.IGNORED_SUPERSEDED);
            mNextVibration = conductor;
            return Vibration.Status.RUNNING;
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    @GuardedBy("mLock")
    private Vibration.Status startVibrationOnThreadLocked(VibrationStepConductor conductor) {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "startVibrationThreadLocked");
        try {
            Vibration vib = conductor.getVibration();
            int mode = startAppOpModeLocked(vib.uid, vib.opPkg, vib.attrs);
            switch (mode) {
                case AppOpsManager.MODE_ALLOWED:
                    Trace.asyncTraceBegin(Trace.TRACE_TAG_VIBRATOR, "vibration", 0);
                    mCurrentVibration = conductor;
                    if (!mVibrationThread.runVibrationOnVibrationThread(mCurrentVibration)) {
                        // Shouldn't happen. The method call already logs a wtf.
                        mCurrentVibration = null;  // Aborted.
                        return Vibration.Status.IGNORED_ERROR_SCHEDULING;
                    }
                    return Vibration.Status.RUNNING;
                case AppOpsManager.MODE_ERRORED:
                    Slog.w(TAG, "Start AppOpsManager operation errored for uid " + vib.uid);
                    return Vibration.Status.IGNORED_ERROR_APP_OPS;
                default:
                    return Vibration.Status.IGNORED_APP_OPS;
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    @GuardedBy("mLock")
    private void endVibrationLocked(Vibration vib, Vibration.Status status) {
        vib.end(status);
        logVibrationStatus(vib.uid, vib.attrs, status);
        mVibratorManagerRecords.record(vib);
    }

    @GuardedBy("mLock")
    private void endVibrationLocked(ExternalVibrationHolder vib, Vibration.Status status) {
        vib.end(status);
        logVibrationStatus(vib.externalVibration.getUid(),
                vib.externalVibration.getVibrationAttributes(), status);
        mVibratorManagerRecords.record(vib);
    }

    private void logVibrationStatus(int uid, VibrationAttributes attrs, Vibration.Status status) {
        switch (status) {
            case IGNORED_BACKGROUND:
                Slog.e(TAG, "Ignoring incoming vibration as process with"
                        + " uid= " + uid + " is background," + " attrs= " + attrs);
                break;
            case IGNORED_ERROR_APP_OPS:
                Slog.w(TAG, "Would be an error: vibrate from uid " + uid);
                break;
            case IGNORED_FOR_ALARM:
                if (DEBUG) {
                    Slog.d(TAG, "Ignoring incoming vibration in favor of alarm vibration");
                }
                break;
            case IGNORED_FOR_EXTERNAL:
                if (DEBUG) {
                    Slog.d(TAG, "Ignoring incoming vibration for current external vibration");
                }
                break;
            case IGNORED_FOR_ONGOING:
                if (DEBUG) {
                    Slog.d(TAG, "Ignoring incoming vibration in favor of repeating vibration");
                }
                break;
            case IGNORED_FOR_RINGER_MODE:
                if (DEBUG) {
                    Slog.d(TAG, "Ignoring incoming vibration because of ringer mode, attrs="
                            + attrs);
                }
                break;
            case IGNORED_FOR_RINGTONE:
                if (DEBUG) {
                    Slog.d(TAG, "Ignoring incoming vibration in favor of ringtone vibration");
                }
                break;

            default:
                if (DEBUG) {
                    Slog.d(TAG, "Vibration for uid=" + uid + " and with attrs=" + attrs
                            + " ended with status " + status);
                }
        }
    }

    @GuardedBy("mLock")
    private void reportFinishedVibrationLocked(Vibration.Status status) {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "reportFinishVibrationLocked");
        Trace.asyncTraceEnd(Trace.TRACE_TAG_VIBRATOR, "vibration", 0);
        try {
            Vibration vib = mCurrentVibration.getVibration();
            if (DEBUG) {
                Slog.d(TAG, "Reporting vibration " + vib.id + " finished with status " + status);
            }
            endVibrationLocked(vib, status);
            finishAppOpModeLocked(vib.uid, vib.opPkg);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    private void onSyncedVibrationComplete(long vibrationId) {
        synchronized (mLock) {
            if (mCurrentVibration != null && mCurrentVibration.getVibration().id == vibrationId) {
                if (DEBUG) {
                    Slog.d(TAG, "Synced vibration " + vibrationId + " complete, notifying thread");
                }
                mCurrentVibration.notifySyncedVibrationComplete();
            }
        }
    }

    private void onVibrationComplete(int vibratorId, long vibrationId) {
        synchronized (mLock) {
            if (mCurrentVibration != null && mCurrentVibration.getVibration().id == vibrationId) {
                if (DEBUG) {
                    Slog.d(TAG, "Vibration " + vibrationId + " on vibrator " + vibratorId
                            + " complete, notifying thread");
                }
                mCurrentVibration.notifyVibratorComplete(vibratorId);
            }
        }
    }

    /**
     * Check if given vibration should be ignored by this service because of the ongoing vibration.
     *
     * @return One of Vibration.Status.IGNORED_* values if the vibration should be ignored, null
     * otherwise.
     */
    @GuardedBy("mLock")
    @Nullable
    private Vibration.Status shouldIgnoreVibrationForOngoingLocked(Vibration vib) {
        if (mCurrentExternalVibration != null) {
            // If something has external control of the vibrator, assume that it's more important.
            return Vibration.Status.IGNORED_FOR_EXTERNAL;
        }

        if (mCurrentVibration == null || vib.isRepeating()) {
            // Incoming repeating vibrations always take precedence over ongoing vibrations.
            return null;
        }

        Vibration currentVibration = mCurrentVibration.getVibration();
        if (currentVibration.hasEnded()) {
            // Current vibration is finishing up, it should not block incoming vibrations.
            return null;
        }

        if (currentVibration.attrs.getUsage() == VibrationAttributes.USAGE_ALARM) {
            return Vibration.Status.IGNORED_FOR_ALARM;
        }

        if (currentVibration.attrs.getUsage() == VibrationAttributes.USAGE_RINGTONE) {
            return Vibration.Status.IGNORED_FOR_RINGTONE;
        }

        if (currentVibration.isRepeating()) {
            return Vibration.Status.IGNORED_FOR_ONGOING;
        }
        return null;
    }

    /**
     * Check if given vibration should be ignored by this service.
     *
     * @return One of Vibration.Status.IGNORED_* values if the vibration should be ignored, null
     * otherwise.
     */
    @GuardedBy("mLock")
    @Nullable
    private Vibration.Status shouldIgnoreVibrationLocked(int uid, String opPkg,
            VibrationAttributes attrs) {
        Vibration.Status statusFromSettings = mVibrationSettings.shouldIgnoreVibration(uid, attrs);
        if (statusFromSettings != null) {
            return statusFromSettings;
        }

        int mode = checkAppOpModeLocked(uid, opPkg, attrs);
        if (mode != AppOpsManager.MODE_ALLOWED) {
            if (mode == AppOpsManager.MODE_ERRORED) {
                // We might be getting calls from within system_server, so we don't actually
                // want to throw a SecurityException here.
                return Vibration.Status.IGNORED_ERROR_APP_OPS;
            } else {
                return Vibration.Status.IGNORED_APP_OPS;
            }
        }

        return null;
    }

    /**
     * Return true if the vibration has the same token and usage belongs to given usage class.
     *
     * @param vib         The ongoing or pending vibration to be cancelled.
     * @param usageFilter The vibration usages to be cancelled, any bitwise combination of
     *                    VibrationAttributes.USAGE_* values.
     * @param token       The binder token to identify the vibration origin. Only vibrations
     *                    started with the same token can be cancelled with it.
     */
    private boolean shouldCancelVibration(Vibration vib, int usageFilter, IBinder token) {
        return (vib.token == token) && shouldCancelVibration(vib.attrs, usageFilter);
    }

    /**
     * Return true if the external vibration usage belongs to given usage class.
     *
     * @param attrs       The attributes of an ongoing or pending vibration to be cancelled.
     * @param usageFilter The vibration usages to be cancelled, any bitwise combination of
     *                    VibrationAttributes.USAGE_* values.
     */
    private boolean shouldCancelVibration(VibrationAttributes attrs, int usageFilter) {
        if (attrs.getUsage() == VibrationAttributes.USAGE_UNKNOWN) {
            // Special case, usage UNKNOWN would match all filters. Instead it should only match if
            // it's cancelling that usage specifically, or if cancelling all usages.
            return usageFilter == VibrationAttributes.USAGE_UNKNOWN
                    || usageFilter == VibrationAttributes.USAGE_FILTER_MATCH_ALL;
        }
        return (usageFilter & attrs.getUsage()) == attrs.getUsage();
    }

    /**
     * Check which mode should be set for a vibration with given {@code uid}, {@code opPkg} and
     * {@code attrs}. This will return one of the AppOpsManager.MODE_*.
     */
    @GuardedBy("mLock")
    private int checkAppOpModeLocked(int uid, String opPkg, VibrationAttributes attrs) {
        int mode = mAppOps.checkAudioOpNoThrow(AppOpsManager.OP_VIBRATE,
                attrs.getAudioUsage(), uid, opPkg);
        int fixedMode = fixupAppOpModeLocked(mode, attrs);
        if (mode != fixedMode && fixedMode == AppOpsManager.MODE_ALLOWED) {
            // If we're just ignoring the vibration op then this is set by DND and we should ignore
            // if we're asked to bypass. AppOps won't be able to record this operation, so make
            // sure we at least note it in the logs for debugging.
            Slog.d(TAG, "Bypassing DND for vibrate from uid " + uid);
        }
        return fixedMode;
    }

    /** Start an operation in {@link AppOpsManager}, if allowed. */
    @GuardedBy("mLock")
    private int startAppOpModeLocked(int uid, String opPkg, VibrationAttributes attrs) {
        return fixupAppOpModeLocked(
                mAppOps.startOpNoThrow(AppOpsManager.OP_VIBRATE, uid, opPkg), attrs);
    }

    /**
     * Finish a previously started operation in {@link AppOpsManager}. This will be a noop if no
     * operation with same uid was previously started.
     */
    @GuardedBy("mLock")
    private void finishAppOpModeLocked(int uid, String opPkg) {
        mAppOps.finishOp(AppOpsManager.OP_VIBRATE, uid, opPkg);
    }

    /**
     * Enforces {@link android.Manifest.permission#UPDATE_APP_OPS_STATS} to incoming UID if it's
     * different from the calling UID.
     */
    private void enforceUpdateAppOpsStatsPermission(int uid) {
        if (uid == Binder.getCallingUid()) {
            return;
        }
        if (Binder.getCallingPid() == Process.myPid()) {
            return;
        }
        mContext.enforcePermission(android.Manifest.permission.UPDATE_APP_OPS_STATS,
                Binder.getCallingPid(), Binder.getCallingUid(), null);
    }

    /**
     * Validate the incoming {@link CombinedVibration}.
     *
     * We can't throw exceptions here since we might be called from some system_server component,
     * which would bring the whole system down.
     *
     * @return whether the CombinedVibrationEffect is non-null and valid
     */
    private static boolean isEffectValid(@Nullable CombinedVibration effect) {
        if (effect == null) {
            Slog.wtf(TAG, "effect must not be null");
            return false;
        }
        try {
            effect.validate();
        } catch (Exception e) {
            Slog.wtf(TAG, "Encountered issue when verifying CombinedVibrationEffect.", e);
            return false;
        }
        return true;
    }

    /**
     * Sets fallback effects to all prebaked ones in given combination of effects, based on {@link
     * VibrationSettings#getFallbackEffect}.
     */
    private void fillVibrationFallbacks(Vibration vib, CombinedVibration effect) {
        if (effect instanceof CombinedVibration.Mono) {
            fillVibrationFallbacks(vib, ((CombinedVibration.Mono) effect).getEffect());
        } else if (effect instanceof CombinedVibration.Stereo) {
            SparseArray<VibrationEffect> effects =
                    ((CombinedVibration.Stereo) effect).getEffects();
            for (int i = 0; i < effects.size(); i++) {
                fillVibrationFallbacks(vib, effects.valueAt(i));
            }
        } else if (effect instanceof CombinedVibration.Sequential) {
            List<CombinedVibration> effects =
                    ((CombinedVibration.Sequential) effect).getEffects();
            for (int i = 0; i < effects.size(); i++) {
                fillVibrationFallbacks(vib, effects.get(i));
            }
        }
    }

    private void fillVibrationFallbacks(Vibration vib, VibrationEffect effect) {
        if (effect instanceof RichTapVibrationEffect.ExtPrebaked ||
            effect instanceof RichTapVibrationEffect.Envelope ||
            effect instanceof RichTapVibrationEffect.PatternHe) {
            return;
        }
        VibrationEffect.Composed composed = (VibrationEffect.Composed) effect;
        int segmentCount = composed.getSegments().size();
        for (int i = 0; i < segmentCount; i++) {
            VibrationEffectSegment segment = composed.getSegments().get(i);
            if (segment instanceof PrebakedSegment) {
                PrebakedSegment prebaked = (PrebakedSegment) segment;
                VibrationEffect fallback = mVibrationSettings.getFallbackEffect(
                        prebaked.getEffectId());
                if (prebaked.shouldFallback() && fallback != null) {
                    vib.addFallback(prebaked.getEffectId(), fallback);
                }
            }
        }
    }

    /**
     * Return new {@link VibrationAttributes} that only applies flags that this user has permissions
     * to use.
     */
    @NonNull
    private VibrationAttributes fixupVibrationAttributes(@Nullable VibrationAttributes attrs,
            @Nullable CombinedVibration effect) {
        if (attrs == null) {
            attrs = DEFAULT_ATTRIBUTES;
        }
        int usage = attrs.getUsage();
        if ((usage == VibrationAttributes.USAGE_UNKNOWN)
                && (effect != null) && effect.isHapticFeedbackCandidate()) {
            usage = VibrationAttributes.USAGE_TOUCH;
        }
        int flags = attrs.getFlags();
        if ((flags & ATTRIBUTES_ALL_BYPASS_FLAGS) != 0) {
            if (!(hasPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                    || hasPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
                    || hasPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING))) {
                // Remove bypass flags from attributes if the app does not have permissions.
                flags &= ~ATTRIBUTES_ALL_BYPASS_FLAGS;
            }
        }
        if ((usage == attrs.getUsage()) && (flags == attrs.getFlags())) {
            return attrs;
        }
        return new VibrationAttributes.Builder(attrs)
                .setUsage(usage)
                .setFlags(flags, attrs.getFlags())
                .build();
    }

    @GuardedBy("mLock")
    @Nullable
    private SparseArray<PrebakedSegment> fixupAlwaysOnEffectsLocked(
            CombinedVibration effect) {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "fixupAlwaysOnEffectsLocked");
        try {
            SparseArray<VibrationEffect> effects;
            if (effect instanceof CombinedVibration.Mono) {
                VibrationEffect syncedEffect = ((CombinedVibration.Mono) effect).getEffect();
                effects = transformAllVibratorsLocked(unused -> syncedEffect);
            } else if (effect instanceof CombinedVibration.Stereo) {
                effects = ((CombinedVibration.Stereo) effect).getEffects();
            } else {
                // Only synced combinations can be used for always-on effects.
                return null;
            }
            SparseArray<PrebakedSegment> result = new SparseArray<>();
            for (int i = 0; i < effects.size(); i++) {
                PrebakedSegment prebaked = extractPrebakedSegment(effects.valueAt(i));
                if (prebaked == null) {
                    Slog.e(TAG, "Only prebaked effects supported for always-on.");
                    return null;
                }
                int vibratorId = effects.keyAt(i);
                VibratorController vibrator = mVibrators.get(vibratorId);
                if (vibrator != null && vibrator.hasCapability(IVibrator.CAP_ALWAYS_ON_CONTROL)) {
                    result.put(vibratorId, prebaked);
                }
            }
            if (result.size() == 0) {
                return null;
            }
            return result;
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    @Nullable
    private static PrebakedSegment extractPrebakedSegment(VibrationEffect effect) {
        if (effect instanceof VibrationEffect.Composed) {
            VibrationEffect.Composed composed = (VibrationEffect.Composed) effect;
            if (composed.getSegments().size() == 1) {
                VibrationEffectSegment segment = composed.getSegments().get(0);
                if (segment instanceof PrebakedSegment) {
                    return (PrebakedSegment) segment;
                }
            }
        }
        return null;
    }

    /**
     * Check given mode, one of the AppOpsManager.MODE_*, against {@link VibrationAttributes} to
     * allow bypassing {@link AppOpsManager} checks.
     */
    @GuardedBy("mLock")
    private int fixupAppOpModeLocked(int mode, VibrationAttributes attrs) {
        if (mode == AppOpsManager.MODE_IGNORED
                && attrs.isFlagSet(VibrationAttributes.FLAG_BYPASS_INTERRUPTION_POLICY)) {
            return AppOpsManager.MODE_ALLOWED;
        }
        return mode;
    }

    private boolean hasPermission(String permission) {
        return mContext.checkCallingOrSelfPermission(permission)
                == PackageManager.PERMISSION_GRANTED;
    }

    @GuardedBy("mLock")
    private boolean shouldCancelOnScreenOffLocked(@Nullable VibrationStepConductor conductor) {
        if (conductor == null) {
            return false;
        }
        Vibration vib = conductor.getVibration();
        return mVibrationSettings.shouldCancelVibrationOnScreenOff(
                vib.uid, vib.opPkg, vib.attrs.getUsage(), vib.startUptimeMillis);
    }

    @GuardedBy("mLock")
    private void onAllVibratorsLocked(Consumer<VibratorController> consumer) {
        for (int i = 0; i < mVibrators.size(); i++) {
            consumer.accept(mVibrators.valueAt(i));
        }
    }

    @GuardedBy("mLock")
    private <T> SparseArray<T> transformAllVibratorsLocked(Function<VibratorController, T> fn) {
        SparseArray<T> ret = new SparseArray<>(mVibrators.size());
        for (int i = 0; i < mVibrators.size(); i++) {
            ret.put(mVibrators.keyAt(i), fn.apply(mVibrators.valueAt(i)));
        }
        return ret;
    }

    /** Point of injection for test dependencies */
    @VisibleForTesting
    static class Injector {

        NativeWrapper getNativeWrapper() {
            return new NativeWrapper();
        }

        Handler createHandler(Looper looper) {
            return new Handler(looper);
        }

        IBatteryStats getBatteryStatsService() {
            return IBatteryStats.Stub.asInterface(ServiceManager.getService(
                    BatteryStats.SERVICE_NAME));
        }

        VibratorController createVibratorController(int vibratorId,
                VibratorController.OnVibrationCompleteListener listener) {
            return new VibratorController(vibratorId, listener);
        }

        void addService(String name, IBinder service) {
            ServiceManager.addService(name, service);
        }
    }

    /**
     * Implementation of {@link VibrationThread.VibratorManagerHooks} that controls synced
     * vibrations and reports them when finished.
     */
    private final class VibrationThreadCallbacks implements VibrationThread.VibratorManagerHooks {

        @Override
        public boolean prepareSyncedVibration(long requiredCapabilities, int[] vibratorIds) {
            if ((mCapabilities & requiredCapabilities) != requiredCapabilities) {
                // This sync step requires capabilities this device doesn't have, skipping sync...
                return false;
            }
            return mNativeWrapper.prepareSynced(vibratorIds);
        }

        @Override
        public boolean triggerSyncedVibration(long vibrationId) {
            return mNativeWrapper.triggerSynced(vibrationId);
        }

        @Override
        public void cancelSyncedVibration() {
            mNativeWrapper.cancelSynced();
        }

        @Override
        public void noteVibratorOn(int uid, long duration) {
            try {
                if (duration <= 0) {
                    return;
                }
                if (duration == Long.MAX_VALUE) {
                    // Repeating duration has started. Report a fixed duration here, noteVibratorOff
                    // should be called when this is cancelled.
                    duration = BATTERY_STATS_REPEATING_VIBRATION_DURATION;
                }
                mBatteryStatsService.noteVibratorOn(uid, duration);
                FrameworkStatsLog.write_non_chained(FrameworkStatsLog.VIBRATOR_STATE_CHANGED,
                        uid, null, FrameworkStatsLog.VIBRATOR_STATE_CHANGED__STATE__ON,
                        duration);
            } catch (RemoteException e) {
            }
        }

        @Override
        public void noteVibratorOff(int uid) {
            try {
                mBatteryStatsService.noteVibratorOff(uid);
                FrameworkStatsLog.write_non_chained(FrameworkStatsLog.VIBRATOR_STATE_CHANGED,
                        uid, null, FrameworkStatsLog.VIBRATOR_STATE_CHANGED__STATE__OFF,
                        /* duration= */ 0);
            } catch (RemoteException e) {
            }
        }

        @Override
        public void onVibrationCompleted(long vibrationId, Vibration.Status status) {
            if (DEBUG) {
                Slog.d(TAG, "Vibration " + vibrationId + " finished with status " + status);
            }
            synchronized (mLock) {
                if (mCurrentVibration != null
                        && mCurrentVibration.getVibration().id == vibrationId) {
                    reportFinishedVibrationLocked(status);
                }
            }
        }

        @Override
        public void onVibrationThreadReleased(long vibrationId) {
            if (DEBUG) {
                Slog.d(TAG, "VibrationThread released after finished vibration");
            }
            synchronized (mLock) {
                if (DEBUG) {
                    Slog.d(TAG, "Processing VibrationThread released callback");
                }
                if (Build.IS_DEBUGGABLE && mCurrentVibration != null
                        && mCurrentVibration.getVibration().id != vibrationId) {
                    Slog.wtf(TAG, TextUtils.formatSimple(
                            "VibrationId mismatch on release. expected=%d, released=%d",
                            mCurrentVibration.getVibration().id, vibrationId));
                }
                mCurrentVibration = null;
                if (mNextVibration != null) {
                    VibrationStepConductor nextConductor = mNextVibration;
                    mNextVibration = null;
                    Vibration.Status status = startVibrationOnThreadLocked(nextConductor);
                    if (status != Vibration.Status.RUNNING) {
                        endVibrationLocked(nextConductor.getVibration(), status);
                    }
                }
            }
        }
    }

    /** Listener for synced vibration completion callbacks from native. */
    @VisibleForTesting
    interface OnSyncedVibrationCompleteListener {

        /** Callback triggered when synced vibration is complete. */
        void onComplete(long vibrationId);
    }

    /**
     * Implementation of listeners to native vibrators with a weak reference to this service.
     */
    private static final class VibrationCompleteListener implements
            VibratorController.OnVibrationCompleteListener, OnSyncedVibrationCompleteListener {
        private WeakReference<VibratorManagerService> mServiceRef;

        VibrationCompleteListener(VibratorManagerService service) {
            mServiceRef = new WeakReference<>(service);
        }

        @Override
        public void onComplete(long vibrationId) {
            VibratorManagerService service = mServiceRef.get();
            if (service != null) {
                service.onSyncedVibrationComplete(vibrationId);
            }
        }

        @Override
        public void onComplete(int vibratorId, long vibrationId) {
            VibratorManagerService service = mServiceRef.get();
            if (service != null) {
                service.onVibrationComplete(vibratorId, vibrationId);
            }
        }
    }

    /**
     * Combination of prekabed vibrations on multiple vibrators, with the same {@link
     * VibrationAttributes}, that can be set for always-on effects.
     */
    private static final class AlwaysOnVibration {
        public final int alwaysOnId;
        public final int uid;
        public final String opPkg;
        public final VibrationAttributes attrs;
        public final SparseArray<PrebakedSegment> effects;

        AlwaysOnVibration(int alwaysOnId, int uid, String opPkg, VibrationAttributes attrs,
                SparseArray<PrebakedSegment> effects) {
            this.alwaysOnId = alwaysOnId;
            this.uid = uid;
            this.opPkg = opPkg;
            this.attrs = attrs;
            this.effects = effects;
        }
    }

    /** Holder for a {@link ExternalVibration}. */
    private final class ExternalVibrationHolder implements IBinder.DeathRecipient {

        public final ExternalVibration externalVibration;
        public int scale;

        private final long mStartUptimeMillis;
        private final long mStartTimeDebug;

        private long mEndUptimeMillis;
        private long mEndTimeDebug;
        private Vibration.Status mStatus;

        private ExternalVibrationHolder(ExternalVibration externalVibration) {
            this.externalVibration = externalVibration;
            this.scale = IExternalVibratorService.SCALE_NONE;
            mStartUptimeMillis = SystemClock.uptimeMillis();
            mStartTimeDebug = System.currentTimeMillis();
            mStatus = Vibration.Status.RUNNING;
        }

        public void end(Vibration.Status status) {
            if (mStatus != Vibration.Status.RUNNING) {
                // Vibration already ended, keep first ending status set and ignore this one.
                return;
            }
            mStatus = status;
            mEndUptimeMillis = SystemClock.uptimeMillis();
            mEndTimeDebug = System.currentTimeMillis();
        }

        public void binderDied() {
            synchronized (mLock) {
                if (mCurrentExternalVibration != null) {
                    if (DEBUG) {
                        Slog.d(TAG, "External vibration finished because binder died");
                    }
                    endExternalVibrateLocked(Vibration.Status.CANCELLED_BINDER_DIED,
                            /* continueExternalControl= */ false);
                }
            }
        }

        public Vibration.DebugInfo getDebugInfo() {
            long durationMs = mEndUptimeMillis == 0 ? -1 : mEndUptimeMillis - mStartUptimeMillis;
            return new Vibration.DebugInfo(
                    mStartTimeDebug, mEndTimeDebug, durationMs,
                    /* effect= */ null, /* originalEffect= */ null, scale,
                    externalVibration.getVibrationAttributes(), externalVibration.getUid(),
                    externalVibration.getPackage(), /* reason= */ null, mStatus);
        }
    }

    /** Wrapper around the static-native methods of {@link VibratorManagerService} for tests. */
    @VisibleForTesting
    public static class NativeWrapper {

        private long mNativeServicePtr = 0;

        /** Returns native pointer to newly created controller and connects with HAL service. */
        public void init(OnSyncedVibrationCompleteListener listener) {
            mNativeServicePtr = nativeInit(listener);
            long finalizerPtr = nativeGetFinalizer();

            if (finalizerPtr != 0) {
                NativeAllocationRegistry registry =
                        NativeAllocationRegistry.createMalloced(
                                VibratorManagerService.class.getClassLoader(), finalizerPtr);
                registry.registerNativeAllocation(this, mNativeServicePtr);
            }
        }

        /** Returns manager capabilities. */
        public long getCapabilities() {
            return nativeGetCapabilities(mNativeServicePtr);
        }

        /** Returns vibrator ids. */
        public int[] getVibratorIds() {
            return nativeGetVibratorIds(mNativeServicePtr);
        }

        /** Prepare vibrators for triggering vibrations in sync. */
        public boolean prepareSynced(@NonNull int[] vibratorIds) {
            return nativePrepareSynced(mNativeServicePtr, vibratorIds);
        }

        /** Trigger prepared synced vibration. */
        public boolean triggerSynced(long vibrationId) {
            return nativeTriggerSynced(mNativeServicePtr, vibrationId);
        }

        /** Cancel prepared synced vibration. */
        public void cancelSynced() {
            nativeCancelSynced(mNativeServicePtr);
        }
    }

    /** Keep records of vibrations played and provide debug information for this service. */
    private static final class VibratorManagerRecords {
        private final SparseArray<LinkedList<Vibration.DebugInfo>> mPreviousVibrations =
                new SparseArray<>();
        private final LinkedList<Vibration.DebugInfo> mPreviousExternalVibrations =
                new LinkedList<>();
        private final int mPreviousVibrationsLimit;

        VibratorManagerRecords(int limit) {
            mPreviousVibrationsLimit = limit;
        }

        synchronized void record(Vibration vib) {
            int usage = vib.attrs.getUsage();
            if (!mPreviousVibrations.contains(usage)) {
                mPreviousVibrations.put(usage, new LinkedList<>());
            }
            record(mPreviousVibrations.get(usage), vib.getDebugInfo());
        }

        synchronized void record(ExternalVibrationHolder vib) {
            record(mPreviousExternalVibrations, vib.getDebugInfo());
        }

        synchronized void record(LinkedList<Vibration.DebugInfo> records,
                Vibration.DebugInfo info) {
            if (records.size() > mPreviousVibrationsLimit) {
                records.removeFirst();
            }
            records.addLast(info);
        }

        synchronized void dumpText(PrintWriter pw) {
            for (int i = 0; i < mPreviousVibrations.size(); i++) {
                pw.println();
                pw.print("  Previous vibrations for usage ");
                pw.print(VibrationAttributes.usageToString(mPreviousVibrations.keyAt(i)));
                pw.println(":");
                for (Vibration.DebugInfo info : mPreviousVibrations.valueAt(i)) {
                    pw.println("    " + info);
                }
            }

            pw.println();
            pw.println("  Previous external vibrations:");
            for (Vibration.DebugInfo info : mPreviousExternalVibrations) {
                pw.println("    " + info);
            }
        }

        synchronized void dumpProto(ProtoOutputStream proto) {
            for (int i = 0; i < mPreviousVibrations.size(); i++) {
                long fieldId;
                switch (mPreviousVibrations.keyAt(i)) {
                    case VibrationAttributes.USAGE_RINGTONE:
                        fieldId = VibratorManagerServiceDumpProto.PREVIOUS_RING_VIBRATIONS;
                        break;
                    case VibrationAttributes.USAGE_NOTIFICATION:
                        fieldId = VibratorManagerServiceDumpProto
                                .PREVIOUS_NOTIFICATION_VIBRATIONS;
                        break;
                    case VibrationAttributes.USAGE_ALARM:
                        fieldId = VibratorManagerServiceDumpProto.PREVIOUS_ALARM_VIBRATIONS;
                        break;
                    default:
                        fieldId = VibratorManagerServiceDumpProto.PREVIOUS_VIBRATIONS;
                }
                for (Vibration.DebugInfo info : mPreviousVibrations.valueAt(i)) {
                    info.dumpProto(proto, fieldId);
                }
            }

            for (Vibration.DebugInfo info : mPreviousExternalVibrations) {
                info.dumpProto(proto,
                        VibratorManagerServiceDumpProto.PREVIOUS_EXTERNAL_VIBRATIONS);
            }
        }
    }

    /** Clears mNextVibration if set, ending it cleanly */
    @GuardedBy("mLock")
    private void clearNextVibrationLocked(Vibration.Status endStatus) {
        if (mNextVibration != null) {
            endVibrationLocked(mNextVibration.getVibration(), endStatus);
            mNextVibration = null;
        }
    }

    /**
     * Ends the external vibration, and clears related service state.
     *
     * @param status the status to end the associated Vibration with
     * @param continueExternalControl indicates whether external control will continue. If not, the
     *                                HAL will have external control turned off.
     */
    @GuardedBy("mLock")
    private void endExternalVibrateLocked(Vibration.Status status,
            boolean continueExternalControl) {
        Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "endExternalVibrateLocked");
        try {
            if (mCurrentExternalVibration == null) {
                return;
            }
            endVibrationLocked(mCurrentExternalVibration, status);
            mCurrentExternalVibration.externalVibration.unlinkToDeath(
                    mCurrentExternalVibration);
            mCurrentExternalVibration = null;
            if (!continueExternalControl) {
                setExternalControl(false);
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
        }
    }

    /** Implementation of {@link IExternalVibratorService} to be triggered on external control. */
    @VisibleForTesting
    final class ExternalVibratorService extends IExternalVibratorService.Stub {

        @Override
        public int onExternalVibrationStart(ExternalVibration vib) {
            if (!hasExternalControlCapability()) {
                return IExternalVibratorService.SCALE_MUTE;
            }
            if (ActivityManager.checkComponentPermission(android.Manifest.permission.VIBRATE,
                    vib.getUid(), -1 /*owningUid*/, true /*exported*/)
                    != PackageManager.PERMISSION_GRANTED) {
                Slog.w(TAG, "pkg=" + vib.getPackage() + ", uid=" + vib.getUid()
                        + " tried to play externally controlled vibration"
                        + " without VIBRATE permission, ignoring.");
                return IExternalVibratorService.SCALE_MUTE;
            }

            VibrationAttributes attrs = fixupVibrationAttributes(vib.getVibrationAttributes(),
                    /* effect= */ null);
            if (attrs.isFlagSet(VibrationAttributes.FLAG_INVALIDATE_SETTINGS_CACHE)) {
                // Force update of user settings before checking if this vibration effect should
                // be ignored or scaled.
                mVibrationSettings.update();
            }

            boolean alreadyUnderExternalControl = false;
            boolean waitForCompletion = false;
            int scale;
            synchronized (mLock) {
                Vibration.Status ignoreStatus = shouldIgnoreVibrationLocked(
                        vib.getUid(), vib.getPackage(), attrs);
                if (ignoreStatus != null) {
                    ExternalVibrationHolder vibHolder = new ExternalVibrationHolder(vib);
                    vibHolder.scale = IExternalVibratorService.SCALE_MUTE;
                    endVibrationLocked(vibHolder, ignoreStatus);
                    return vibHolder.scale;
                }
                if (mCurrentExternalVibration != null
                        && mCurrentExternalVibration.externalVibration.equals(vib)) {
                    // We are already playing this external vibration, so we can return the same
                    // scale calculated in the previous call to this method.
                    return mCurrentExternalVibration.scale;
                }
                if (mCurrentExternalVibration == null) {
                    // If we're not under external control right now, then cancel any normal
                    // vibration that may be playing and ready the vibrator for external control.
                    if (mCurrentVibration != null) {
                        clearNextVibrationLocked(Vibration.Status.IGNORED_FOR_EXTERNAL);
                        mCurrentVibration.notifyCancelled(Vibration.Status.CANCELLED_SUPERSEDED,
                                /* immediate= */ true);
                        waitForCompletion = true;
                    }
                } else {
                    // At this point we have an externally controlled vibration playing already.
                    // Since the interface defines that only one externally controlled vibration can
                    // play at a time, we need to first mute the ongoing vibration and then return
                    // a scale from this function for the new one, so we can be assured that the
                    // ongoing will be muted in favor of the new vibration.
                    //
                    // Note that this doesn't support multiple concurrent external controls, as we
                    // would need to mute the old one still if it came from a different controller.
                    alreadyUnderExternalControl = true;
                    mCurrentExternalVibration.externalVibration.mute();
                    endExternalVibrateLocked(Vibration.Status.CANCELLED_SUPERSEDED,
                            /* continueExternalControl= */ true);
                }
                mCurrentExternalVibration = new ExternalVibrationHolder(vib);
                vib.linkToDeath(mCurrentExternalVibration);
                mCurrentExternalVibration.scale = mVibrationScaler.getExternalVibrationScale(
                        attrs.getUsage());
                scale = mCurrentExternalVibration.scale;
            }

            if (waitForCompletion) {
                if (!mVibrationThread.waitForThreadIdle(VIBRATION_CANCEL_WAIT_MILLIS)) {
                    Slog.e(TAG, "Timed out waiting for vibration to cancel");
                    synchronized (mLock) {
                        endExternalVibrateLocked(Vibration.Status.IGNORED_ERROR_CANCELLING,
                                /* continueExternalControl= */ false);
                    }
                    return IExternalVibratorService.SCALE_MUTE;
                }
            }
            if (!alreadyUnderExternalControl) {
                if (DEBUG) {
                    Slog.d(TAG, "Vibrator going under external control.");
                }
                setExternalControl(true);
            }
            if (DEBUG) {
                Slog.e(TAG, "Playing external vibration: " + vib);
            }
            return scale;
        }

        @Override
        public void onExternalVibrationStop(ExternalVibration vib) {
            synchronized (mLock) {
                if (mCurrentExternalVibration != null
                        && mCurrentExternalVibration.externalVibration.equals(vib)) {
                    if (DEBUG) {
                        Slog.e(TAG, "Stopping external vibration" + vib);
                    }
                    endExternalVibrateLocked(Vibration.Status.FINISHED,
                            /* continueExternalControl= */ false);
                }
            }
        }

        private boolean hasExternalControlCapability() {
            for (int i = 0; i < mVibrators.size(); i++) {
                if (mVibrators.valueAt(i).hasCapability(IVibrator.CAP_EXTERNAL_CONTROL)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Provide limited functionality from {@link VibratorManagerService} as shell commands. */
    private final class VibratorManagerShellCommand extends ShellCommand {
        public static final String SHELL_PACKAGE_NAME = "com.android.shell";

        private final class CommonOptions {
            public boolean force = false;
            public String description = "Shell command";
            public boolean background = false;

            CommonOptions() {
                String nextArg;
                while ((nextArg = peekNextArg()) != null) {
                    switch (nextArg) {
                        case "-f":
                            getNextArgRequired(); // consume "-f"
                            force = true;
                            break;
                        case "-B":
                            getNextArgRequired(); // consume "-B"
                            background = true;
                            break;
                        case "-d":
                            getNextArgRequired(); // consume "-d"
                            description = getNextArgRequired();
                            break;
                        default:
                            // nextArg is not a common option, finish reading.
                            return;
                    }
                }
            }
        }

        private final IBinder mShellCallbacksToken;

        private VibratorManagerShellCommand(IBinder shellCallbacksToken) {
            mShellCallbacksToken = shellCallbacksToken;
        }

        @Override
        public int onCommand(String cmd) {
            Trace.traceBegin(Trace.TRACE_TAG_VIBRATOR, "onCommand " + cmd);
            try {
                if ("list".equals(cmd)) {
                    return runListVibrators();
                }
                if ("synced".equals(cmd)) {
                    return runMono();
                }
                if ("combined".equals(cmd)) {
                    return runStereo();
                }
                if ("sequential".equals(cmd)) {
                    return runSequential();
                }
                if ("cancel".equals(cmd)) {
                    return runCancel();
                }
                return handleDefaultCommands(cmd);
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_VIBRATOR);
            }
        }

        private int runListVibrators() {
            try (PrintWriter pw = getOutPrintWriter();) {
                if (mVibratorIds.length == 0) {
                    pw.println("No vibrator found");
                } else {
                    for (int id : mVibratorIds) {
                        pw.println(id);
                    }
                }
                pw.println("");
                return 0;
            }
        }

        /**
         * Runs a CombinedVibration using the configured common options and attributes.
         */
        private void runVibrate(CommonOptions commonOptions, CombinedVibration combined) {
            VibrationAttributes attrs = createVibrationAttributes(commonOptions);
            // If running in the background, bind to death of the server binder rather than the
            // client, and the cancel command likewise uses the server binder reference to
            // only cancel background vibrations.
            IBinder deathBinder = commonOptions.background ? VibratorManagerService.this
                    : mShellCallbacksToken;
            Vibration vib = vibrateInternal(Binder.getCallingUid(), SHELL_PACKAGE_NAME, combined,
                    attrs, commonOptions.description, deathBinder);
            if (vib != null && !commonOptions.background) {
                try {
                    vib.waitForEnd();
                } catch (InterruptedException e) {
                }
            }
        }

        private int runMono() {
            runVibrate(new CommonOptions(), CombinedVibration.createParallel(nextEffect()));
            return 0;
        }

        private int runStereo() {
            CommonOptions commonOptions = new CommonOptions();
            CombinedVibration.ParallelCombination combination =
                    CombinedVibration.startParallel();
            while ("-v".equals(getNextOption())) {
                int vibratorId = Integer.parseInt(getNextArgRequired());
                combination.addVibrator(vibratorId, nextEffect());
            }
            runVibrate(commonOptions, combination.combine());
            return 0;
        }

        private int runSequential() {
            CommonOptions commonOptions = new CommonOptions();
            CombinedVibration.SequentialCombination combination =
                    CombinedVibration.startSequential();
            while ("-v".equals(getNextOption())) {
                int vibratorId = Integer.parseInt(getNextArgRequired());
                combination.addNext(vibratorId, nextEffect());
            }
            runVibrate(commonOptions, combination.combine());
            return 0;
        }

        private int runCancel() {
            // Cancel is only needed if the vibration was run in the background, otherwise it's
            // terminated by the shell command ending. In these cases, the token was that of the
            // service rather than the client.
            cancelVibrate(VibrationAttributes.USAGE_FILTER_MATCH_ALL, VibratorManagerService.this);
            return 0;
        }

        private VibrationEffect nextEffect() {
            VibrationEffect.Composition composition = VibrationEffect.startComposition();
            String nextArg;

            while ((nextArg = peekNextArg()) != null) {
                if ("oneshot".equals(nextArg)) {
                    addOneShotToComposition(composition);
                } else if ("waveform".equals(nextArg)) {
                    addWaveformToComposition(composition);
                } else if ("prebaked".equals(nextArg)) {
                    addPrebakedToComposition(composition);
                } else if ("primitives".equals(nextArg)) {
                    addPrimitivesToComposition(composition);
                } else {
                    // nextArg is not an effect, finish reading.
                    break;
                }
            }

            return composition.compose();
        }

        private void addOneShotToComposition(VibrationEffect.Composition composition) {
            boolean hasAmplitude = false;
            int delay = 0;

            getNextArgRequired(); // consume "oneshot"
            String nextOption;
            while ((nextOption = getNextOption()) != null) {
                if ("-a".equals(nextOption)) {
                    hasAmplitude = true;
                } else if ("-w".equals(nextOption)) {
                    delay = Integer.parseInt(getNextArgRequired());
                }
            }

            long duration = Long.parseLong(getNextArgRequired());
            int amplitude = hasAmplitude ? Integer.parseInt(getNextArgRequired())
                    : VibrationEffect.DEFAULT_AMPLITUDE;
            composition.addOffDuration(Duration.ofMillis(delay));
            composition.addEffect(VibrationEffect.createOneShot(duration, amplitude));
        }

        private void addWaveformToComposition(VibrationEffect.Composition composition) {
            boolean hasAmplitudes = false;
            boolean hasFrequencies = false;
            boolean isContinuous = false;
            int repeat = -1;
            int delay = 0;

            getNextArgRequired(); // consume "waveform"
            String nextOption;
            while ((nextOption = getNextOption()) != null) {
                if ("-a".equals(nextOption)) {
                    hasAmplitudes = true;
                } else if ("-r".equals(nextOption)) {
                    repeat = Integer.parseInt(getNextArgRequired());
                } else if ("-w".equals(nextOption)) {
                    delay = Integer.parseInt(getNextArgRequired());
                } else if ("-f".equals(nextOption)) {
                    hasFrequencies = true;
                } else if ("-c".equals(nextOption)) {
                    isContinuous = true;
                }
            }
            List<Integer> durations = new ArrayList<>();
            List<Float> amplitudes = new ArrayList<>();
            List<Float> frequencies = new ArrayList<>();

            float nextAmplitude = 0;
            String nextArg;
            while ((nextArg = peekNextArg()) != null) {
                try {
                    durations.add(Integer.parseInt(nextArg));
                    getNextArgRequired(); // consume the duration
                } catch (NumberFormatException e) {
                    // nextArg is not a duration, finish reading.
                    break;
                }
                if (hasAmplitudes) {
                    amplitudes.add(
                            Float.parseFloat(getNextArgRequired()) / VibrationEffect.MAX_AMPLITUDE);
                } else {
                    amplitudes.add(nextAmplitude);
                    nextAmplitude = 1 - nextAmplitude;
                }
                if (hasFrequencies) {
                    frequencies.add(Float.parseFloat(getNextArgRequired()));
                }
            }

            // Add delay before the waveform.
            composition.addOffDuration(Duration.ofMillis(delay));

            VibrationEffect.WaveformBuilder waveform = VibrationEffect.startWaveform();
            for (int i = 0; i < durations.size(); i++) {
                Duration transitionDuration = isContinuous
                        ? Duration.ofMillis(durations.get(i))
                        : Duration.ZERO;
                Duration sustainDuration = isContinuous
                        ? Duration.ZERO
                        : Duration.ofMillis(durations.get(i));

                if (hasFrequencies) {
                    waveform.addTransition(transitionDuration, targetAmplitude(amplitudes.get(i)),
                            targetFrequency(frequencies.get(i)));
                } else {
                    waveform.addTransition(transitionDuration, targetAmplitude(amplitudes.get(i)));
                }
                if (!sustainDuration.isZero()) {
                    // Add sustain only takes positive durations. Skip this since we already
                    // did a transition to the desired values (even when duration is zero).
                    waveform.addSustain(sustainDuration);
                }

                if ((i > 0) && (i == repeat)) {
                    // Add segment that is not repeated to the composition and reset builder.
                    composition.addEffect(waveform.build());

                    if (hasFrequencies) {
                        waveform = VibrationEffect.startWaveform(targetAmplitude(amplitudes.get(i)),
                                targetFrequency(frequencies.get(i)));
                    } else {
                        waveform = VibrationEffect.startWaveform(
                                targetAmplitude(amplitudes.get(i)));
                    }
                }
            }
            if (repeat < 0) {
                composition.addEffect(waveform.build());
            } else {
                // The waveform was already split at the repeat index, just repeat what remains.
                composition.repeatEffectIndefinitely(waveform.build());
            }
        }

        private void addPrebakedToComposition(VibrationEffect.Composition composition) {
            boolean shouldFallback = false;
            int delay = 0;

            getNextArgRequired(); // consume "prebaked"
            String nextOption;
            while ((nextOption = getNextOption()) != null) {
                if ("-b".equals(nextOption)) {
                    shouldFallback = true;
                } else if ("-w".equals(nextOption)) {
                    delay = Integer.parseInt(getNextArgRequired());
                }
            }

            int effectId = Integer.parseInt(getNextArgRequired());
            composition.addOffDuration(Duration.ofMillis(delay));
            composition.addEffect(VibrationEffect.get(effectId, shouldFallback));
        }

        private void addPrimitivesToComposition(VibrationEffect.Composition composition) {
            getNextArgRequired(); // consume "primitives"
            String nextArg;
            while ((nextArg = peekNextArg()) != null) {
                int delay = 0;
                if ("-w".equals(nextArg)) {
                    getNextArgRequired(); // consume "-w"
                    delay = Integer.parseInt(getNextArgRequired());
                    nextArg = peekNextArg();
                }
                try {
                    composition.addPrimitive(Integer.parseInt(nextArg), /* scale= */ 1, delay);
                    getNextArgRequired(); // consume the primitive id
                } catch (NumberFormatException | NullPointerException e) {
                    // nextArg is not describing a primitive, leave it to be consumed by outer loops
                    break;
                }
            }
        }

        private VibrationAttributes createVibrationAttributes(CommonOptions commonOptions) {
            final int flags =
                    commonOptions.force ? VibrationAttributes.FLAG_BYPASS_INTERRUPTION_POLICY : 0;
            return new VibrationAttributes.Builder()
                    .setFlags(flags)
                    // Used to apply Settings.System.HAPTIC_FEEDBACK_INTENSITY to scale effects.
                    .setUsage(VibrationAttributes.USAGE_TOUCH)
                    .build();
        }

        @Override
        public void onHelp() {
            try (PrintWriter pw = getOutPrintWriter();) {
                pw.println("Vibrator Manager commands:");
                pw.println("  help");
                pw.println("    Prints this help text.");
                pw.println("");
                pw.println("  list");
                pw.println("    Prints the id of device vibrators. This does not include any ");
                pw.println("    connected input device.");
                pw.println("  synced [options] <effect>...");
                pw.println("    Vibrates effect on all vibrators in sync.");
                pw.println("  combined [options] (-v <vibrator-id> <effect>...)...");
                pw.println("    Vibrates different effects on each vibrator in sync.");
                pw.println("  sequential [options] (-v <vibrator-id> <effect>...)...");
                pw.println("    Vibrates different effects on each vibrator in sequence.");
                pw.println("  cancel");
                pw.println("    Cancels any active vibration");
                pw.println("");
                pw.println("Effect commands:");
                pw.println("  oneshot [-w delay] [-a] <duration> [<amplitude>]");
                pw.println("    Vibrates for duration milliseconds; ignored when device is on ");
                pw.println("    DND (Do Not Disturb) mode; touch feedback strength user setting ");
                pw.println("    will be used to scale amplitude.");
                pw.println("    If -w is provided, the effect will be played after the specified");
                pw.println("    wait time in milliseconds.");
                pw.println("    If -a is provided, the command accepts a second argument for ");
                pw.println("    amplitude, in a scale of 1-255.");
                pw.print("  waveform [-w delay] [-r index] [-a] [-f] [-c] ");
                pw.println("(<duration> [<amplitude>] [<frequency>])...");
                pw.println("    Vibrates for durations and amplitudes in list; ignored when ");
                pw.println("    device is on DND (Do Not Disturb) mode; touch feedback strength ");
                pw.println("    user setting will be used to scale amplitude.");
                pw.println("    If -w is provided, the effect will be played after the specified");
                pw.println("    wait time in milliseconds.");
                pw.println("    If -r is provided, the waveform loops back to the specified");
                pw.println("    index (e.g. 0 loops from the beginning)");
                pw.println("    If -a is provided, the command expects amplitude to follow each");
                pw.println("    duration; otherwise, it accepts durations only and alternates");
                pw.println("    off/on");
                pw.println("    If -f is provided, the command expects frequency to follow each");
                pw.println("    amplitude or duration; otherwise, it uses resonant frequency");
                pw.println("    If -c is provided, the waveform is continuous and will ramp");
                pw.println("    between values; otherwise each entry is a fixed step.");
                pw.println("    Duration is in milliseconds; amplitude is a scale of 1-255;");
                pw.println("    frequency is an absolute value in hertz;");
                pw.println("  prebaked [-w delay] [-b] <effect-id>");
                pw.println("    Vibrates with prebaked effect; ignored when device is on DND ");
                pw.println("    (Do Not Disturb) mode; touch feedback strength user setting ");
                pw.println("    will be used to scale amplitude.");
                pw.println("    If -w is provided, the effect will be played after the specified");
                pw.println("    wait time in milliseconds.");
                pw.println("    If -b is provided, the prebaked fallback effect will be played if");
                pw.println("    the device doesn't support the given effect-id.");
                pw.println("  primitives ([-w delay] <primitive-id>)...");
                pw.println("    Vibrates with a composed effect; ignored when device is on DND ");
                pw.println("    (Do Not Disturb) mode; touch feedback strength user setting ");
                pw.println("    will be used to scale primitive intensities.");
                pw.println("    If -w is provided, the next primitive will be played after the ");
                pw.println("    specified wait time in milliseconds.");
                pw.println("");
                pw.println("Common Options:");
                pw.println("  -f");
                pw.println("    Force. Ignore Do Not Disturb setting.");
                pw.println("  -B");
                pw.println("    Run in the background; without this option the shell cmd will");
                pw.println("    block until the vibration has completed.");
                pw.println("  -d <description>");
                pw.println("    Add description to the vibration.");
                pw.println("");
            }
        }
    }
}
