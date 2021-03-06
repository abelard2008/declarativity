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
 * DESCRIPTION: PEL (P2 Expression Language) virtual machine
 *
 */

#ifndef __PEL_VM_H__
#define __PEL_VM_H__

#include <deque>
#include <vector>
#include <async.h>

#include "tuple.h"
#include "pel_program.h"
#include "ID.h"

class Pel_VM {

public: 
  enum Error {
    PE_SUCCESS=0,
    PE_BAD_CONSTANT,
    PE_BAD_FIELD,
    PE_OPER_UNSUP,
    PE_STACK_UNDERFLOW,
    PE_TYPE_CONVERSION,
    PE_BAD_OPCODE,
    PE_DIVIDE_BY_ZERO,
    PE_INVALID_ERRNO,
    PE_STOP,
    PE_UNKNOWN // Must be the last error
  };

#include "pel_opcode_decls.gen.h"

private:
  // Execution state
  std::deque<ValueRef> _st;
  const Pel_Program	*prg;
  Error		 error;
  uint		 pc;
  TuplePtr	 result;
  TuplePtr	 operand;

  static const char* err_msgs[];

  ValueRef pop();
  uint64_t pop_unsigned();
  int64_t pop_signed();
  str pop_string();
  double pop_double();
  struct timespec pop_time();
  IDRef pop_ID();


  // Deque to stack conversion facilities
  inline void stackPop() {  _st.pop_back(); }
  inline void stackPush(ValueRef v) { _st.push_back(v); }
  inline ValueRef stackTop() { return _st.back(); }
  inline ValueRef stackPeek(unsigned p) { return _st[_st.size() - 1 - p]; }


public:
  Pel_VM();
  Pel_VM(std::deque< ValueRef >);

  // 
  // Execution paths
  // 

  // Reset the VM (clear the stack)
  void reset();

  // Stop execution without error
  void stop();

  /** Dump the stack */
  void dumpStack(str);

  // Execute the program on the tuple. 
  // Return 0 if success, -1 if an error. 
  Error execute(const Pel_Program &prog, const TupleRef data);
  
  // Single step an instruction
  Error execute_instruction( u_int32_t inst, TupleRef data);

  // Return the result (top of the stack)
  ValueRef result_val();
  
  // Return the current result tuple.
  TuplePtr result_tuple();

  // Reset the result tuple to nothingness
  void reset_result_tuple();

  // Convert the error into a string.
  static const char *strerror(Error e);
};

#endif /* __PEL_VM_H_ */
