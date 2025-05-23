/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.annotation.Nullable;
import android.hardware.vibrator.IVibrator;
import android.os.Binder;
import android.os.IVibratorStateListener;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.VibratorInfo;
import android.os.vibrator.PrebakedSegment;
import android.os.vibrator.PrimitiveSegment;
import android.os.vibrator.RampSegment;
import android.os.VibrationEffect;
import java.util.HashMap;
import java.util.Map;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import libcore.util.NativeAllocationRegistry;
import com.android.server.vibrator.RichTapVibratorService;

/** Controls a single vibrator. */
final class VibratorController {
    private static final String TAG = "VibratorController";

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private final NativeWrapper mNativeWrapper;
    private final static boolean RICHTAP_TAKEOVER_CTL = true;
    private static final int DEFAULT_PREBAKED_DURATION = 30;
    private RichTapVibratorService mRichTapService;

    // Vibrator state listeners that support concurrent updates and broadcasts, but should lock
    // while broadcasting to guarantee delivery order.
    private final RemoteCallbackList<IVibratorStateListener> mVibratorStateListeners =
            new RemoteCallbackList<>();

    // Vibrator state variables that are updated from synchronized blocks but can be read anytime
    // for a snippet of the current known vibrator state/info.
    private volatile VibratorInfo mVibratorInfo;
    private volatile boolean mVibratorInfoLoadSuccessful;
    private volatile boolean mIsVibrating;
    private volatile boolean mIsUnderExternalControl;
    private volatile float mCurrentAmplitude;

    /** Listener for vibration completion callbacks from native. */
    public interface OnVibrationCompleteListener {

        /** Callback triggered when vibration is complete. */
        void onComplete(int vibratorId, long vibrationId);
    }

    VibratorController(int vibratorId, OnVibrationCompleteListener listener) {
        this(vibratorId, listener, new NativeWrapper());
        if(RICHTAP_TAKEOVER_CTL){
            mRichTapService = new RichTapVibratorService(true, null);
        }
    }

    @VisibleForTesting
    VibratorController(int vibratorId, OnVibrationCompleteListener listener,
            NativeWrapper nativeWrapper) {
        mNativeWrapper = nativeWrapper;
        mNativeWrapper.init(vibratorId, listener);
        VibratorInfo.Builder vibratorInfoBuilder = new VibratorInfo.Builder(vibratorId);
        mVibratorInfoLoadSuccessful = mNativeWrapper.getInfo(vibratorInfoBuilder);
        mVibratorInfo = vibratorInfoBuilder.build();

        if (!mVibratorInfoLoadSuccessful) {
            Slog.e(TAG,
                    "Vibrator controller initialization failed to load some HAL info for vibrator "
                            + vibratorId);
        }
    }

    /** Register state listener for this vibrator. */
    public boolean registerVibratorStateListener(IVibratorStateListener listener) {
        final long token = Binder.clearCallingIdentity();
        try {
            // Register the listener and send the first state atomically, to avoid potentially
            // out of order broadcasts in between.
            synchronized (mLock) {
                if (!mVibratorStateListeners.register(listener)) {
                    return false;
                }
                // Notify its callback after new client registered.
                notifyStateListener(listener, mIsVibrating);
            }
            return true;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /** Remove registered state listener for this vibrator. */
    public boolean unregisterVibratorStateListener(IVibratorStateListener listener) {
        final long token = Binder.clearCallingIdentity();
        try {
            return mVibratorStateListeners.unregister(listener);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /** Reruns the query to the vibrator to load the {@link VibratorInfo}, if not yet successful. */
    public void reloadVibratorInfoIfNeeded() {
        // Early check outside lock, for quick return.
        if (mVibratorInfoLoadSuccessful) {
            return;
        }
        synchronized (mLock) {
            if (mVibratorInfoLoadSuccessful) {
                return;
            }
            int vibratorId = mVibratorInfo.getId();
            VibratorInfo.Builder vibratorInfoBuilder = new VibratorInfo.Builder(vibratorId);
            mVibratorInfoLoadSuccessful = mNativeWrapper.getInfo(vibratorInfoBuilder);
            mVibratorInfo = vibratorInfoBuilder.build();
            if (!mVibratorInfoLoadSuccessful) {
                Slog.e(TAG, "Failed retry of HAL getInfo for vibrator " + vibratorId);
            }
        }
    }

    /** Checks if the {@link VibratorInfo} was loaded from the vibrator hardware successfully. */
    boolean isVibratorInfoLoadSuccessful() {
        return mVibratorInfoLoadSuccessful;
    }

    /** Return the {@link VibratorInfo} representing the vibrator controlled by this instance. */
    public VibratorInfo getVibratorInfo() {
        return mVibratorInfo;
    }

    /**
     * Return {@code true} is this vibrator is currently vibrating, false otherwise.
     *
     * <p>This state is controlled by calls to {@link #on} and {@link #off} methods, and is
     * automatically notified to any registered {@link IVibratorStateListener} on change.
     */
    public boolean isVibrating() {
        return mIsVibrating;
    }

    /**
     * Returns the current amplitude the device is vibrating.
     *
     * <p>This value is set to 1 by the method {@link #on(long, long)}, and can be updated via
     * {@link #setAmplitude(float)} if called while the device is vibrating.
     *
     * <p>If the device is vibrating via any other {@link #on} method then the current amplitude is
     * unknown and this will return -1.
     *
     * <p>If {@link #isVibrating()} is false then this will be zero.
     */
    public float getCurrentAmplitude() {
        return mCurrentAmplitude;
    }

    /** Return {@code true} if this vibrator is under external control, false otherwise. */
    public boolean isUnderExternalControl() {
        return mIsUnderExternalControl;
    }

    /**
     * Check against this vibrator capabilities.
     *
     * @param capability one of IVibrator.CAP_*
     * @return true if this vibrator has this capability, false otherwise
     */
    public boolean hasCapability(long capability) {
        return mVibratorInfo.hasCapability(capability);
    }

    /** Return {@code true} if the underlying vibrator is currently available, false otherwise. */
    public boolean isAvailable() {
        synchronized (mLock) {
            return mNativeWrapper.isAvailable();
        }
    }

    /**
     * Set the vibrator control to be external or not, based on given flag.
     *
     * <p>This will affect the state of {@link #isUnderExternalControl()}.
     */
    public void setExternalControl(boolean externalControl) {
        if (!mVibratorInfo.hasCapability(IVibrator.CAP_EXTERNAL_CONTROL)) {
            return;
        }
        synchronized (mLock) {
            mIsUnderExternalControl = externalControl;
            mNativeWrapper.setExternalControl(externalControl);
        }
    }

    /**
     * Update the predefined vibration effect saved with given id. This will remove the saved effect
     * if given {@code effect} is {@code null}.
     */
    public void updateAlwaysOn(int id, @Nullable PrebakedSegment prebaked) {
        if (!mVibratorInfo.hasCapability(IVibrator.CAP_ALWAYS_ON_CONTROL)) {
            return;
        }
        synchronized (mLock) {
            if (prebaked == null) {
                mNativeWrapper.alwaysOnDisable(id);
            } else {
                mNativeWrapper.alwaysOnEnable(id, prebaked.getEffectId(),
                        prebaked.getEffectStrength());
            }
        }
    }

    /** Set the vibration amplitude. This will NOT affect the state of {@link #isVibrating()}. */
    public void setAmplitude(float amplitude) {
        synchronized (mLock) {
            if (RICHTAP_TAKEOVER_CTL) {
                int strength = (int)(amplitude * VibrationEffect.MAX_AMPLITUDE);
                mRichTapService.richTapVibratorSetAmplitude(strength);
            } else {
                if (mVibratorInfo.hasCapability(IVibrator.CAP_AMPLITUDE_CONTROL)) {
                    mNativeWrapper.setAmplitude(amplitude);
                }
            }
            if (mIsVibrating) {
                mCurrentAmplitude = amplitude;
            }
        }
    }

    /**
     * Turn on the vibrator for {@code milliseconds} time, using {@code vibrationId} or completion
     * callback to {@link OnVibrationCompleteListener}.
     *
     * <p>This will affect the state of {@link #isVibrating()}.
     *
     * @return The positive duration of the vibration started, if successful, zero if the vibrator
     * do not support the input or a negative number if the operation failed.
     */
    public long on(long milliseconds, long vibrationId) {
        long duration = 0;
        synchronized (mLock) {
            if (RICHTAP_TAKEOVER_CTL) {
                duration = milliseconds;
                mRichTapService.richTapVibratorOn(duration);
            } else {
                duration = mNativeWrapper.on(milliseconds, vibrationId);
                if (duration > 0) {
                    mCurrentAmplitude = -1;
                    notifyListenerOnVibrating(true);
                }
            }
        }
        return duration;
    }

    /**
     * Plays predefined vibration effect, using {@code vibrationId} or completion callback to
     * {@link OnVibrationCompleteListener}.
     *
     * <p>This will affect the state of {@link #isVibrating()}.
     *
     * @return The positive duration of the vibration started, if successful, zero if the vibrator
     * do not support the input or a negative number if the operation failed.
     */
    public long on(PrebakedSegment prebaked, long vibrationId) {
        long duration = 0;
        synchronized (mLock) {
            if (RICHTAP_TAKEOVER_CTL) {
                duration = DEFAULT_PREBAKED_DURATION;
                mRichTapService.richTapVibratorPerform(prebaked.getEffectId(), (byte)prebaked.getEffectStrength());
            } else {
                duration = mNativeWrapper.perform(prebaked.getEffectId(),
                        prebaked.getEffectStrength(), vibrationId);
                if (duration > 0) {
                    mCurrentAmplitude = -1;
                    notifyListenerOnVibrating(true);
                }
            }
        }
        return duration;
    }

    /**
     * Plays a composition of vibration primitives, using {@code vibrationId} or completion callback
     * to {@link OnVibrationCompleteListener}.
     *
     * <p>This will affect the state of {@link #isVibrating()}.
     *
     * @return The positive duration of the vibration started, if successful, zero if the vibrator
     * do not support the input or a negative number if the operation failed.
     */
    public long on(PrimitiveSegment[] primitives, long vibrationId) {
        if (!mVibratorInfo.hasCapability(IVibrator.CAP_COMPOSE_EFFECTS)) {
            return 0;
        }
        synchronized (mLock) {
            long duration = mNativeWrapper.compose(primitives, vibrationId);
            if (duration > 0) {
                mCurrentAmplitude = -1;
                notifyListenerOnVibrating(true);
            }
            return duration;
        }
    }

    /**
     * Plays a composition of pwle primitives, using {@code vibrationId} or completion callback
     * to {@link OnVibrationCompleteListener}.
     *
     * <p>This will affect the state of {@link #isVibrating()}.
     *
     * @return The duration of the effect playing, or 0 if unsupported.
     */
    public long on(RampSegment[] primitives, long vibrationId) {
        if (!mVibratorInfo.hasCapability(IVibrator.CAP_COMPOSE_PWLE_EFFECTS)) {
            return 0;
        }
        synchronized (mLock) {
            int braking = mVibratorInfo.getDefaultBraking();
            long duration = mNativeWrapper.composePwle(primitives, braking, vibrationId);
            if (duration > 0) {
                mCurrentAmplitude = -1;
                notifyListenerOnVibrating(true);
            }
            return duration;
        }
    }

    /** Turns off the vibrator. This will affect the state of {@link #isVibrating()}. */
    public void off() {
        synchronized (mLock) {
            mNativeWrapper.off();
            mCurrentAmplitude = 0;
            notifyListenerOnVibrating(false);
        }
    }

    /**
     * Resets the vibrator hardware to a default state.
     * This turns the vibrator off, which will affect the state of {@link #isVibrating()}.
     */
    public void reset() {
        setExternalControl(false);
        off();
    }

    @Override
    public String toString() {
        return "VibratorController{"
                + "mVibratorInfo=" + mVibratorInfo
                + ", mVibratorInfoLoadSuccessful=" + mVibratorInfoLoadSuccessful
                + ", mIsVibrating=" + mIsVibrating
                + ", mCurrentAmplitude=" + mCurrentAmplitude
                + ", mIsUnderExternalControl=" + mIsUnderExternalControl
                + ", mVibratorStateListeners count="
                + mVibratorStateListeners.getRegisteredCallbackCount()
                + '}';
    }

    @GuardedBy("mLock")
    private void notifyListenerOnVibrating(boolean isVibrating) {
        if (mIsVibrating != isVibrating) {
            mIsVibrating = isVibrating;
            // The broadcast method is safe w.r.t. register/unregister listener methods, but lock
            // is required here to guarantee delivery order.
            mVibratorStateListeners.broadcast(
                    listener -> notifyStateListener(listener, isVibrating));
        }
    }

    private void notifyStateListener(IVibratorStateListener listener, boolean isVibrating) {
        try {
            listener.onVibrating(isVibrating);
        } catch (RemoteException | RuntimeException e) {
            Slog.e(TAG, "Vibrator state listener failed to call", e);
        }
    }

    /** Wrapper around the static-native methods of {@link VibratorController} for tests. */
    @VisibleForTesting
    public static class NativeWrapper {
        /**
         * Initializes the native part of this controller, creating a global reference to given
         * {@link OnVibrationCompleteListener} and returns a newly allocated native pointer. This
         * wrapper is responsible for deleting this pointer by calling the method pointed
         * by {@link #getNativeFinalizer()}.
         *
         * <p><b>Note:</b> Make sure the given implementation of {@link OnVibrationCompleteListener}
         * do not hold any strong reference to the instance responsible for deleting the returned
         * pointer, to avoid creating a cyclic GC root reference.
         */
        private static native long nativeInit(int vibratorId, OnVibrationCompleteListener listener);

        /**
         * Returns pointer to native function responsible for cleaning up the native pointer
         * allocated and returned by {@link #nativeInit(int, OnVibrationCompleteListener)}.
         */
        private static native long getNativeFinalizer();

        private static native boolean isAvailable(long nativePtr);

        private static native long on(long nativePtr, long milliseconds, long vibrationId);

        private static native void off(long nativePtr);

        private static native void setAmplitude(long nativePtr, float amplitude);

        private static native long performEffect(long nativePtr, long effect, long strength,
                long vibrationId);

        private static native long performComposedEffect(long nativePtr, PrimitiveSegment[] effect,
                long vibrationId);

        private static native long performPwleEffect(long nativePtr, RampSegment[] effect,
                int braking, long vibrationId);

        private static native void setExternalControl(long nativePtr, boolean enabled);

        private static native void alwaysOnEnable(long nativePtr, long id, long effect,
                long strength);

        private static native void alwaysOnDisable(long nativePtr, long id);

        private static native boolean getInfo(long nativePtr, VibratorInfo.Builder infoBuilder);

        private long mNativePtr = 0;

        /** Initializes native controller and allocation registry to destroy native instances. */
        public void init(int vibratorId, OnVibrationCompleteListener listener) {
            mNativePtr = nativeInit(vibratorId, listener);
            long finalizerPtr = getNativeFinalizer();

            if (finalizerPtr != 0) {
                NativeAllocationRegistry registry =
                        NativeAllocationRegistry.createMalloced(
                                VibratorController.class.getClassLoader(), finalizerPtr);
                registry.registerNativeAllocation(this, mNativePtr);
            }
        }

        /** Check if the vibrator is currently available. */
        public boolean isAvailable() {
            return isAvailable(mNativePtr);
        }

        /** Turns vibrator on for given time. */
        public long on(long milliseconds, long vibrationId) {
            return on(mNativePtr, milliseconds, vibrationId);
        }

        /** Turns vibrator off. */
        public void off() {
            off(mNativePtr);
        }

        /** Sets the amplitude for the vibrator to run. */
        public void setAmplitude(float amplitude) {
            setAmplitude(mNativePtr, amplitude);
        }

        /** Turns vibrator on to perform one of the supported effects. */
        public long perform(long effect, long strength, long vibrationId) {
            return performEffect(mNativePtr, effect, strength, vibrationId);
        }

        /** Turns vibrator on to perform effect composed of give primitives effect. */
        public long compose(PrimitiveSegment[] primitives, long vibrationId) {
            return performComposedEffect(mNativePtr, primitives, vibrationId);
        }

        /** Turns vibrator on to perform PWLE effect composed of given primitives. */
        public long composePwle(RampSegment[] primitives, int braking, long vibrationId) {
            return performPwleEffect(mNativePtr, primitives, braking, vibrationId);
        }

        /** Enabled the device vibrator to be controlled by another service. */
        public void setExternalControl(boolean enabled) {
            setExternalControl(mNativePtr, enabled);
        }

        /** Enable always-on vibration with given id and effect. */
        public void alwaysOnEnable(long id, long effect, long strength) {
            alwaysOnEnable(mNativePtr, id, effect, strength);
        }

        /** Disable always-on vibration for given id. */
        public void alwaysOnDisable(long id) {
            alwaysOnDisable(mNativePtr, id);
        }

        /**
         * Loads device vibrator metadata and returns true if all metadata was loaded successfully.
         */
        public boolean getInfo(VibratorInfo.Builder infoBuilder) {
            return getInfo(mNativePtr, infoBuilder);
        }
    }
}
