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
 */

#include "val_id.h"

#include "val_uint32.h"
#include "val_uint64.h"


//
// Marshalling and unmarshallng
//
void Val_ID::xdr_marshal_subtype( XDR *x )
{
  i->xdr_marshal(x);
}

ValueRef Val_ID::xdr_unmarshal( XDR *x )
{
  return Val_ID::mk(ID::xdr_unmarshal(x));
}


//
// Casting
//
IDRef Val_ID::cast(ValueRef v) {
  Value *vp = v;
  switch (v->typeCode()) {
  case Value::ID:
    return (static_cast<Val_ID *>(vp))->i;
  case Value::UINT32:
    return ID::mk(Val_UInt32::cast(v));
  case Value::UINT64:
    return ID::mk(Val_UInt64::cast(v));
  default:
    throw Value::TypeError(v->typeCode(), Value::ID );
  }
}

int Val_ID::compareTo(ValueRef other) const
{
  if (other->typeCode() != Value::ID) {
    if (Value::ID < other->typeCode()) {
      return -1;
    } else if (Value::ID > other->typeCode()) {
      return 1;
    }
  }
  return i->compareTo(cast(other));
}

/*
 * End of file
 */
