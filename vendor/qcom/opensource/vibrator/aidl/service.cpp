/*
 * Copyright (c) 2020, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *     * Neither the name of The Linux Foundation nor the names of its
 *       contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

#define LOG_TAG "vendor.qti.hardware.vibrator.service"

#include <android-base/logging.h>
#include <android/binder_manager.h>
#include <android/binder_process.h>

#include "Vibrator.h"
//add by AAC for RichTap support
#include <thread>
#include <android-base/properties.h>
#include "richtap/RichtapVibrator.h"
using aidl::vendor::aac::hardware::richtap::vibrator::RichtapVibrator;
using aidl::android::hardware::vibrator::Vibrator;

// when boot_completed ,we set this prop to 1
constexpr char kHapticCalibrateProp[] = "vendor.haptic.calibrate.done";
int main() {
    ABinderProcess_setThreadPoolMaxThreadCount(0);
    std::shared_ptr<Vibrator> vib = ndk::SharedRefBase::make<Vibrator>();
    ndk::SpAIBinder vibBinder = vib->asBinder(); // must have this ,i don't know why

    // add by AAC for RichTap support
    // making the extension service
    std::shared_ptr<RichtapVibrator> cvib = ndk::SharedRefBase::make<RichtapVibrator>();

    // need to attach the extension to the same binder we will be registering
    CHECK(STATUS_OK == AIBinder_setExtension(vibBinder.get(), cvib->asBinder().get()));

    const std::string instance = std::string() + Vibrator::descriptor + "/default";
    binder_status_t status = AServiceManager_addService(vib->asBinder().get(), instance.c_str());
    CHECK(status == STATUS_OK);

    std::thread initThread([&]() {
        using std::literals::chrono_literals::operator""s;
        ::android::base::WaitForProperty(kHapticCalibrateProp, "1", 500s);
        cvib->init(nullptr);
    });
    initThread.detach();

    ABinderProcess_joinThreadPool();
    return EXIT_FAILURE;  // should not reach
}
