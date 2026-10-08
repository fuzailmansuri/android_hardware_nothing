/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#define LOG_TAG "vibrator-rt_ics"

#include "Vibrator.h"

#include <android-base/properties.h>
#include <log/log.h>

#include <algorithm>
#include <cmath>

#include "HapticPrimitives.h"
#include "aac_vibra_function.h"

namespace aidl {
namespace android {
namespace hardware {
namespace vibrator {

namespace {

// Prebaked JND effects, ranked by output measured with the accelerometer: 0x3007 is the
// strongest, then 0x3008 click, 0x3002 a rounder pop, 0x3009 tick and 0x300b the lightest.
constexpr uint32_t kJndHeavyClick = 0x3007;
constexpr uint32_t kJndClick = 0x3008;
constexpr uint32_t kJndPop = 0x3002;
constexpr uint32_t kJndTick = 0x3009;
constexpr uint32_t kJndTextureTick = 0x300b;

constexpr int32_t kComposeDelayMaxMs = 1000;
constexpr int32_t kComposeSizeMax = 16;

constexpr int32_t kAmplitudeMax = 0xff;

float strengthScale(EffectStrength strength) {
    switch (strength) {
        case EffectStrength::LIGHT:
            return 0.5f;
        case EffectStrength::MEDIUM:
            return 0.75f;
        default:
            return 1.0f;
    }
}

ndk::ScopedAStatus unsupported() {
    return ndk::ScopedAStatus(AStatus_fromExceptionCode(EX_UNSUPPORTED_OPERATION));
}

ndk::ScopedAStatus illegalArgument() {
    return ndk::ScopedAStatus(AStatus_fromExceptionCode(EX_ILLEGAL_ARGUMENT));
}

ndk::ScopedAStatus serviceError() {
    return ndk::ScopedAStatus(AStatus_fromExceptionCode(EX_SERVICE_SPECIFIC));
}

}  // namespace

// ---- CompletionNotifier ----------------------------------------------------

CompletionNotifier::CompletionNotifier() : mThread(&CompletionNotifier::loop, this) {}

CompletionNotifier::~CompletionNotifier() {
    {
        std::lock_guard<std::mutex> lock(mLock);
        mExit = true;
    }
    mCv.notify_one();
    mThread.join();
}

void CompletionNotifier::schedule(const std::shared_ptr<IVibratorCallback>& callback,
                                  int32_t durationMs) {
    std::shared_ptr<IVibratorCallback> previous;
    {
        std::lock_guard<std::mutex> lock(mLock);
        previous = std::move(mCallback);
        mCallback = callback;
        mDeadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(durationMs);
    }
    mCv.notify_one();
    if (previous) previous->onComplete();
}

void CompletionNotifier::completeNow() {
    schedule(nullptr, 0);
}

void CompletionNotifier::loop() {
    std::unique_lock<std::mutex> lock(mLock);
    while (!mExit) {
        if (!mCallback) {
            mCv.wait(lock);
            continue;
        }
        if (mCv.wait_until(lock, mDeadline) == std::cv_status::timeout && mCallback &&
            std::chrono::steady_clock::now() >= mDeadline) {
            auto callback = std::move(mCallback);
            lock.unlock();
            callback->onComplete();
            lock.lock();
        }
    }
}

// ---- Vibrator ----------------------------------------------------------------

Vibrator::Vibrator() {
    uint32_t deviceType = 0;

    std::string devicePath = ::android::base::GetProperty("vendor.vibrator.device", "");
    if (!devicePath.empty()) {
        setenv("RICHTAP_DEVICE_PATH", devicePath.c_str(), 1);
    } else {
        ALOGE("No vibrator device set!");
    }

    int32_t ret = aac_vibra_init(&deviceType);
    if (ret) {
        ALOGE("AAC init failed: %d", ret);
        return;
    }

    aac_vibra_looper_start();

    ALOGI("AAC init success: %u", deviceType);
}

ndk::ScopedAStatus Vibrator::play(const HePattern& pattern, int32_t durationMs,
                                  const std::shared_ptr<IVibratorCallback>& callback,
                                  int32_t* playedMs) {
    if (!pattern.empty()) {
        const auto& data = pattern.data();
        int32_t ret = aac_vibra_looper_post(data.data(), static_cast<int32_t>(data.size()), 0, 1,
                                            kAmplitudeMax, 0);
        if (ret < 0) {
            ALOGE("AAC pattern failed: %d", ret);
            return serviceError();
        }
        durationMs = std::max(durationMs, ret);
    }

    mNotifier.schedule(callback, durationMs);
    if (playedMs) *playedMs = durationMs;

    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::getCapabilities(int32_t* _aidl_return) {
    *_aidl_return = IVibrator::CAP_ON_CALLBACK | IVibrator::CAP_PERFORM_CALLBACK |
                    IVibrator::CAP_AMPLITUDE_CONTROL | IVibrator::CAP_COMPOSE_EFFECTS;

    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::off() {
    bool stopped = aac_vibra_looper_stopPerformHe();
    mNotifier.completeNow();

    if (!stopped) {
        ALOGE("AAC stop failed");
        return serviceError();
    }

    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::on(int32_t timeoutMs,
                                const std::shared_ptr<IVibratorCallback>& callback) {
    int32_t ret = aac_vibra_looper_on(timeoutMs);
    if (ret < 0) {
        ALOGE("AAC on failed: %d", ret);
        return serviceError();
    }

    mNotifier.schedule(callback, ret);

    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::perform(Effect effect, EffectStrength es,
                                     const std::shared_ptr<IVibratorCallback>& callback,
                                     int32_t* _aidl_return) {
    float scale = strengthScale(es);
    uint32_t jndId;

    switch (effect) {
        case Effect::HEAVY_CLICK:
            jndId = kJndHeavyClick;
            break;
        case Effect::CLICK:
            jndId = kJndClick;
            break;
        case Effect::POP:
            jndId = kJndPop;
            break;
        case Effect::TICK:
            jndId = kJndTick;
            break;
        case Effect::TEXTURE_TICK:
            jndId = kJndTextureTick;
            break;
        case Effect::DOUBLE_CLICK: {
            HePattern pattern;
            int32_t durationMs = addDoubleClick(pattern, 0, scale);
            return play(pattern, durationMs, callback, _aidl_return);
        }
        case Effect::THUD: {
            HePattern pattern;
            int32_t durationMs = addPrimitive(pattern, CompositePrimitive::THUD, 0, scale);
            return play(pattern, durationMs, callback, _aidl_return);
        }
        default:
            return unsupported();
    }

    // The prebaked JND clicks are tuned by the vendor; their strength argument scales the
    // output amplitude linearly.
    aac_vibra_setAmplitude(kAmplitudeMax);
    int32_t ret = aac_vibra_looper_prebaked_effect(jndId, std::lround(scale * 100));
    if (ret < 0) {
        ALOGE("AAC perform failed: %d", ret);
        return serviceError();
    }

    mNotifier.schedule(callback, ret);
    *_aidl_return = ret;

    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::getSupportedEffects(std::vector<Effect>* _aidl_return) {
    *_aidl_return = {Effect::CLICK,       Effect::DOUBLE_CLICK, Effect::TICK, Effect::THUD,
                     Effect::POP,         Effect::HEAVY_CLICK,  Effect::TEXTURE_TICK};

    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::setAmplitude(float amplitude) {
    if (amplitude <= 0.0f || amplitude > 1.0f) {
        return illegalArgument();
    }

    auto value = static_cast<uint8_t>(std::max(1L, std::lround(amplitude * 0xff)));
    int32_t ret = aac_vibra_setAmplitude(value);
    if (ret) {
        ALOGE("AAC set amplitude failed: %d", ret);
        return serviceError();
    }

    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::setExternalControl(bool enabled __unused) {
    return unsupported();
}

ndk::ScopedAStatus Vibrator::getCompositionDelayMax(int32_t* maxDelayMs) {
    *maxDelayMs = kComposeDelayMaxMs;

    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::getCompositionSizeMax(int32_t* maxSize) {
    *maxSize = kComposeSizeMax;

    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::getSupportedPrimitives(std::vector<CompositePrimitive>* supported) {
    *supported = {CompositePrimitive::NOOP,       CompositePrimitive::CLICK,
                  CompositePrimitive::THUD,       CompositePrimitive::SPIN,
                  CompositePrimitive::QUICK_RISE, CompositePrimitive::SLOW_RISE,
                  CompositePrimitive::QUICK_FALL, CompositePrimitive::LIGHT_TICK,
                  CompositePrimitive::LOW_TICK};

    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::getPrimitiveDuration(CompositePrimitive primitive,
                                                  int32_t* durationMs) {
    HePattern scratch;
    int32_t ms = addPrimitive(scratch, primitive, 0, 1.0f);
    if (ms < 0) {
        return unsupported();
    }

    *durationMs = ms;

    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::compose(const std::vector<CompositeEffect>& composite,
                                     const std::shared_ptr<IVibratorCallback>& callback) {
    if (composite.empty() || composite.size() > kComposeSizeMax) {
        return illegalArgument();
    }

    // The whole composition goes out as one timed pattern, so the engine keeps the gaps
    // exact instead of a thread sleeping between primitives.
    HePattern pattern;
    int32_t timeMs = 0;
    for (const auto& e : composite) {
        if (e.delayMs < 0 || e.delayMs > kComposeDelayMaxMs || e.scale < 0.0f || e.scale > 1.0f) {
            return illegalArgument();
        }
        timeMs += e.delayMs;
        int32_t ms = addPrimitive(pattern, e.primitive, timeMs, e.scale);
        if (ms < 0) {
            return unsupported();
        }
        timeMs += ms;
    }

    return play(pattern, timeMs, callback, nullptr);
}

ndk::ScopedAStatus Vibrator::getSupportedAlwaysOnEffects(
        std::vector<Effect>* _aidl_return __unused) {
    return unsupported();
}

ndk::ScopedAStatus Vibrator::alwaysOnEnable(int32_t id __unused, Effect effect __unused,
                                            EffectStrength strength __unused) {
    return unsupported();
}

ndk::ScopedAStatus Vibrator::alwaysOnDisable(int32_t id __unused) {
    return unsupported();
}

ndk::ScopedAStatus Vibrator::getResonantFrequency(float* resonantFreqHz __unused) {
    return unsupported();
}

ndk::ScopedAStatus Vibrator::getQFactor(float* qFactor __unused) {
    return unsupported();
}

ndk::ScopedAStatus Vibrator::getFrequencyResolution(float* freqResolutionHz __unused) {
    return unsupported();
}

ndk::ScopedAStatus Vibrator::getFrequencyMinimum(float* freqMinimumHz __unused) {
    return unsupported();
}

ndk::ScopedAStatus Vibrator::getBandwidthAmplitudeMap(std::vector<float>* _aidl_return __unused) {
    return unsupported();
}

ndk::ScopedAStatus Vibrator::getPwlePrimitiveDurationMax(int32_t* durationMs __unused) {
    return unsupported();
}

ndk::ScopedAStatus Vibrator::getPwleCompositionSizeMax(int32_t* maxSize __unused) {
    return unsupported();
}

ndk::ScopedAStatus Vibrator::getSupportedBraking(std::vector<Braking>* supported __unused) {
    return unsupported();
}

ndk::ScopedAStatus Vibrator::composePwle(const std::vector<PrimitivePwle>& composite __unused,
                                         const std::shared_ptr<IVibratorCallback>& callback
                                                 __unused) {
    return unsupported();
}

}  // namespace vibrator
}  // namespace hardware
}  // namespace android
}  // namespace aidl
