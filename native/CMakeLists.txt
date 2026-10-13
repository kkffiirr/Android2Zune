cmake_minimum_required(VERSION 3.22)
project(aftlprobe CXX C)
set(CMAKE_CXX_STANDARD 17)
set(ROOT ${CMAKE_CURRENT_SOURCE_DIR}/..)
set(AFTL ${ROOT}/XuneSyncLibrary/vendor/android-file-transfer-linux)
set(LU ${ROOT}/deps/libusb-1.0.27)
set(SSL ${ROOT}/deps/openssl/prefab/modules)

# libusb (Android config.h; no netlink/udev, usbfs only)
add_library(usb1 STATIC ${LU}/libusb/core.c ${LU}/libusb/descriptor.c ${LU}/libusb/hotplug.c
  ${LU}/libusb/io.c ${LU}/libusb/strerror.c ${LU}/libusb/sync.c
  ${LU}/libusb/os/linux_usbfs.c ${LU}/libusb/os/events_posix.c ${LU}/libusb/os/threads_posix.c
  ${LU}/libusb/os/linux_netlink.c)
target_include_directories(usb1 PUBLIC ${LU}/libusb ${LU}/android)
target_link_libraries(usb1 log)

add_library(ssl_crypto SHARED IMPORTED)
set_target_properties(ssl_crypto PROPERTIES
  IMPORTED_LOCATION ${SSL}/crypto/libs/android.${ANDROID_ABI}/libcrypto.so)

add_library(aftl STATIC ${CMAKE_CURRENT_SOURCE_DIR}/version_stub.cpp
  ${AFTL}/mtp/log.cpp ${AFTL}/mtp/ptp/DataTypeCode.cpp ${AFTL}/mtp/ptp/Device.cpp
  ${AFTL}/mtp/ptp/DeviceProperty.cpp ${AFTL}/mtp/ptp/EventCode.cpp ${AFTL}/mtp/ptp/Messages.cpp
  ${AFTL}/mtp/ptp/ObjectFormat.cpp ${AFTL}/mtp/ptp/ObjectProperty.cpp ${AFTL}/mtp/ptp/OperationCode.cpp
  ${AFTL}/mtp/ptp/PipePacketer.cpp ${AFTL}/mtp/ptp/Response.cpp ${AFTL}/mtp/ptp/Session.cpp
  ${AFTL}/mtp/usb/DeviceBusyException.cpp ${AFTL}/mtp/usb/BulkPipe.cpp ${AFTL}/mtp/usb/Request.cpp
  ${AFTL}/mtp/metadata/Metadata.cpp ${AFTL}/mtp/metadata/Library.cpp ${AFTL}/mtp/mtpz/TrustedApp.cpp
  ${AFTL}/mtp/backend/libusb/usb/Context.cpp ${AFTL}/mtp/backend/libusb/usb/Device.cpp
  ${AFTL}/mtp/backend/libusb/usb/DeviceDescriptor.cpp ${AFTL}/mtp/backend/libusb/usb/Exception.cpp
  ${AFTL}/mtp/backend/posix/FileHandler.cpp ${AFTL}/mtp/backend/posix/Exception.cpp)
target_compile_definitions(aftl PUBLIC USB_BACKEND_LIBUSB MTPZ_ENABLED)
target_include_directories(aftl PUBLIC ${AFTL} ${AFTL}/mtp/backend/libusb ${AFTL}/mtp/backend/posix ${SSL}/../../prefab/modules/crypto/include)
target_link_libraries(aftl PUBLIC usb1 ssl_crypto)


# ---- aft-cli (cli::Session) ----
set(XS ${ROOT}/XuneSyncLibrary)
add_library(aft-cli STATIC ${AFTL}/cli/Command.cpp ${AFTL}/cli/CommandLineStub.cpp ${AFTL}/cli/Session.cpp
  ${AFTL}/cli/Tokenizer.cpp ${AFTL}/cli/arg_lexer.l.cpp)
target_include_directories(aft-cli PUBLIC ${AFTL} ${AFTL}/mtp ${AFTL}/cli ${AFTL}/mtp/backend/posix ${AFTL}/mtp/backend/libusb)
target_link_libraries(aft-cli PUBLIC aftl)

# ---- libzuneandroid: XuneSyncLibrary core + JNI (curl stubbed, no TagLib) ----
file(GLOB_RECURSE XS_SRC ${XS}/lib/src/ZuneDevice.cpp ${XS}/lib/src/ZuneDeviceIdentification.cpp
  ${XS}/lib/src/NetworkManager.cpp ${XS}/lib/src/ZuneMtp*.cpp ${XS}/lib/src/ZMDBLibraryExtractor.cpp
  ${XS}/lib/src/zmdb/*.cpp ${XS}/lib/src/ptpip_client.cpp ${XS}/lib/src/ssdp_discovery.cpp
  ${XS}/lib/src/xune_sync_api.cpp ${XS}/lib/src/protocols/*.cpp)
add_library(zuneandroid SHARED ${XS_SRC})
target_include_directories(zuneandroid PRIVATE ${XS}/include ${XS}/lib/src ${CMAKE_CURRENT_SOURCE_DIR}/shim ${SSL}/crypto/include)
target_link_libraries(zuneandroid PRIVATE aft-cli aftl log)
set_target_properties(zuneandroid PROPERTIES CXX_VISIBILITY_PRESET hidden)

# ---- upload runner (reference sequence from tools/upload_test_cli.cpp) + JNI ----
add_library(tag STATIC IMPORTED)
set_target_properties(tag PROPERTIES IMPORTED_LOCATION ${ROOT}/taglib-install/${ANDROID_ABI}/lib/libtag.a)
target_sources(zuneandroid PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/upload_runner.cpp ${CMAKE_CURRENT_SOURCE_DIR}/jni_bridge.cpp)
target_include_directories(zuneandroid PRIVATE ${ROOT}/taglib-install/${ANDROID_ABI}/include ${XS})
target_link_libraries(zuneandroid PRIVATE tag z ssl_crypto)
