# SPDX-FileCopyrightText: 2026 Paranoid Android
# SPDX-License-Identifier: Apache-2.0

PRODUCT_SOONG_NAMESPACES += hardware/nothing/dirac

PRODUCT_PACKAGES += \
    ParanoidDiracEffectService \
    libdirac \
    libdiraceffect_NT

PRODUCT_COPY_FILES += \
    hardware/nothing/dirac/interface.json:$(TARGET_COPY_OUT_VENDOR)/etc/dirac/interface.json \
    hardware/nothing/dirac/proprietary/vendor/etc/dirac/dirac_resource.dar:$(TARGET_COPY_OUT_VENDOR)/etc/dirac/dirac_resource.dar

BOARD_VENDOR_SEPOLICY_DIRS += hardware/nothing/dirac/sepolicy/vendor
