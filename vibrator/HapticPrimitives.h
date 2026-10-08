/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include <aidl/android/hardware/vibrator/CompositePrimitive.h>

#include "HePattern.h"

namespace aidl {
namespace android {
namespace hardware {
namespace vibrator {

/** Event intensity (0-100) that drives the actuator at `amplitude` (0-1) of full scale. */
int32_t heIntensity(float amplitude);

/**
 * Adds `primitive` at `timeMs`, scaled by `scale` (0-1).
 * Returns its duration in ms, or -1 if it is not supported.
 */
int32_t addPrimitive(HePattern& pattern, CompositePrimitive primitive, int32_t timeMs, float scale);

/** Adds two clicks; returns the duration in ms. */
int32_t addDoubleClick(HePattern& pattern, int32_t timeMs, float scale);

}  // namespace vibrator
}  // namespace hardware
}  // namespace android
}  // namespace aidl
