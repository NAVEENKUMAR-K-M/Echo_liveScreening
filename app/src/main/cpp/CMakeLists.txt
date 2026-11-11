cmake_minimum_required(VERSION 3.22.1)
project(echoaudio LANGUAGES CXX)

find_package(oboe REQUIRED CONFIG)

add_library(echoaudio SHARED native_audio_engine.cpp)
target_compile_features(echoaudio PRIVATE cxx_std_17)
target_compile_options(echoaudio PRIVATE -Wall -Wextra)
target_link_options(echoaudio PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384"
)
target_link_libraries(echoaudio PRIVATE oboe::oboe log)
