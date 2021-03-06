/*
 * @(#)$Id$
 *
 * Copyright (c) 2005 Intel Corporation. All rights reserved.
 *
 * This file is distributed under the terms in the attached INTEL-LICENSE file.
 * If you do not find these files, copies can be found by writing to:
 * Intel Research Berkeley, 2150 Shattuck Avenue, Suite 1300,
 * Berkeley, CA, 94704.  Attention:  Intel License Inquiry.
 * 
 * DESCRIPTION:
 *
 */

#ifndef __PEL_LEXER_H__
#define __PEL_LEXER_H__

#include <async.h>
#include <sstream>
#include "pel_program.h"
#include "pel_vm.h"

#include "val_int32.h"
#include "val_int64.h"
#include "val_uint64.h"
#include "val_str.h"
#include "val_double.h"
#include "val_null.h"

#ifndef yyFlexLexer
#define yyFlexLexer PelBaseFlexLexer
#include <FlexLexer.h>
#endif

class Pel_Lexer : public PelBaseFlexLexer {

private:

  struct opcode_token {
    const char *name;
    u_int32_t	code;
  };
  static opcode_token tokens[];
  static const size_t num_tokens;

  yy_buffer_state *bufstate;
  std::istringstream strb;

  Pel_Program *result;

  virtual int yylex();

  void add_const_int(int v) { add_const(Val_Int32::mk(v));};
  void add_const_int(int64_t v) { add_const(Val_Int64::mk(v));};
  void add_const_int(uint64_t v) { add_const(Val_UInt64::mk(v));};
  void add_const_str(str s) { add_const(Val_Str::mk(s));};
  void add_const_dbl(double d) { add_const(Val_Double::mk(d));};
  void add_const(ValueRef f);
  void add_tuple_load(int f);
  void add_opcode(u_int32_t op);
  void log_error(str errstr);

  Pel_Lexer(const char *prog);
  virtual ~Pel_Lexer() { yy_delete_buffer(bufstate); };

public:

  // 
  // Take a string and compile into a PEL program
  //
  static Pel_Program *compile(const char *prog);

  //
  // Decompile a PEL program back into a string
  //
  static str decompile(Pel_Program &prog);

  //
  // Translate a PEL opcode into a mnemonic for the instruction
  //
  static const char *opcode_mnemonic(u_int32_t opcode);

};

#endif /* __PEL_LEXER_H_ */
