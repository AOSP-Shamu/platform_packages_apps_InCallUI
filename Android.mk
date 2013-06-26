LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_JAVA_LIBRARIES := telephony-common
LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_PACKAGE_NAME := InCallUI
LOCAL_CERTIFICATE := platform

include $(BUILD_PACKAGE)
