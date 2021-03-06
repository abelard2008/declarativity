// -*- c-basic-offset: 2; related-file-name: "functorSource.C" -*-
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
 * DESCRIPTION: Element that produces whatever a generating functor says
 * whenever pulled.  It never blocks.
 */


#ifndef __FUNCTOR_SOURCE_H__
#define __FUNCTOR_SOURCE_H__

#include <element.h>

class FunctorSource : public Element { 
 public:
  
  struct Generator {
    virtual ~Generator() {};
    virtual TuplePtr operator()() = 0;
  };

  FunctorSource(string, Generator*);

  const char *class_name() const		{ return "FunctorSource"; }
  const char *flow_code() const			{ return "/-"; }
  const char *processing() const		{ return "/l"; }

  virtual TuplePtr pull(int port, b_cbv cb);

 protected:
  Generator* _generator;
};

#endif /* __FUNCTOR_SOURCE_H_ */
