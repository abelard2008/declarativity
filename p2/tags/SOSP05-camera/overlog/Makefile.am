########################################################################
#
#  @(#)$Id$
#
# Copyright (c) 2005 Intel Corporation. All rights reserved.
#
# This file is distributed under the terms in the attached INTEL-LICENSE file.
# If you do not find these files, copies can be found by writing to:
# Intel Research Berkeley, 2150 Shattuck Avenue, Suite 1300,
# Berkeley, CA, 94704.  Attention:  Intel License Inquiry.
#
# DESCRIPTION: Makefile for the P2 datalog parser
#

lib_LTLIBRARIES = liboverlog.la
liboverlog_la_SOURCES = \
	parser_util.C \
	ol_lexer.C \
	ol_parser.C \
	ol_context.C \
	rtr_confgen.C

INCLUDES = @SFS_INC@ -I../p2core -I../elements -I../net

CLEANFILES = core *.core *~ *.gen.h *.gen.txt ol_lexer.C \
	ol_parser.output ol_parser.C ol_parser.tab.h ol_parser.tab.c

EXTRA_DIST = .cvsignore
MAINTAINERCLEANFILES = Makefile.in 

ol_parser.C: ol_parser.y parser_util.C
	$(top_builddir)/util/fixbison.sh ol_parser.y

#	$(YACC) ol_parser.y
#	cp ol_parser.tab.c ol_parser.C

ol_parser.tab.h: ol_parser.C
 
ol_lexer.C: ol_lexer.lex ol_parser.tab.h parser_util.C
	$(top_builddir)/util/fixflex.sh -ool_lexer.C -8 ol_lexer.lex
#	$(LEX) $(AM_LFLAGS) $(LFLAGS) -ool_lexer.C -8 ol_lexer.lex

