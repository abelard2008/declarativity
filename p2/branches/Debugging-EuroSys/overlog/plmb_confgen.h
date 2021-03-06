// -*- c-basic-offset: 2; related-file-name: "ol_context.h" -*-
/*
 * @(#)$Id$
 *
 * This file is distributed under the terms in the attached INTEL-LICENSE file.
 * If you do not find these files, copies can be found by writing to:
 * Intel Research Berkeley, 2150 Shattuck Avenue, Suite 1300,
 * Berkeley, CA, 94704.  Attention:  Intel License Inquiry.
 * Or
 * UC Berkeley EECS Computer Science Division, 387 Soda Hall #1776, 
 * Berkeley, CA,  94707. Attention: P2 Group.
 * 
 * DESCRIPTION: Takes as input the system environment (udp send / receive, dups) and 
 * the Overlog parsing context, and then generate the Plumber Configuration
 *              
 */

#ifndef __OL_RTR_CONFGEN_H__
#define __OL_RTR_CONFGEN_H__

//#if HAVE_CONFIG_H
//#include <config.h>
//#endif /* HAVE_CONFIG_H */

#include <list>
#include <map>
#include <iostream>
#include <stdlib.h>
#include <fstream>

#include "ol_context.h"
#include "value.h"
#include "parser_util.h"
#include "ol_lexer.h"
#include "tuple.h"
#include "plumber.h"
#include "val_int32.h"
#include "val_str.h"
#include "print.h"
#include "discard.h"
#include "pelTransform.h"
#include "duplicate.h"
#include "dupElim.h"
#include "filter.h"
#include "timedPullPush.h"
#include "udp.h"
#include "marshalField.h"
#include "unmarshalField.h"
#include "unboxField.h"
#include "mux.h"
#include "demux.h"
#include "strToSockaddr.h"
#include "slot.h"
#include "timedPullSink.h"
#include "timestampSource.h"
#include "hexdump.h"
#include "table.h"
#include "lookup.h"
#include "insert.h"
#include "scan.h"
#include "queue.h"
#include "printTime.h"
#include "roundRobin.h"
#include "noNullField.h"
#include "functorSource.h"
#include "delete.h"
#include "tupleSource.h"
#include "printWatch.h"
#include "aggregate.h"
#include "duplicateConservative.h"
#include "aggwrap.h"
#include "tupleseq.h"
#include "cc.h"
#include "loop.h"

class Plmb_ConfGen {
 private:
  class FieldNamesTracker;
  struct ReceiverInfo;

 public:
  Plmb_ConfGen(OL_Context* ctxt, Plumber::ConfigurationPtr conf, 
	      bool _dups, bool debug, bool cc, string filename);
  Plmb_ConfGen::~Plmb_ConfGen();

  void configurePlumber(boost::shared_ptr< Udp > udp, string nodeID);

  TablePtr getTableByName(string nodeID, string tableName);

  void createTables(string nodeID);

  void clear();

  // allow driver program to push data into dataflow
  void registerUDPPushSenders(ElementSpecPtr elementSpecPtr);
  
private:
  static const string SEL_PRE, AGG_PRE, ASSIGN_PRE, TABLESIZE;
  typedef std::map<string, TablePtr> TableMap;
  typedef std::map<string, string> PelFunctionMap;
  typedef std::map<string, ReceiverInfo> ReceiverInfoMap;

  PelFunctionMap pelFunctions;
  TableMap _tables;
  OL_Context* _ctxt; // the context after parsing
  bool _dups; // do we care about duplicates in our dataflow? 
  bool _debug; // do we stick debug elements in?
  bool _cc; // are we using congestion control
  FILE *_output;
  Plumber::ConfigurationPtr _conf; 
  std::map<string, string> _multTableIndices;

  // counter to determine how many muxers and demuxers are needed
  string _curType;
  std::vector<ElementSpecPtr> _udpSenders;
  std::vector<int> _udpSendersPos;
  std::vector<ElementSpecPtr> _currentElementChain;
   
  ReceiverInfoMap _udpReceivers; // for demuxing
  bool _pendingRegisterReceiver;
  string  _pendingReceiverTable;
  ElementSpecPtr _pendingReceiverSpec;
  OL_Context::Rule* _currentRule;
  ElementSpecPtr _ccTx, _ccRx, _roundRobinCC;
  bool _isPeriodic;
  int _currentPositionIndex;
  
  // Relational -> P2 elements
  void processRule(OL_Context::Rule *r, string nodeID);
  
  void genJoinElements(OL_Context::Rule* curRule, 
		       string nodeID,
		       FieldNamesTracker* namesTracker,
		       boost::shared_ptr<Aggwrap> agg_el);

  void genProbeElements(OL_Context::Rule* curRule, 
			Parse_Functor* eventFunctor, 
			Parse_Term* baseTerm, 
			string nodeID, 	     
			FieldNamesTracker* probeNames, 
			FieldNamesTracker* baseProbeNames, 
			int joinOrder,
			b_cbv comp_cb);

  void genProjectHeadElements(OL_Context::Rule* curRule,
 			      string nodeID,
 			      FieldNamesTracker* curNamesTracker);
    
  void genAllSelectionAssignmentElements(OL_Context::Rule* curRule,
					 string nodeID,
					 FieldNamesTracker* curNamesTracker);
    
  void genDupElimElement(string header);
  
  void genSingleTermElement(OL_Context::Rule* curRule, 
			    string nodeID, 
			    FieldNamesTracker* namesTracker);
  
  void genSingleAggregateElements(OL_Context::Rule* curRule, 
				  string nodeID, 
				  FieldNamesTracker* curNamesTracker);  


  // Debug elements
  void genPrintElement(string header);

  void genPrintWatchElement(string header);

  void genFunctorSource(OL_Context::Rule* rule, 
			string nodeID,
			FieldNamesTracker* namesTracker);
 
  // Network elements
  ElementSpecPtr genSendElements(boost::shared_ptr< Udp> udp, string nodeID);

  void genReceiveElements(boost::shared_ptr< Udp> udp, 
			  string nodeID, 
			  ElementSpecPtr wrapAroundDemux);

  void registerReceiverTable(OL_Context::Rule* rule, 
			     string tableName);

  void registerReceiver(string tableName, 
			ElementSpecPtr elementSpecPtr,
			OL_Context::Rule* curRule,
			string nodeid);



  // Pel Generation functions
  string pelRange(FieldNamesTracker* names, 
		  Parse_Bool *expr,
		  OL_Context::Rule* rule);		  

  string pelMath(FieldNamesTracker* names, 
		 Parse_Math *expr,
		 OL_Context::Rule* rule);

  string pelBool(FieldNamesTracker* names, 
		 Parse_Bool *expr,
		 OL_Context::Rule* rule);

  string pelFunction(FieldNamesTracker* names, 
		     Parse_Function *expr,
		     OL_Context::Rule* rule);

  void pelSelect(OL_Context::Rule* rule, 
		 FieldNamesTracker *names, 
		 Parse_Select *expr, 
                 string nodeID, 
		 int selectionID);

  void pelAssign(OL_Context::Rule* rule, 
		 FieldNamesTracker *names, 
		 Parse_Assign *expr, 
                 string nodeID, 
		 int assignID);

  // Other helper functions
  void hookUp(ElementSpecPtr firstElement, 
	      int firstPort,
	      ElementSpecPtr secondElement, 
	      int secondPort);  

  void hookUp(ElementSpecPtr secondElement, 
	      int secondPort);  
  
  void addMultTableIndex(TablePtr table, 
			 int fn, 
			 string nodeID);
  
  int numFunctors(OL_Context::Rule* rule);

  bool hasEventTerm(OL_Context::Rule* rule);
  
  Parse_Functor* getEventTerm(OL_Context::Rule* curRule);

  bool hasPeriodicTerm(OL_Context::Rule* curRule);

  void debugRule(OL_Context::Rule* curRule, 
		 string debugMsg) { 
    warn << "Planner debug rule (" << curRule->ruleID<< "): " << debugMsg; 
  }

  // Debugging stuff starts here **
  void createLoggingTable(string);

  // Debugging stuff ends here **

  void error(string msg);
  void error(string msg, OL_Context::Rule* rule);
  void checkFunctor(Parse_Functor* baseFunctor, OL_Context::Rule* rule);

  // convince placeholder to figure out the cur fields in a tuple in flight
  class FieldNamesTracker {
  public:
    std::vector<string> fieldNames;    
    FieldNamesTracker();   
    FieldNamesTracker(Parse_Term* pf);

    void initialize(Parse_Term* pf);
    std::vector<int> matchingJoinKeys(std::vector<string> names);    
    void mergeWith(std::vector<string> names);
    void mergeWith(std::vector<string> names, int numJoinKeys);
    int fieldPosition(string var);
    string toString();
  };

  
  // keep track of where joins need to be performed
  struct JoinKey {
    string _firstTableName;
    string _firstFieldName;
    string _secondTableName;
    string _secondFieldName;   

    JoinKey(string firstTableName, string firstFieldName, string secondTableName, string secondFieldName) {
      _firstTableName = firstTableName;
      _firstFieldName = firstFieldName;
      _secondTableName = secondTableName;
      _secondFieldName = secondFieldName;
    }
  };

  struct ReceiverInfo {
    std::vector<ElementSpecPtr> _receivers;
    string _name;
    u_int _arity;
    ReceiverInfo(string name, u_int arity) {
      _name = name;
      _arity = arity;
    }      
    void addReceiver(ElementSpecPtr elementSpecPtr) { 
      _receivers.push_back(elementSpecPtr);
    }
  };

  // Debugging related functions here
  // different parameters to set are:
  // 1. start or end of rule
  // 2. ruleId
  // 3. ruleNum
  // 4. nodeId

  void instrumentElement(ElementPtr, int, string, string, int);
};

#endif /* __OL_RTR_CONFGEN_H_ */
