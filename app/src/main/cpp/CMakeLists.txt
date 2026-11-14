cmake_minimum_required(VERSION 3.22.1)
project(llama_jni VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

option(ENABLE_VULKAN "Enable Vulkan GPU offloading via ggml-vulkan" OFF)
option(ENABLE_OPENCL "Enable OpenCL GPU offloading via ggml-opencl" OFF)
set(LLAMA_CPP_DIR "" CACHE PATH "Optional local path to llama.cpp checkout")

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
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
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
        GIT_TAG master
        GIT_SHALLOW TRUE
    )
    FetchContent_MakeAvailable(llama_cpp)
    set(LLAMA_SRC "${llama_cpp_SOURCE_DIR}")
endif()

add_library(llama_jni SHARED
    llama_jni.cpp
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
    )
endif()

target_link_libraries(llama_jni PRIVATE
    llama
    llama-common
    mtmd
    android
    log
    jnigraphics
)
