/*
 * This file is distributed under the terms in the attached LICENSE file.
 * If you do not find this file, copies can be found by writing to:
 * Intel Research Berkeley, 2150 Shattuck Avenue, Suite 1300,
 * Berkeley, CA, 94704.  Attention:  Intel License Inquiry.
 * Or
 * UC Berkeley EECS Computer Science Division, 387 Soda Hall #1776, 
 * Berkeley, CA,  94707. Attention: P2 Group.
 * 
 * DESCRIPTION: Parsing environment for Overlog (the P2 dialect of datalog)
 *
 */

#ifndef __PARSER_UTIL_C__
#define __PARSER_UTIL_C__

#include "parser_util.h"
#include "val_int32.h"


//=====================================

Parse_Val::operator int() {
  return Val_Int32::cast(v);
}

Parse_Expr* Parse_Agg::DONT_CARE = new Parse_Var(Val_Str::mk("*"));
Parse_Expr* Parse_Expr::Now = new Parse_Var(Val_Str::mk("now"));

bool Parse_Agg::operator==(const Parse_Expr &e){
  try {
    const Parse_Agg& a = dynamic_cast<const Parse_Agg&>(e);
    return Parse_Expr::operator==(e) && oper == a.oper;
  }
  catch (std::bad_cast e) {
    return false;
  }
}

string Parse_Agg::toString() {
  ostringstream a;
  switch(oper) {
    case MIN:   a << "min< "; break;
    case MAX:   a << "max< "; break;
    case COUNT: a << "count< "; break;
  }
  a << v->toString() << " >";
  return a.str();
}

string Parse_Agg::aggName() {
  switch(oper) {
    case MIN:   return "min";
    case MAX:   return "max";
    case COUNT: return "count";
  }
  return "bad";
}

Parse_Bool::Parse_Bool(Parse_Bool::Operator o, Parse_Expr *l, Parse_Expr *r, bool id) 
  : oper(o), lhs(l), rhs(r), id_(id) {
  // TODO: if (oper != NOT && rhs == NULL) ERROR!
}

bool Parse_Bool::operator==(const Parse_Expr &e) {
  try {
    const Parse_Bool& b = dynamic_cast<const Parse_Bool&>(e);
    return *lhs == *b.lhs && *rhs == *b.rhs && oper == b.oper;
  } catch (std::bad_cast e) {
    return false;
  }
} 

string Parse_Bool::toString() {
  ostringstream b;
  if (oper == NOT) {
    b << "NOT( " << lhs->toString() << " )";
  }
  else if (oper == RANGE) {
    b << lhs->toString() << " IN " << rhs->toString();
  }
  else {
    if (dynamic_cast<Parse_Bool*>(lhs) != NULL) b << "( ";
    b << lhs->toString();
    if (dynamic_cast<Parse_Bool*>(lhs) != NULL) b << " )";
    switch (oper) {
      case AND: b << " AND "; break;
      case OR:  b << " OR "; break;
      case EQ:  b << " == "; break;
      case NEQ: b << " != "; break;
      case GT:  b << " > "; break;
      case LT:  b << " < "; break;
      case LTE: b << " <= "; break;
      case GTE: b << " >= "; break;
      default: assert(0);
    }
    if (dynamic_cast<Parse_Bool*>(rhs) != NULL) b << "( ";
    b << rhs->toString();
    if (dynamic_cast<Parse_Bool*>(rhs) != NULL) b << " )";
  }

  return b.str();
}


bool Parse_Range::operator==(const Parse_Expr &e){
  try {
    const Parse_Range& r = dynamic_cast<const Parse_Range&>(e);
    return *lhs == *r.lhs && *rhs == *r.rhs && type == r.type;
  } catch (std::bad_cast e) {
    return false;
  }
}

string Parse_Range::toString() {
  ostringstream r;
  switch (type) {
    case RANGEOO: 
      r << "(" << lhs->toString() << ", " << rhs->toString() << ")"; break;
    case RANGEOC: 
      r << "(" << lhs->toString() << ", " << rhs->toString() << "]"; break;
    case RANGECO: 
      r << "[" << lhs->toString() << ", " << rhs->toString() << ")"; break;
    case RANGECC: 
      r << "[" << lhs->toString() << ", " << rhs->toString() << "]"; break;
  }
  return r.str();
}


Parse_Math::operator int() {
  Parse_Math *m;
  Parse_Val  *v;
  int l = 0;
  int r = 0;
  
  if ((m=dynamic_cast<Parse_Math*>(lhs)) != NULL) 
    l = int(*m);
  else if ((v=dynamic_cast<Parse_Val*>(lhs)) != NULL) 
    l = int(*v);
  else
    return 0;	// TODO: throw some kind of exception.

  if ((m=dynamic_cast<Parse_Math*>(rhs)) != NULL) 
    r = int(*m);
  else if ((v=dynamic_cast<Parse_Val*>(rhs)) != NULL) 
    r = int(*v);
  else
    return 0;	// TODO: throw some kind of exception.

  switch (oper) {
    case LSHIFT:  return l << r;
    case RSHIFT:  return l >> r;
    case PLUS:    return l +  r;
    case MINUS:   return l -  r;
    case TIMES:   return l *  r;
    case DIVIDE:  return l /  r;
    case MODULUS: return l %  r;
    default: assert(0);
  }
}

bool Parse_Math::operator==(const Parse_Expr &e){
  try {
    const Parse_Math& m = dynamic_cast<const Parse_Math&>(e);
    return *lhs == *m.lhs && *rhs == *m.rhs && oper == m.oper;
  }
  catch (std::bad_cast e) {
    return false;
  }
}

string Parse_Math::toString() {
  ostringstream m;
  bool lpar = (dynamic_cast<Parse_Math*>(lhs) != NULL);
  bool rpar = (dynamic_cast<Parse_Math*>(rhs) != NULL);

  if (lpar) m << "( ";
  m << lhs->toString(); 
  if (lpar) m << " )";

  switch (oper) {
    case LSHIFT:  m << " <<"; break;
    case RSHIFT:  m << " >>"; break;
    case PLUS:    m << " +"; break;
    case MINUS:   m << " -"; break;
    case TIMES:   m << " *"; break;
    case DIVIDE:  m << " /"; break;
    case MODULUS: m << " %"; break;
    default: assert(0);
  }
  if (id) m << "id ";
  else m << " ";

  if (rpar) m << "( ";
  m << rhs->toString();
  if (rpar) m << " )";

  return m.str();
}

Parse_FunctorName::Parse_FunctorName(Parse_Expr *n, Parse_Expr *l) {
  name = n->v->toString(); delete n;
  if (l) {
    loc = l->v->toString();
    delete l;
  } else {
    loc = "";
  }
}

string Parse_FunctorName::toString() {
  ostringstream fn;
  fn <<  name;
  if (loc != "") fn << "@" << loc;
  return fn.str();
}

string Parse_Functor::toString() {
  ostringstream f;
  f << fn->toString() << "( ";
  for (int i = 0; i < args(); i++) {
    f << arg(i)->toString();
    if (i+1 < args()) f << ", ";
    else f << " )";
  }
  return f.str();
}

int Parse_Functor::find(Parse_Expr *arg) {
  return find(arg->v->toString());
}

int Parse_Functor::find(string argname) {
  int p = 0;
  for ( ; p < args() && arg(p)->toString() != argname; p++);
  return (p < args()) ? p : -1;
}

int Parse_Functor::aggregate() {
  for (int i = 0; i < args(); i++)
    if (dynamic_cast<Parse_Agg*>(arg(i)) != NULL) return i;
  return -1;
}

void Parse_Functor::replace(int p, Parse_Expr *e) {
  Parse_ExprList::iterator next = args_->erase((args_->begin()+p));
  args_->insert(next, e);
}

string Parse_Assign::toString() {
  return var->toString() + " = " + assign->toString();
}

string Parse_Select::toString() {
  return select->toString();
}

string Parse_Function::toString() {
  ostringstream f;
  f << v->toString() << "( ";
  for (int i = 0; i < args(); i++) {
    f << arg(i)->toString();
    if (i+1 < args()) f << ", ";
  }
  f << ")";
  return f.str();
}

string Parse_RangeFunction::toString() {
  return "RANGE( " + var->toString() + ", " + start->toString() 
         + ", " + end->toString() + " )";
}

string Parse_AggTerm::toString() {

  ostringstream aggFieldStr;
  ostringstream groupByFieldStr;

  aggFieldStr << "(";
  groupByFieldStr << "(";

  for (unsigned k = 0; k < _groupByFields->size(); k++) {
    groupByFieldStr << _groupByFields->at(k)->toString();
    if (k != _groupByFields->size() - 1) {
      groupByFieldStr << ", ";
    }
  }
  groupByFieldStr << ")";


  for (unsigned k = 0; k < _aggFields->size(); k++) {
    aggFieldStr << _aggFields->at(k)->toString();
    if (k != _aggFields->size() - 1) {
      aggFieldStr << ", ";
    }
  }
  aggFieldStr << ")";
  

  if (_oper == Parse_Agg::MIN) {
    return "min( " + groupByFieldStr.str() + 
      ", " + aggFieldStr.str() + ", " + _baseTerm->toString() + " )";    
  }

  if (_oper == Parse_Agg::MAX) {
    return "max( " + groupByFieldStr.str() + 
      ", " + aggFieldStr.str() + ", " + _baseTerm->toString() + " )";    
  }

  if (_oper == Parse_Agg::COUNT) {
    return "count( " + groupByFieldStr.str() + 
      ", " + aggFieldStr.str() + ", " + _baseTerm->toString() + " )";    
  }
  return "BAD";  
}


#endif /* __PARSER_UTIL_C__ */
