cmake_minimum_required(VERSION 3.22.1)
project(aibox LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

option(AIBOX_VULKAN  "Enable Vulkan backend"   ON)
option(AIBOX_OPENCL  "Enable OpenCL backend"  ON)
option(AIBOX_NPU     "Enable Hexagon NPU"     OFF)

# Vulkan header version mismatch fix: NDK 26.1 headers are v365, but environment has v366.
# Disable the static_assert in vulkan.hpp so ggml-vulkan.cpp compiles.
# MUST be set BEFORE add_subdirectory(llama.cpp) to affect ggml-vulkan target.
add_compile_definitions(VULKAN_HPP_NO_VERSION_CHECK)

if(EXISTS /usr/local/share/cmake/SPIRV-Headers/SPIRV-HeadersConfig.cmake)
    set(SPIRV-Headers_DIR /usr/local/share/cmake/SPIRV-Headers CACHE PATH "" FORCE)
endif()

set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp)

if(EXISTS ${LLAMA_DIR}/CMakeLists.txt)
    set(GGML_VULKAN   ${AIBOX_VULKAN} CACHE BOOL "" FORCE)
    # OpenCL: 只在有库时启用，否则跳过（运行时 dlopen）
    find_library(OPENCL_LIB OpenCL)
    if(OPENCL_LIB)
        set(GGML_OPENCL ON CACHE BOOL "" FORCE)
    else()
        set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
        message(STATUS "OpenCL library not found, ggml-opencl will not be compiled (runtime dlopen still works)")
    endif()
    set(GGML_HEXAGON  ${AIBOX_NPU}    CACHE BOOL "" FORCE)
    set(GGML_OPENBLAS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
    add_subdirectory(${LLAMA_DIR} llama_build EXCLUDE_FROM_ALL)
else()
    message(FATAL_ERROR "llama.cpp not found at ${LLAMA_DIR}")
endif()

add_library(aibox SHARED
    native_bridge.cpp
    bridge.cpp
    memory_pool.cpp
    thermals.cpp
    crash/crash_handler.cpp
)

target_include_directories(aibox PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${LLAMA_DIR}/include
    ${LLAMA_DIR}/ggml/include
)

target_link_libraries(aibox PRIVATE llama ggml)

if(AIBOX_NPU)
    target_compile_definitions(aibox PRIVATE POCKET_NPU=1)
endif()

if(AIBOX_VULKAN)
    find_library(VULKAN_LIB vulkan REQUIRED)
    target_link_libraries(aibox PRIVATE ${VULKAN_LIB})
    target_compile_definitions(aibox PRIVATE POCKET_VULKAN=1)
    target_compile_definitions(aibox PRIVATE VULKAN_HPP_NO_VERSION_CHECK)
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -DVULKAN_HPP_NO_VERSION_CHECK" CACHE STRING "" FORCE)
endif()

# OpenCL: 运行时 dlopen，编译时只需要定义宏
# 在 Android 设备上 libOpenCL.so 由 GPU 驱动提供
target_compile_definitions(aibox PRIVATE POCKET_OPENCL=1)

find_library(LOG_LIB log REQUIRED)
find_library(ANDROID_LIB android REQUIRED)
target_link_libraries(aibox PRIVATE ${LOG_LIB} ${ANDROID_LIB})

target_compile_options(aibox PRIVATE
    -Ofast -fvisibility=hidden -fvisibility-inlines-hidden
    -ffunction-sections -fdata-sections
)
target_link_options(aibox PRIVATE
    -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-s
)
