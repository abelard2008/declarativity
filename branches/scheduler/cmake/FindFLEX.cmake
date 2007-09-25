# - Find flex executable and provides a macro to generate custom build rules
# The module defines the following variables:
#  FLEX_FOUND - true is flex executable is found
#  FLEX_VERSION - the version of flex
# If flex is found on the system, the module provides the macro:
#  FLEX_TARGET(Name FlexInput FlexOutput [COMPILE_FLAGS <string>])
# which creates a custom command  to generate the <FlexOutput> file from
# the <FlexInput> file.  If  COMPILE_FLAGS option is specified, the next
# parameter is added to the flex  command line. Name is an alias used to
# get  details of  this custom  command.  Indeed the  macro defines  the
# following variables:
#  FLEX_${Name}_DEFINED - true is the macro ran successfully
#  FLEX_${Name}_OUTPUTS - the source file generated by the custom rule, an
#  alias for FlexOutput
#  FLEX_${Name}_INPUT - the flex source file, an alias for ${FlexInput}
#
# Flex scanners oftenly use tokens  defined by Bison: the code generated
# by Flex  depends of the header  generated by Bison.   This module also
# defines a macro:
#  ADD_FLEX_BISON_DEPENDENCY(FlexTarget BisonTarget)
# which  adds the  required dependency  between a  scanner and  a parser
# where  <FlexTarget>  and <BisonTarget>  are  the  first parameters  of
# respectively FLEX_TARGET and BISON_TARGET macros.
#
# Example:
#  FIND_PACKAGE(BISON)
#  FIND_PACKAGE(FLEX)
#  BISON_TARGET(MyParser parser.y ${PROJECT_BINARY_DIR}/parser.cpp
#  FLEX_TARGET(MyScanner lexer.l ${PROJECT_BINARY_DIR}/lexer.cpp)
#  ADD_FLEX_BISON_DEPENDENCY(MyScanner MyParser)
#

# Copyright (c) 2006, Tristan Carel
# All rights reserved.
# Redistribution and use in source and binary forms, with or without
# modification, are permitted provided that the following conditions are met:
#
#     * Redistributions of source code must retain the above copyright
#       notice, this list of conditions and the following disclaimer.
#     * Redistributions in binary form must reproduce the above copyright
#       notice, this list of conditions and the following disclaimer in the
#       documentation and/or other materials provided with the distribution.
#     * Neither the name of the University of California, Berkeley nor the
#       names of its contributors may be used to endorse or promote products
#       derived from this software without specific prior written permission.
#
# THIS SOFTWARE IS PROVIDED BY THE REGENTS AND CONTRIBUTORS ``AS IS'' AND ANY
# EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
# WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
# DISCLAIMED. IN NO EVENT SHALL THE REGENTS AND CONTRIBUTORS BE LIABLE FOR ANY
# DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
# (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
# LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
# ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
# (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
# SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

# $Id: FindFLEX.cmake 1218 2007-03-22 22:10:45Z jmh $

SET(FLEX_FOUND FALSE)

FIND_PROGRAM(FLEX_EXECUTABLE flex DOC "path to the flex executable")
MARK_AS_ADVANCED(FLEX_EXECUTABLE)

FIND_LIBRARY(FL_LIBRARY NAMES fl
  PATHS /usr/lib DOC "path to the fl library")
SET(FLEX_LIBRARIES ${FL_LIBRARY})

IF(FLEX_EXECUTABLE)
  SET(FLEX_FOUND TRUE)

  EXECUTE_PROCESS(COMMAND ${FLEX_EXECUTABLE} --version
    OUTPUT_VARIABLE FLEX_version_output
    ERROR_VARIABLE FLEX_version_error
    RESULT_VARIABLE FLEX_version_result
    OUTPUT_STRIP_TRAILING_WHITESPACE)
  IF(NOT ${FLEX_version_result} EQUAL 0)
    MESSAGE(SEND_ERROR "Command \"${FLEX_EXECUTABLE} --version\" failed with output:\n${FLEX_version_error}")
  ELSE(NOT ${FLEX_version_result} EQUAL 0)
    STRING(REGEX REPLACE "^flex (.*)$" "\\1"
      FLEX_VERSION "${FLEX_version_output}")
  ENDIF(NOT ${FLEX_version_result} EQUAL 0)

  MACRO(FLEX_TARGET Name Input Output)
    SET(FLEX_TARGET_usage "FLEX_TARGET(<Name> <Input> <Output> [COMPILE_FLAGS <string>]")
    IF(${ARGC} GREATER 3)
      IF(${ARGC} EQUAL 5)
	IF("${ARGV3}" STREQUAL "COMPILE_FLAGS")
	  SET(FLEX_EXECUTABLE_opts  "${ARGV4}")
	  SEPARATE_ARGUMENTS(FLEX_EXECUTABLE_opts)
	ELSE("${ARGV3}" STREQUAL "COMPILE_FLAGS")
	  MESSAGE(SEND_ERROR ${FLEX_TARGET_usage})
	ENDIF("${ARGV3}" STREQUAL "COMPILE_FLAGS")
      ELSE(${ARGC} EQUAL 5)
	MESSAGE(SEND_ERROR ${FLEX_TARGET_usage})
      ENDIF(${ARGC} EQUAL 5)
    ENDIF(${ARGC} GREATER 3)
    ADD_CUSTOM_COMMAND(OUTPUT ${Output}
      COMMAND ${FLEX_EXECUTABLE} ${FLEX_EXECUTABLE_opts} -o${Output} ${Input}
      DEPENDS ${Input}
      COMMENT "[FLEX][${Name}] Building scanner with flex ${FLEX_VERSION}"
      WORKING_DIRECTORY ${CMAKE_SOURCE_DIR})

    SET(FLEX_${Name}_DEFINED TRUE)
    SET(FLEX_${Name}_OUTPUTS ${Output})
    SET(FLEX_${Name}_INPUT ${Input})
    SET(FLEX_${Name}_COMPILE_FLAGS ${FLEX_EXECUTABLE_opts})
  ENDMACRO(FLEX_TARGET)

  MACRO(ADD_FLEX_BISON_DEPENDENCY FlexTarget BisonTarget)
    IF(NOT FLEX_${FlexTarget}_TARGET)
      MESSAGE(SEND_ERROR "Flex target `${FlexTarget}' does not exists.")
    ENDIF(NOT FLEX_${FlexTarget}_TARGET)
    IF(NOT BISON_${BisonTarget}_TARGET)
      MESSAGE(SEND_ERROR "Bison target `${BisonTarget}' does not exists.")
    ENDIF(NOT BISON_${BisonTarget}_TARGET)

    SET_SOURCE_FILES_PROPERTIES(${FLEX_${FlexTarget}_OUTPUT}
      PROPERTIES OBJECT_DEPENDS ${BISON_${BisonTarget}_OUTPUT_HEADER})
  ENDMACRO(ADD_FLEX_BISON_DEPENDENCY)

ENDIF(FLEX_EXECUTABLE)

IF(NOT FLEX_FOUND)
  IF(NOT FLEX_FIND_QUIETLY)
    MESSAGE(STATUS "FLEX was not found.")
  ELSE(NOT FLEX_FIND_QUIETLY)
    IF(FLEX_FIND_REQUIRED)
      MESSAGE(FATAL_ERROR "FLEX was not found.")
    ENDIF(FLEX_FIND_REQUIRED)
  ENDIF(NOT FLEX_FIND_QUIETLY)
ENDIF(NOT FLEX_FOUND)

# FindFLEX.cmake ends here
