// -*- c-basic-offset: 2; related-file-name: "ol_context.h" -*-
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
 * DESCRIPTION: Parsing environment for Overlog (the P2 dialect of datalog)
 *
 */

#include "ol_context.h"
#include "ol_lexer.h"
#include "val_uint32.h"
#include "tuple.h"
#define DEBUG_OFF
#include "trace.h"

/**********************************************************************
 *
 * Rule methods
 *
 *********************************************************************/

//
// Print out the rule for debugging purposes
//
str OL_Context::Rule::toString() {
  strbuf r;
  r << ruleID << " ";
  r << head->toString() << " :- ";

  for(std::vector<Parse_Term*>::iterator t = terms.begin(); t!=terms.end(); t++) {
    r << (*t)->toString();
    r << (((t+1) != terms.end()) ? ", " : ".");
  }

  return r;
}

str OL_Context::TableInfo::toString() {
  strbuf t;
  t << "MATERIALIZE( " << tableName << ", ";
  if (timeout < 0) t << "infinity, ";
  else t << timeout << ", ";

  if (size < 0) t << "infinity";
  else t << size;

  if (primaryKeys.size() > 0) {
    t << ", " << primaryKeys.at(0);
  }
  t << " )";

  return t;
}

/**********************************************************************
 *
 * Context methods
 *
 *********************************************************************/

//
// Adding a new rule to the environment.  Subtle: we need to resolve
// all variable references at this point and convert them to
// positional arguments. 
// 
void OL_Context::rule( Parse_Term *lhs, Parse_TermList *rhs, bool deleteFlag, Parse_Expr *n ) 
{
  TRC_FN;
  Parse_Functor *h    = dynamic_cast<Parse_Functor*>(lhs);
  str     ruleName    = (n) ? n->v->toString() : "";
  int     fict_varnum = 1;		// Counter for inventing anonymous variables. 


  // Get hold of the functor (rule head) for this new rule.  This
  // holds a list of all the rule bodies that match the head.  Note
  // that this match is only performed on (name,arity), since we
  // convert all bound values to extra "eq" terms in the body down
  // below. 

  // Create a new rule and register it. 
  Rule *r = New Rule(ruleName, h, deleteFlag);

  // Next, we canonicalize all the args in the rule head.  We build up
  // a list of argument names - the first 'arity' of these will be the
  // free variables in the rule head.  Literals and duplicate free
  // variables here are eliminated, by a process of prepending extra
  // "eq" terms to the body, and inventing new free variables.
  for(int i = 0; i < h->args(); i++) {
    Parse_Agg *agg = NULL;
    Parse_Var *var = NULL;
    Parse_Val *val = NULL;

    if ((agg = dynamic_cast<Parse_Agg*>(h->arg(i))) != NULL) {
      agg->position(i); 
    }
    else if ((var = dynamic_cast<Parse_Var*>(h->arg(i))) != NULL) {
      // The argument is a free variable - the usual case. 
      int loc = h->find(var->toString());
      if (loc < i) {
	// We've found a duplicate variable in the head. Add a new
	// "eq" term to the front of the term list. 
        Parse_Var *tmp = New Parse_Var(strbuf() << "$" << fict_varnum++);
        tmp->position(i);
        h->replace(i, tmp);
        r->terms.push_back(New Parse_Assign(tmp, h->arg(loc)));
      } else {
        var->position(i);
      }
    }
    else if ((val = dynamic_cast<Parse_Val*>(h->arg(i))) != NULL) {
      Parse_Var *tmp = New Parse_Var(strbuf() << "$" << fict_varnum++);
      tmp->position(i);
      h->replace(i, tmp);
      r->terms.push_back(New Parse_Assign(tmp, val));
    }
    else {
      error("Parse rule unknown functor body type.");
    }
  }

  // Now we've taken care of the head (and possibly created a few
  // terms), we run through the rest of the terms converting all the
  // variables we encounter to indices. 
  int tpos = 1;
  for(Parse_TermList::iterator iter = rhs->begin(); iter != rhs->end(); 
      (*iter)->position(tpos++), iter++) {
    Parse_Functor       *f  = NULL;
    Parse_Assign        *a  = NULL;
    Parse_Select        *s  = NULL;
    Parse_RangeFunction *rf = NULL;

    if ((f = dynamic_cast<Parse_Functor*>(*iter)) != NULL) {

      for(int i = 0; i < f->args(); i++) {
        Parse_Var *var = NULL;
        Parse_Val *val = NULL;

        if ((var = dynamic_cast<Parse_Var*>(f->arg(i))) != NULL) {
          var->position(i);
        }
        else if ((val = dynamic_cast<Parse_Val*>(f->arg(i))) != NULL) {
          val->position(i);
        }
        else {
          error("Parse functor term unknown argument type.");
        }
      }
    }
    else if ((a = dynamic_cast<Parse_Assign*>(*iter)) != NULL) {

    }
    else if ((s = dynamic_cast<Parse_Select*>(*iter)) != NULL) {

    }
    else if ((rf = dynamic_cast<Parse_RangeFunction*>(*iter)) != NULL) {

    }
    else error("Internal parse error: unknown term type!");

    r->terms.push_back(*iter);
  }
  rules->push_back(r);
}

//
// Print out the whole parse result, if we can
//
str OL_Context::toString()
{
  strbuf r;

  // Errors first
  for( ErrorList::iterator e=errors.begin(); e!=errors.end(); e++) {
    r << "error(" << (*e)->line_num << ",'" << (*e)->msg << "').\n";
  }
  for (TableInfoMap::iterator i = tables->begin(); i != tables->end(); i++ ) {
    r << i->second->toString() << "\n";
  }
  
  for(RuleList::iterator rule=rules->begin(); rule != rules->end(); rule++) {
    r << (*rule)->toString() << "\n";
  }
  return r;
}

OL_Context::OL_Context() : lexer(NULL)
{
  rules  = New RuleList();
  tables = New TableInfoMap();
}

OL_Context::~OL_Context()
{
  delete rules;
  delete tables;
}

void OL_Context::parse_string(const char *prog)
{
  assert(lexer==NULL);
  lexer = New OL_Lexer(prog);
  ol_parser_parse(this);
  delete lexer;
  lexer = NULL;
}
void OL_Context::parse_stream(std::istream *str)
{
  assert(lexer==NULL);
  lexer = New OL_Lexer(str);
  ol_parser_parse(this);
  delete lexer;
  lexer = NULL;
}

void OL_Context::error(str msg)
{
  errors.push_back(New OL_Context::Error(lexer->line_num(), msg));
}

void OL_Context::watch(Parse_Expr *w)
{
  std::cout << "Add watch variable " << w->toString() << "\n";
  watchTables.insert(w->v->toString());
}


void OL_Context::table(Parse_Expr *name, Parse_Expr *ttl, 
                       Parse_Expr *size, Parse_ExprList *keys) {
  TableInfo  *tableInfo = new TableInfo();
  Parse_Math *math;
  
  tableInfo->tableName = name->toString();
  
  
  if ((math = dynamic_cast<Parse_Math*>(ttl)) == NULL) {
    tableInfo->timeout = Val_UInt32::cast(ttl->v);
    if (tableInfo->timeout == 0) {
      error("bad timeout for materialized table");
      goto mat_error;
    }
  }
  else {
    tableInfo->timeout = *math;
  }

  if ((math = dynamic_cast<Parse_Math*>(size)) == NULL) {
    tableInfo->size = Val_UInt32::cast(size->v);
    if (tableInfo->size == 0) {
      error("bad size for materialized table");
      goto mat_error;
    }
  }
  else {
    tableInfo->timeout = *math;
  }

  if (keys) {
    for (Parse_ExprList::iterator i = keys->begin(); i != keys->end(); i++)
	tableInfo->primaryKeys.push_back(Val_UInt32::cast((*i)->v));
  }

  tables->insert(std::make_pair(tableInfo->tableName, tableInfo));
  
  DBG( "Materialize " << tableInfo->tableName << "/"
       << ", timeout " << tableInfo->timeout << ", size " << tableInfo->size); 

 mat_error:
  delete name;
  delete ttl;
  delete size;
  if (keys) delete keys;
}

//
// Adding a fact
//
void OL_Context::fact(Parse_Term *t)
{
  Parse_Functor *f = dynamic_cast<Parse_Functor*>(t);

  TupleRef tpl = Tuple::mk();
  tpl->append(Val_Str::mk(f->fn->name));
  for (int i = 0; i < f->args(); i++) {
    Parse_Val  *val = dynamic_cast<Parse_Val*>(f->arg(i));
    if (val != NULL) {
      tpl->append(val->v);
    } 
    else {
      error("free variables and don't-cares not allowed in facts:" << f->arg(i)->toString());
      goto fact_error;
    }
  }
  tpl->freeze();
  facts.push_back(tpl);

  DBG("Fact: " << tblname << " <- " << tpl->toString());
    
  fact_error:
    delete f;
}
