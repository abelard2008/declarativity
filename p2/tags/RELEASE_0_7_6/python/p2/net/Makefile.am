########################################################################
#
#  @(#)$Id$
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
# DESCRIPTION: Makefile for the P2 core elements python library
#

lib_LTLIBRARIES =			libp2pythonnet.la

libp2pythonnet_la_SOURCES =		\
					bw.cpp \
					basicAck.cpp \
					cumulativeAck.cpp \
					cct.cpp \
					rccr.cpp \
					rcct.cpp \
					defrag.cpp \
					frag.cpp \
					plsensor.cpp \
					rdelivery.cpp \
					odelivery.cpp \
					dupRemove.cpp \
					skr.cpp \
					snetsim.cpp \
					tman.cpp \
					tupleseq.cpp \
					roundTripTimer.cpp \
					udp.cpp \
					udp2.cpp


libp2pythonnet_la_CPPFLAGS =		@BOOST_INC@ \
					-I$(top_builddir)/p2core \
					-I$(top_builddir)/net \
					-I$(top_builddir)/eventLoop

CLEANFILES =				core *.core *~

MAINTAINERCLEANFILES =			Makefile.in

libp2pythonnet_la_CXXFLAGS =		-DBOOST_DATE_TIME_POSIX_TIME_STD_CONFIG \
					@PYTHON_CSPEC@
