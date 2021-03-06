/*
 * @(#)$Id$
 *
 * This file is distributed under the terms in the attached LICENSE file.
 * If you do not find this file, copies can be found by writing to:
 * Intel Research Berkeley, 2150 Shattuck Avenue, Suite 1300,
 * Berkeley, CA, 94704.  Attention:  Intel License Inquiry.
 * Or
 * UC Berkeley EECS Computer Science Division, 387 Soda Hall #1776, 
 * Berkeley, CA,  94707. Attention: P2 Group.
 * 
 * DESCRIPTION: P2's concrete type system: String type.
 *
 */

#include "val_opaque.h"
#include "val_str.h"

const opr::Oper* Val_Opaque::oper_ = new opr::OperCompare<Val_Opaque>();


//
// Marshal an opaque
// 
void Val_Opaque::xdr_marshal_subtype( XDR *x ) 
{
  warn << "Cannot marshal an OPAQUE value\n";
}

//
// Casting
//
FdbufPtr Val_Opaque::cast(ValuePtr v)
{
  switch (v->typeCode()) {
  case Value::OPAQUE:
    return (static_cast<Val_Opaque *>(v.get()))->b;
  case Value::STR:
    {
      FdbufPtr fb(new Fdbuf());
      fb->pushBack(Val_Str::cast(v));
      return fb;
    }
  default:
    throw Value::TypeError(v->typeCode(), Value::OPAQUE );
  }
}
  
int Val_Opaque::compareTo(ValuePtr other) const
{
  if (other->typeCode() != Value::OPAQUE) {
    if (Value::OPAQUE < other->typeCode()) {
      return -1;
    } else if (Value::OPAQUE > other->typeCode()) {
      return 1;
    }
  }
  warn << "Comparing opaques. Not implemented yet!!!\n";
  exit(-1);
  //  return cmp(b, cast(other));
  return -1;
}

/* 
 * End of file
 */
