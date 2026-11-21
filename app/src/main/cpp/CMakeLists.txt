cmake_minimum_required(VERSION 3.22.1)
project(llama_jni VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

option(ENABLE_VULKAN "Enable Vulkan GPU offloading via ggml-vulkan" ON)
option(ENABLE_OPENCL "Enable OpenCL GPU offloading via ggml-opencl" OFF)
set(LLAMA_CPP_DIR "" CACHE PATH "Optional local path to llama.cpp checkout")

# Pinned llama.cpp release; the JNI bridge compiles against this exact API set.
# Bump deliberately (and re-verify llama_jni.cpp) when upgrading.
set(LLAMA_CPP_TAG "v1.0.0" CACHE STRING "llama.cpp git tag / commit to build against")

# Configure llama.cpp / ggml options before adding subdirectory
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_HTTPLIB OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)

set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

if (DEFINED ANDROID_ABI AND ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif()

if (ENABLE_VULKAN)
    # ggml-vulkan precompiles its shaders with a host-side glslc. If it is not
    # installed, degrade gracefully to CPU-only instead of failing the build
    # (CI installs glslc; local dev machines may not have it).
    find_program(GLSLC_EXECUTABLE glslc)
    if (GLSLC_EXECUTABLE)
        set(GGML_VULKAN ON CACHE BOOL "" FORCE)
        message(STATUS "Vulkan GPU offload enabled (glslc: ${GLSLC_EXECUTABLE})")
        # ggml-vulkan requires the Khronos SPIRV-Headers config package at configure
        # time; fetch a pinned copy so no host SDK install is required. Needs CMake
        # >= 3.24 (the Gradle build pins cmake;3.30.3) for OVERRIDE_FIND_PACKAGE.
        include(FetchContent)
        # The name must match what ggml-vulkan passes to find_package() (case-insensitive
        # hyphenated form) for OVERRIDE_FIND_PACKAGE redirection to take effect.
        FetchContent_Declare(
            SPIRV-Headers
            GIT_REPOSITORY https://github.com/KhronosGroup/SPIRV-Headers.git
            GIT_TAG vulkan-sdk-1.4.350.1
            GIT_SHALLOW TRUE
            OVERRIDE_FIND_PACKAGE
        )
        FetchContent_MakeAvailable(SPIRV-Headers)
        include_directories(${SPIRV-Headers_SOURCE_DIR}/include)
        set(LLAMA_VULKAN_SHIM_SOURCE vulkan_api_shim.cpp)
        # The NDK ships C Vulkan headers only; ggml-vulkan also needs the C++ bindings.
        # Tag must match the NDK sysroot's VK_HEADER_VERSION exactly (static assertion).
        FetchContent_Declare(
            Vulkan-Hpp
            GIT_REPOSITORY https://github.com/KhronosGroup/Vulkan-Hpp.git
            GIT_TAG v1.3.275
            GIT_SHALLOW TRUE
        )
        FetchContent_MakeAvailable(Vulkan-Hpp)
        include_directories(${vulkan-hpp_SOURCE_DIR})
        if (CMAKE_HOST_APPLE)
            # ggml-vulkan compiles a host-side shader-gen tool. Its compiler probe tries
            # gcc first, which is a non-functional stub on macOS; pre-seed its cache
            # variables with Apple's clang from /usr/bin (NOT the NDK cross-compiler that
            # find_program would otherwise pick up). NOTE: key off CMAKE_HOST_APPLE — the
            # build target is Android, so APPLE refers to the wrong platform here.
            find_program(HOST_C_COMPILER clang PATHS /usr/bin NO_DEFAULT_PATH)
            find_program(HOST_CXX_COMPILER clang++ PATHS /usr/bin NO_DEFAULT_PATH)
        endif()
    else()
        message(WARNING "ENABLE_VULKAN=ON but glslc was not found on the host; building CPU-only. Install shaderc/glslc to enable Vulkan GPU offloading.")
        set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
    endif()
endif()

if (ENABLE_OPENCL)
    set(GGML_OPENCL ON CACHE BOOL "" FORCE)
endif()

if (LLAMA_CPP_DIR AND EXISTS "${LLAMA_CPP_DIR}/CMakeLists.txt")
    message(STATUS "Using local llama.cpp source at ${LLAMA_CPP_DIR}")
    set(LLAMA_SRC "${LLAMA_CPP_DIR}")
    add_subdirectory("${LLAMA_SRC}" build-llama EXCLUDE_FROM_ALL)
else()
    include(FetchContent)
    FetchContent_Declare(
        llama_cpp
        GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
        GIT_TAG ${LLAMA_CPP_TAG}
        GIT_SHALLOW TRUE
    )
    FetchContent_MakeAvailable(llama_cpp)
    set(LLAMA_SRC "${llama_cpp_SOURCE_DIR}")
endif()

add_library(llama_jni SHARED
    llama_jni.cpp
    ${LLAMA_VULKAN_SHIM_SOURCE}
)

target_include_directories(llama_jni PRIVATE
    "${LLAMA_SRC}"
    "${LLAMA_SRC}/include"
    "${LLAMA_SRC}/common"
    "${LLAMA_SRC}/ggml/include"
    "${LLAMA_SRC}/tools/mtmd"
)

if (DEFINED ANDROID_ABI AND ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_options(llama_jni PRIVATE
        -O3
        -march=armv8.2-a+dotprod+fp16
        -fvisibility=hidden
        -ffunction-sections
        -fdata-sections
    )
else()
    target_compile_options(llama_jni PRIVATE
        -fvisibility=hidden
        -ffunction-sections
        -fdata-sections
    )
endif()

target_link_options(llama_jni PRIVATE
    -Wl,--gc-sections
    $<$<NOT:$<CONFIG:Debug>>:-Wl,-s>
)

target_link_libraries(llama_jni PRIVATE
    llama
    llama-common
    mtmd
    android
    log
    jnigraphics
)
