/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#include "Vibrator.h"
#include "aac_vibra_function.h"
#include "ics_haptic_function.h"

#include <aidl/android/hardware/vibrator/BnVibratorCallback.h>
#include <gtest/gtest.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <mutex>
#include <utility>
#include <vector>

namespace {
std::mutex gMutex;
std::condition_variable gPlayed;
std::vector<std::pair<uint32_t, int32_t>> gEffects;
int32_t gDurationMs = 18;
std::atomic<uint8_t> gAmplitude{0};
}  // namespace

extern "C" {
int aac_vibra_init(uint32_t* deviceType) { *deviceType = 0; return 0; }
int aac_vibra_setAmplitude(uint8_t amplitude) { gAmplitude = amplitude; return 0; }
int aac_vibra_dynamic_scale(uint8_t) { return 0; }
int aac_vibra_setting_f0(int, float) { return 0; }
int sfdc_initialize(void (*)(float)) { return -1; }
int sfdc_calibrate(const int32_t*, int) { return -1; }
float sfdc_get_manufactory_f0() { return 0; }
float sfdc_get_continuous_f0() { return 0; }
float sfdc_get_transient_fc() { return 0; }
}

void aac_vibra_looper_start() {}
bool aac_vibra_looper_stopPerformHe() { return true; }
int32_t aac_vibra_looper_on(uint32_t timeoutMs) { return timeoutMs; }
int32_t aac_vibra_looper_prebaked_effect(uint32_t effect, int32_t strength) {
    std::lock_guard lock(gMutex);
    gEffects.emplace_back(effect, strength);
    gPlayed.notify_all();
    return gDurationMs;
}

namespace aidl::android::hardware::vibrator {
namespace {
class RecordingCallback : public BnVibratorCallback {
  public:
    ndk::ScopedAStatus onComplete() override {
        std::lock_guard lock(mMutex);
        ++mCalls;
        mCompleted.notify_all();
        return ndk::ScopedAStatus::ok();
    }

    bool wait() {
        std::unique_lock lock(mMutex);
        return mCompleted.wait_for(lock, std::chrono::seconds(1), [this] { return mCalls == 1; });
    }

  private:
    std::mutex mMutex;
    std::condition_variable mCompleted;
    int mCalls = 0;
};

class VibratorTest : public testing::Test {
  protected:
    void SetUp() override {
        std::lock_guard lock(gMutex);
        gEffects.clear();
        gDurationMs = 18;
        gAmplitude = 0;
        mVibrator = ndk::SharedRefBase::make<Vibrator>();
    }

    std::shared_ptr<Vibrator> mVibrator;
};

TEST_F(VibratorTest, PerformDispatchesAllStrengthsAndKeepsFactoryGainAndDuration) {
    for (auto [strength, expected] : {
            std::pair{EffectStrength::LIGHT, 50},
            std::pair{EffectStrength::MEDIUM, 80},
            std::pair{EffectStrength::STRONG, 100}}) {
        int32_t duration = -1;
        ASSERT_TRUE(mVibrator->perform(Effect::CLICK, strength, nullptr, &duration).isOk());
        EXPECT_EQ(duration, 18);
        EXPECT_EQ(gAmplitude.load(), 255);
        std::lock_guard lock(gMutex);
        ASSERT_FALSE(gEffects.empty());
        EXPECT_EQ(gEffects.back().first, 12296u);
        EXPECT_EQ(gEffects.back().second, expected);
    }
}

TEST_F(VibratorTest, InvalidStrengthAndEffectDoNotStartHardwareOrLeaveDuration) {
    for (int32_t invalid : {-1, 3}) {
        int32_t duration = -1;
        auto status = mVibrator->perform(Effect::CLICK, static_cast<EffectStrength>(invalid), nullptr, &duration);
        EXPECT_EQ(status.getExceptionCode(), EX_ILLEGAL_ARGUMENT);
        EXPECT_EQ(duration, 0);
    }
    int32_t duration = -1;
    auto status = mVibrator->perform(static_cast<Effect>(-1), EffectStrength::MEDIUM, nullptr, &duration);
    EXPECT_EQ(status.getExceptionCode(), EX_UNSUPPORTED_OPERATION);
    EXPECT_EQ(duration, 0);
    EXPECT_EQ(gAmplitude.load(), 0);
    EXPECT_TRUE(gEffects.empty());
}

TEST_F(VibratorTest, BackendErrorDoesNotLeaveDuration) {
    gDurationMs = -1;
    int32_t duration = 123;
    auto status = mVibrator->perform(Effect::CLICK, EffectStrength::MEDIUM, nullptr, &duration);
    EXPECT_EQ(status.getExceptionCode(), EX_SERVICE_SPECIFIC);
    EXPECT_EQ(duration, 0);
}

TEST_F(VibratorTest, SuccessfulEffectStillCompletesCallback) {
    auto callback = ndk::SharedRefBase::make<RecordingCallback>();
    int32_t duration = 0;
    ASSERT_TRUE(mVibrator->perform(Effect::TEXTURE_TICK, EffectStrength::LIGHT, callback, &duration).isOk());
    EXPECT_EQ(duration, 18);
    EXPECT_TRUE(callback->wait());
    std::lock_guard lock(gMutex);
    ASSERT_EQ(gEffects.size(), 1u);
    EXPECT_EQ(gEffects.front(), (std::pair<uint32_t, int32_t>{12297, 50}));
}

TEST_F(VibratorTest, DoubleClickUsesSelectedStrengthForBothPulses) {
    int32_t duration = 0;
    ASSERT_TRUE(mVibrator->perform(Effect::DOUBLE_CLICK, EffectStrength::MEDIUM, nullptr, &duration).isOk());
    EXPECT_EQ(duration, 88);
    std::unique_lock lock(gMutex);
    ASSERT_TRUE(gPlayed.wait_for(lock, std::chrono::seconds(1), [] { return gEffects.size() == 2; }));
    EXPECT_EQ(gEffects[0], (std::pair<uint32_t, int32_t>{12296, 80}));
    EXPECT_EQ(gEffects[1], gEffects[0]);
}
}  // namespace
}  // namespace aidl::android::hardware::vibrator
