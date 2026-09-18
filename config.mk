HW_NOTHING_PATH = hardware/nothing

PRODUCT_SOONG_NAMESPACES += $(HW_NOTHING_PATH)

SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS += $(HW_NOTHING_PATH)/sepolicy/common/private
BOARD_VENDOR_SEPOLICY_DIRS += $(HW_NOTHING_PATH)/sepolicy/common/vendor
SYSTEM_EXT_PUBLIC_SEPOLICY_DIRS += $(HW_NOTHING_PATH)/sepolicy/common/public

ifeq ($(BOARD_USES_QCOM_HARDWARE),true)
BOARD_VENDOR_SEPOLICY_DIRS += $(HW_NOTHING_PATH)/sepolicy/qcom/vendor
endif

ifneq ($(filter vendor.lineage.health-service.default ,$(PRODUCT_PACKAGES)),)
$(call soong_config_set,lineage_health,charging_control_charging_path,/proc/charger/usb_charger_en)
BOARD_VENDOR_SEPOLICY_DIRS += $(HW_NOTHING_PATH)/sepolicy/lineage/health
endif

ifneq ($(filter NothingEsimSwitcher ,$(PRODUCT_PACKAGES)),)
SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS += $(HW_NOTHING_PATH)/sepolicy/esimswitcher/private
endif

ifneq ($(filter DeviceExtras ,$(PRODUCT_PACKAGES)),)
SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS += $(HW_NOTHING_PATH)/sepolicy/DeviceExtras/private
SYSTEM_EXT_PUBLIC_SEPOLICY_DIRS += $(HW_NOTHING_PATH)/sepolicy/DeviceExtras/public
endif

ifneq ($(filter HieroGlyph$(PRODUCT_DEVICE) ,$(PRODUCT_PACKAGES)),)
SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS += $(HW_NOTHING_PATH)/sepolicy/HieroGlyph/private
endif
