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
 * DESCRIPTION: A directory for all defined aggregate functions.
 *
 */

#ifndef __AGGFACTORY_H__
#define __AGGFACTORY_H__

#include <boost/function.hpp>
#include <boost/shared_ptr.hpp>
#include "map"
#include "commonTable.h"

class AggFactory
{
public:
  /** A type for aggregate function factories.  Just a boost
      function. */
  typedef boost::function< CommonTable::AggFunc* () > AggFuncFactory;

  
  /** Returns a new aggregate function object on the heap. Must be
      deleted in the end.  It never returns a null. If the given name is
      not found, an AggregateNotFound exception is raised. */
  static CommonTable::AggFunc*
  mk(std::string aggName);


  /** Returns an aggregate function factory.  If the given name is not
      found, an AggregateNotFound exception is raised. */
  static AggFuncFactory
  factory(std::string aggName);


  /** Returns a string containing all aggregates known to me */
  static std::string
  aggList();


  /** An exception thrown when a factory fails to locate a named
      aggregate function */
  struct AggregateNotFound {
  public:
    AggregateNotFound(std::string name);
    
    
    std::string aggName;
  };

  
  
private:
  /** A type for a directory of aggregate function factories. */
  typedef std::map< std::string, // name
                    AggFuncFactory, // factory
                    std::less< std::string > > FactorySet;
  

  /** The actual directory */
  static FactorySet _factories;


  /** A static initializer object to initialize static class objects */
  class Initializer {
  public:
    Initializer();
  };

  
  /** And the actual dummy initializer object.  Its constructor is the
      static initializer. */
  static Initializer _INITIALIZER;


  /** Registers an aggregate function factory given its name (e.g., MIN,
      MAX, etc.) */
  static bool
  add(std::string aggName,
      AggFuncFactory factory);




};


#endif // AGGMIN_H
