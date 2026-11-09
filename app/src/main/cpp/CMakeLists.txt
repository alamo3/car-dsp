cmake_minimum_required(VERSION 3.22.1)
project(cabindsp CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(cabindsp SHARED cabin_dsp_jni.cpp)
target_compile_options(cabindsp PRIVATE -O3 -Wall -Wextra)
target_link_libraries(cabindsp log)

# 16 KB page-size devices (Android 15+ on Pixel 8/Samsung and others) refuse to load native
# libraries whose LOAD segments are only 4 KB aligned. Newer NDKs default to 16 KB; this keeps
# the build correct for older NDKs too.
target_link_options(cabindsp PRIVATE "-Wl,-z,max-page-size=16384")
