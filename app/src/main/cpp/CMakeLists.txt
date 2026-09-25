cmake_minimum_required(VERSION 3.22.1)
project(mpvex_xr LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Provided by the org.khronos.openxr:openxr_loader_for_android prefab package.
find_package(OpenXR REQUIRED CONFIG)

add_library(mpvex_xr SHARED
    xr_app.cpp
    crt_scene.cpp
    tv_model.cpp
)

target_compile_definitions(mpvex_xr PRIVATE
    XR_USE_PLATFORM_ANDROID
    XR_USE_GRAPHICS_API_OPENGL_ES
)
target_compile_options(mpvex_xr PRIVATE -Wall -Wextra -Wno-unused-parameter -Wno-missing-field-initializers)

target_link_libraries(mpvex_xr
    OpenXR::openxr_loader
    EGL
    GLESv3
    android
    log
)
