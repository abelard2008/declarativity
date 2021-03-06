// -*- c-basic-offset: 2; related-file-name: "printTime.C" -*-
/*
 * @(#)$Id$
 * 
 * This file is distributed under the terms in the attached INTEL-LICENSE file.
 * If you do not find these files, copies can be found by writing to:
 * Intel Research Berkeley, 2150 Shattuck Avenue, Suite 1300,
 * Berkeley, CA, 94704.  Attention:  Intel License Inquiry.
 * 
 * DESCRIPTION: Element which simply prints any tuple that passes
 * through it.
 */

#ifndef __PRINTWATCH_H__
#define __PRINTWATCH_H__

#include "element.h"
#include <set>

class PrintWatch : public Element { 
public:

  PrintWatch(str prefix, std::set<str> tableNames);

  ~PrintWatch();
  
  /** Overridden to perform the printing */
  TuplePtr simple_action(TupleRef p);

  const char *class_name() const		{ return "PrintWatch";}
  const char *processing() const		{ return "a/a"; }
  const char *flow_code() const			{ return "x/x"; }

private:
  /** The prefix to be placed on every printout by this element */
  str _prefix;
  std::set<str> _tableNames;
};


#endif /* __PRINTWATCH_H_ */
