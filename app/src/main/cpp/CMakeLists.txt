cmake_minimum_required(VERSION 3.22.1)
project(terminal_guard)
add_library(terminal_guard SHARED terminal_guard.cpp)
target_compile_options(terminal_guard PRIVATE -Wall -Wextra -Werror)
