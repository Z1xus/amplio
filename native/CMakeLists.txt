cmake_minimum_required(VERSION 3.24)
project(amplio_native LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
# nvsdk_ngx_s.lib is built against the static msvc runtime
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded")
set(DLSS_SDK "${CMAKE_CURRENT_SOURCE_DIR}/external/DLSS" CACHE PATH "NVIDIA DLSS SDK")
# release builds bundle the c++ runtime so the library loads on any distro
option(AMPLIO_STATIC_RUNTIME "Link the C++ runtime statically" OFF)

find_package(Vulkan REQUIRED)
find_package(Threads REQUIRED)

add_library(amplio SHARED src/amplio.cpp)
target_include_directories(amplio PRIVATE "${DLSS_SDK}/include")
target_link_libraries(amplio PRIVATE Vulkan::Vulkan Threads::Threads)

if(WIN32)
    target_link_libraries(amplio PRIVATE "${DLSS_SDK}/lib/Windows_x86_64/x64/nvsdk_ngx_s.lib")
else()
    target_link_libraries(amplio PRIVATE "${DLSS_SDK}/lib/Linux_x86_64/libnvsdk_ngx.a" dl)
    target_link_options(amplio PRIVATE -Wl,--no-undefined -Wl,--gc-sections)
    if(AMPLIO_STATIC_RUNTIME)
        target_link_options(amplio PRIVATE -static-libstdc++ -static-libgcc)
    endif()
endif()
