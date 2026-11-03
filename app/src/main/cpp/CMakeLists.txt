cmake_minimum_required(VERSION 3.22.1)
project("goatsdrive_engine" LANGUAGES CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Aggressive automotive performance flags for low-spec Cortex-A7/A53/A55 SoC
add_compile_options(
    -O3
    -flto
    -ffast-math
    -fomit-frame-pointer
    -fno-exceptions
    -fno-rtti
    -fvisibility=hidden
    -fvisibility-inlines-hidden
    -Wall
    -Wextra
    -pthread
    -DNDEBUG
)

set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -flto -Wl,--gc-sections -Wl,--strip-all")

include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}
)

add_library(
    goatsdrive_engine
    SHARED
    native-bridge.cpp
    transport_io.cpp
    native-decoder.cpp
    native-audio.cpp
)

find_library(log-lib log REQUIRED)
find_library(android-lib android REQUIRED)
find_library(mediandk-lib mediandk REQUIRED)
find_library(opensles-lib OpenSLES REQUIRED)
find_library(jnigraphics-lib jnigraphics REQUIRED)

# AAudio was introduced in API 26; find optionally for backward compatibility
find_library(aaudio-lib aaudio)

set(OPTIONAL_LIBS "")
if(aaudio-lib)
    list(APPEND OPTIONAL_LIBS ${aaudio-lib})
    add_compile_definitions(HAVE_AAUDIO=1)
endif()

target_link_libraries(
    goatsdrive_engine
    PRIVATE
    ${log-lib}
    ${android-lib}
    ${mediandk-lib}
    ${opensles-lib}
    ${jnigraphics-lib}
    ${OPTIONAL_LIBS}
)
