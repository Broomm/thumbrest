cmake_minimum_required(VERSION 3.22)
project(thumbrest LANGUAGES CXX)

add_library(thumbrest SHARED android_bridge.cpp linux_pad.cpp)
target_compile_features(thumbrest PRIVATE cxx_std_17)
target_compile_options(thumbrest PRIVATE -Wall -Wextra -Werror)
