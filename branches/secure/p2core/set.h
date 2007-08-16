/*
 * This file is distributed under the terms in the attached LICENSE file.
 * If you do not find this file, copies can be found by writing to:
 * Intel Research Berkeley, 2150 Shattuck Avenue, Suite 1300,
 * Berkeley, CA, 94704.  Attention:  Intel License Inquiry.
 * Or
 * UC Berkeley EECS Computer Science Division, 387 Soda Hall #1776, 
 * Berkeley, CA,  94707. Attention: P2 Group.
 * 
 * DESCRIPTION: Pel's list type
 */
 
#ifndef __SET_H__
#define __SET_H__


#include <boost/shared_ptr.hpp>
#include <set>
#include <map>
#include "value.h"
#include <utility>

class Set;
typedef boost::shared_ptr<Set> SetPtr;
typedef std::set< ValuePtr > ValPtrSet;

class Set {

public:
   // Factory
   static SetPtr mk() { SetPtr l(new Set()); return l; };
   static SetPtr mk(ValueSet *vl) { 
     SetPtr l(new Set()); 
     for (ValueSet::iterator i = vl->begin(); i != vl->end(); i++)
       l->append(*i);
     return l; 
   };

   SetPtr clone() const;

   Set() : vpl() {};
   
   // Constructs a set consisting of a single element.
   Set(ValuePtr v);

   // Constructs a set containing a copy of the passed set.
   Set(ValPtrSet set) : vpl(set) {};

   // Is the passed ValuePtr a member of the set?
   int member(ValuePtr val) const;

   // Intersect this set with another set and return the result. 
   // Result is ordered, and duplicates are not necessarily preserved. 
   // This is the intersection style described in Steele's Common Lisp.
   SetPtr intersect(SetPtr l) const;

   // Intersect this set with another set and return the result.
   // This intersection treats the sets as multisets. 
   
   SetPtr multiset_intersect(SetPtr l) const;
   
   uint32_t size() const { return vpl.size(); };
   
   // Appends a value to a set.
   void append(ValuePtr val);

   // Prepends a value to a set.
   void prepend(ValuePtr val);
   
   // Concatenates two sets together. This is the functional 
   // equivalent of Lisp-style cons. Ordering in the new set is 
   // assumed to be (this, L)
   
   SetPtr concat(SetPtr L) const;
   
   ValPtrSet::const_iterator begin();
   
   ValPtrSet::const_iterator end();
      
   string toString() const;
   
   int compareTo(SetPtr) const;
   
   void xdr_marshal( XDR *xdrs );
   
   static SetPtr xdr_unmarshal( XDR *xdrs );

   void sort();

   ValuePtr front() { return vpl.front(); }
   ValuePtr back() { return vpl.back(); }

   void pop_front() { vpl.pop_front(); }
   void pop_back() { vpl.pop_back(); }
      
private:
   ValPtrSet vpl;
};



#endif /* __SET_H__ */
