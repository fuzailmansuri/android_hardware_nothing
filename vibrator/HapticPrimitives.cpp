/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#include "HapticPrimitives.h"

#include <algorithm>
#include <cmath>

namespace aidl {
namespace android {
namespace hardware {
namespace vibrator {

namespace {

// RichTap HE frequencies are 0-100, not Hz. Measured on the rt6010 LRA with the
// accelerometer, output peaks at 55-60, which is the 170 Hz the driver reports as f0.
constexpr int32_t kFreqResonance = 57;
constexpr int32_t kFreqLow = 48;

// A transient's frequency selects the JND click: 45-57 is the crisp 18 ms click, 70-100 the
// light 12 ms tick and 0-30 the round 21 ms tick.
constexpr int32_t kTransientClick = 57;
constexpr int32_t kTransientLightTick = 100;
constexpr int32_t kTransientLowTick = 10;
constexpr int32_t kTransientClickMs = 18;
constexpr int32_t kTransientLightTickMs = 12;
constexpr int32_t kTransientLowTickMs = 21;

constexpr int32_t kDoubleClickGapMs = 90;

using Curve = std::array<HePattern::Point, 4>;

}  // namespace

int32_t heIntensity(float amplitude) {
    // The engine drives intensity I at I * 1.14^((I - 100) / 10) of full scale. Invert
    // that so the framework's scale maps linearly onto the actuator.
    amplitude = std::clamp(amplitude, 0.0f, 1.0f);
    if (amplitude <= 0.0f) return 0;
    float lo = 0.0f, hi = 100.0f;
    for (int i = 0; i < 20; i++) {
        float mid = (lo + hi) / 2;
        float drive = mid * std::pow(1.14f, (mid - 100.0f) / 10.0f) / 100.0f;
        (drive < amplitude ? lo : hi) = mid;
    }
    return std::max(1, static_cast<int32_t>(std::lround(hi)));
}

int32_t addPrimitive(HePattern& pattern, CompositePrimitive primitive, int32_t timeMs,
                     float scale) {
    switch (primitive) {
        case CompositePrimitive::NOOP:
            return 0;
        case CompositePrimitive::CLICK:
            pattern.addTransient(timeMs, heIntensity(scale), kTransientClick);
            return kTransientClickMs;
        case CompositePrimitive::LIGHT_TICK:
            pattern.addTransient(timeMs, heIntensity(scale * 0.5f), kTransientLightTick);
            return kTransientLightTickMs;
        case CompositePrimitive::LOW_TICK:
            pattern.addTransient(timeMs, heIntensity(scale * 0.8f), kTransientLowTick);
            return kTransientLowTickMs;
        case CompositePrimitive::THUD:
            // A hard hit below resonance that dies away.
            pattern.addContinuous(timeMs, heIntensity(scale), kFreqLow, 100,
                                  Curve{{{0, 100, 0}, {15, 100, 0}, {55, 50, 0}, {100, 0, 0}}});
            return 100;
        case CompositePrimitive::SPIN:
            // Swells through resonance with rising pitch.
            pattern.addContinuous(timeMs, heIntensity(scale), kFreqResonance, 150,
                                  Curve{{{0, 30, -20}, {50, 100, -5}, {110, 100, 10}, {150, 0, 20}}});
            return 150;
        case CompositePrimitive::QUICK_RISE:
            pattern.addContinuous(timeMs, heIntensity(scale), kFreqResonance, 80,
                                  Curve{{{0, 10, 0}, {35, 45, 0}, {65, 85, 0}, {80, 100, 0}}});
            return 80;
        case CompositePrimitive::SLOW_RISE:
            pattern.addContinuous(timeMs, heIntensity(scale), kFreqResonance, 300,
                                  Curve{{{0, 5, 0}, {120, 30, 0}, {240, 75, 0}, {300, 100, 0}}});
            return 300;
        case CompositePrimitive::QUICK_FALL:
            pattern.addContinuous(timeMs, heIntensity(scale), kFreqResonance, 80,
                                  Curve{{{0, 100, 0}, {12, 75, 0}, {40, 30, 0}, {80, 0, 0}}});
            return 80;
        default:
            return -1;
    }
}

int32_t addDoubleClick(HePattern& pattern, int32_t timeMs, float scale) {
    addPrimitive(pattern, CompositePrimitive::CLICK, timeMs, scale);
    addPrimitive(pattern, CompositePrimitive::CLICK, timeMs + kDoubleClickGapMs, scale);
    return kDoubleClickGapMs + kTransientClickMs;
}

}  // namespace vibrator
}  // namespace hardware
}  // namespace android
}  // namespace aidl
