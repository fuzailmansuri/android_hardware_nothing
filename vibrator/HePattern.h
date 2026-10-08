/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include <array>
#include <cstdint>
#include <vector>

namespace aidl {
namespace android {
namespace hardware {
namespace vibrator {

/**
 * Builds a RichTap HE 1.0 pattern for aac_vibra_looper_post().
 *
 * The array is a version word followed by 17 words per event:
 *   type, relativeTime, intensity, frequency, duration,
 *   then four curve points of (time, intensity, frequencyOffset).
 * Transients only use the first four words. Intensities are 0-100; a curve point's
 * intensity multiplies the event's, and its frequency is added to the event's.
 */
class HePattern {
  public:
    struct Point {
        int32_t timeMs;
        int32_t intensity;
        int32_t frequencyOffset;
    };

    /** A short click from the JND library; frequency picks how sharp it is. */
    void addTransient(int32_t timeMs, int32_t intensity, int32_t frequency) {
        mData.insert(mData.end(), {kTypeTransient, timeMs, intensity, frequency});
        mData.insert(mData.end(), kEventWords - 4, 0);
    }

    /** A sustained vibration following a four point envelope. */
    void addContinuous(int32_t timeMs, int32_t intensity, int32_t frequency, int32_t durationMs,
                       const std::array<Point, 4>& curve) {
        mData.insert(mData.end(), {kTypeContinuous, timeMs, intensity, frequency, durationMs});
        for (const auto& p : curve) {
            mData.insert(mData.end(), {p.timeMs, p.intensity, p.frequencyOffset});
        }
    }

    bool empty() const { return mData.size() <= 1; }

    const std::vector<int32_t>& data() const { return mData; }

  private:
    static constexpr int32_t kVersion = 1;
    static constexpr int32_t kTypeContinuous = 0x1000;
    static constexpr int32_t kTypeTransient = 0x1001;
    static constexpr size_t kEventWords = 17;

    std::vector<int32_t> mData{kVersion};
};

}  // namespace vibrator
}  // namespace hardware
}  // namespace android
}  // namespace aidl
