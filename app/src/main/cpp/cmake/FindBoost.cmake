# SPDX-FileCopyrightText: 2015 - 2024 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later

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