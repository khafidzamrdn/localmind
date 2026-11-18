cmake_minimum_required(VERSION 3.22)
project(localmind_llama LANGUAGES C CXX)

# Path to a llama.cpp checkout (pinned version documented in docs/MILESTONE-2.md).
set(LLAMA_SRC "" CACHE PATH "Path to llama.cpp source tree")
if(NOT LLAMA_SRC)
    message(FATAL_ERROR "Pass -DLLAMA_SRC=/path/to/llama.cpp")
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
# The static llama/ggml archives are linked into our shared JNI library, so
# every object must be position independent.
set(CMAKE_POSITION_INDEPENDENT_CODE ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_SRC} ${CMAKE_BINARY_DIR}/llama)

add_library(localmind_llama SHARED localmind_llama.cpp)
target_compile_features(localmind_llama PRIVATE cxx_std_17)
target_compile_options(localmind_llama PRIVATE -fvisibility=hidden -O3)
target_include_directories(localmind_llama PRIVATE
    ${LLAMA_SRC}/include
    ${LLAMA_SRC}/ggml/include
)
target_link_libraries(localmind_llama PRIVATE
    llama
    ggml
    ggml-base
    ggml-cpu
    log
)
set_target_properties(localmind_llama PROPERTIES
    CXX_VISIBILITY_PRESET hidden
    BUILD_RPATH ""
)
