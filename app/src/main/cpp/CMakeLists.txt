cmake_minimum_required(VERSION 3.10)
project(ivyvnc C)

# Vendored LibVNCClient (LibVNCServer 0.9.15, GPL-2.0-or-later).
# Only the client library is built, with every optional dependency disabled
# so the build needs nothing beyond the NDK (zlib comes from the NDK sysroot).
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(LIBVNCSERVER_INSTALL OFF CACHE BOOL "" FORCE)
set(WITH_THREADS ON CACHE BOOL "" FORCE)
set(WITH_ZLIB ON CACHE BOOL "" FORCE)
foreach(opt LZO JPEG PNG SDL GTK QT LIBSSHTUNNEL GNUTLS OPENSSL GCRYPT
            SYSTEMD FFMPEG WEBSOCKETS SASL XCB EXAMPLES TESTS)
  set(WITH_${opt} OFF CACHE BOOL "" FORCE)
endforeach()

add_subdirectory(libvncclient)

add_library(ivyvnc SHARED jni_bridge.c vnc_client.c)
target_include_directories(ivyvnc PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/libvncclient/include
    ${CMAKE_CURRENT_BINARY_DIR}/libvncclient/include)
target_link_libraries(ivyvnc PRIVATE vncclient log android z)

# 16 KB page-size support (required for Android 15+ / Play and good practice)
target_link_options(ivyvnc PRIVATE -Wl,-z,max-page-size=16384)
