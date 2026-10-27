# SPDX-FileCopyrightText: 2026 Jime contributors
#
# SPDX-License-Identifier: BSD-3-Clause

set(Boost_FOUND TRUE)

list(TRANSFORM BOOST_INCLUDE_LIBRARIES PREPEND Boost:: OUTPUT_VARIABLE
                                                       Boost_LIBRARIES)

file(GLOB __boost_installed_libs
    LIST_DIRECTORIES true
    RELATIVE "${CMAKE_SOURCE_DIR}/deps"
    "${CMAKE_SOURCE_DIR}/deps/boost/libs/*"
)

foreach(__lib ${__boost_installed_libs})
    set(__full_dir "${CMAKE_SOURCE_DIR}/deps/${__lib}/include")
    if(IS_DIRECTORY "${__full_dir}")
        list(APPEND Boost_INCLUDE_DIRS "${__full_dir}")
    endif()
endforeach()