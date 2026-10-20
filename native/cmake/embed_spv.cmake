# Usage: cmake -DIN=<file.spv> -DOUT=<header.h> -DNAME=<symbol> -P embed_spv.cmake
file(READ "${IN}" hex HEX)
string(LENGTH "${hex}" n)
math(EXPR bytes "${n} / 2")
math(EXPR rem "${bytes} % 4")
if(NOT rem EQUAL 0)
  message(FATAL_ERROR "${IN} is not a whole number of 32-bit words")
endif()
# one regex pass (little-endian bytes -> uint32 literals): the word-by-word loop this replaced was quadratic, 13 minutes
# of CPU for the 1.3 MB far kernel
string(REGEX REPLACE "(..)(..)(..)(..)" "0x\\4\\3\\2\\1u," words "${hex}")
file(WRITE "${OUT}" "// generated from ${IN}; do not edit\n#pragma once\n#include <cstddef>\n#include <cstdint>\nstatic const uint32_t ${NAME}[] = {${words}};\nstatic const size_t ${NAME}_size = sizeof(${NAME});\n")
