cmake_minimum_required(VERSION 3.22)
project(airplaytv C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

add_library(airplaytv SHARED android/jni_main.c)
target_link_libraries(airplaytv log)
