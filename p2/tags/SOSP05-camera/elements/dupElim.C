// -*- c-basic-offset: 2; related-file-name: "dupElim.h" -*-
/*
 * @(#)$Id$
 * 
 * This file is distributed under the terms in the attached INTEL-LICENSE file.
 * If you do not find these files, copies can be found by writing to:
 * Intel Research Berkeley, 2150 Shattuck Avenue, Suite 1300,
 * Berkeley, CA, 94704.  Attention:  Intel License Inquiry.
 * 
 */

#include "dupElim.h"

DupElim::DupElim(str name)
  : Element(name, 1, 1)
{
}

TuplePtr DupElim::simple_action(TupleRef p)
{
  // Attempt to insert tuple
  std::pair< std::set< TupleRef >::iterator, bool > result = _table.insert(p);

  // Did we succeed?
  if (result.second) {
    // Yup, the tuple is inserted
    return p;
  } else {
    // No, another one was there
    return 0;
  }
}
