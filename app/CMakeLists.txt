cmake_minimum_required(VERSION 3.22.1)
project("openclaw")

# Build the JNI bridge that embeds Node.js via dlopen/dlsym
add_library(
    node_launcher
    SHARED
    src/main/cpp/node_launcher.cpp
)

# Link against Android system libraries only — libnode.so is loaded at runtime
target_link_libraries(
    node_launcher
    android   # Android core
    log       # __android_log_print
    dl        # dlopen / dlsym
)
