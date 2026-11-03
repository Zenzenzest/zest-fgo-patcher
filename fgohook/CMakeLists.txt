cmake_minimum_required(VERSION 3.20)
project(fgohook CXX C ASM)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 17)

set(DOBBY_BUILD_EXAMPLE OFF CACHE BOOL "" FORCE)
set(DOBBY_BUILD_TEST OFF CACHE BOOL "" FORCE)
set(Plugin.SymbolResolver OFF CACHE BOOL "" FORCE)

add_subdirectory(third_party/dobby dobby)

add_library(fgohook SHARED src/jni_entry.cpp)
target_include_directories(fgohook PRIVATE include)
target_link_libraries(fgohook dobby_static log dl)
