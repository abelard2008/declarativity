########################################################################
#
#  @(#)$Id: $
#
# This file is distributed under the terms in the attached LICENSE file.
# If you do not find this file, copies can be found by writing to:
# Intel Research Berkeley, 2150 Shattuck Avenue, Suite 1300,
# Berkeley, CA, 94704.  Attention:  Intel License Inquiry.
# Or
# UC Berkeley EECS Computer Science Division, 387 Soda Hall #1776, 
# Berkeley, CA,  94707. Attention: P2 Group.
#  
#
# DESCRIPTION: CMakefile for the P2 aggregates
#
ADD_LIBRARY(p2functions
            functionLoader.C)

INCLUDE_DIRECTORIES(${CMAKE_CURRENT_SOURCE_DIR})
