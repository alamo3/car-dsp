cmake_minimum_required(VERSION 3.22.1)
project(cabindsp CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(cabindsp SHARED cabin_dsp_jni.cpp)
target_compile_options(cabindsp PRIVATE -O3 -Wall -Wextra)
target_link_libraries(cabindsp log)
