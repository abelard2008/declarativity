// -*- c-basic-offset: 2; related-file-name: "element.C" -*-
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
 */

#include <iostream>

#include "groupby.h"
#include "tuple.h"
#include "val_str.h"
#include "math.h"


const int GroupBy::MIN_AGG = 0;
const int GroupBy::MAX_AGG = 1;
const int GroupBy::AVG_AGG = 2;

GroupBy::GroupBy(string name, string newTableName, std::vector<int> primaryFields, std::vector<int> groupByFields, 
		 std::vector<int> aggFields, std::vector<int> aggTypes, 
		 double seconds, bool aggregateSelections)
  : Element(name,1,1), 
    _seconds(seconds),
    _wakeupCB(boost::bind(&GroupBy::wakeup, this)),
    _runTimerCB(boost::bind(&GroupBy::runTimer, this))
{
  _primaryFields = primaryFields;
  _groupByFields = groupByFields;
  _aggFields = aggFields;
  _aggTypes = aggTypes;
  _newTableName = newTableName;
  _aggregateSelections = aggregateSelections;

  _numAdded = 0;

  assert(_aggregateSelections == false || (aggregateSelections == true && aggFields.size() == 1));
}

GroupBy::~GroupBy()
{
}

int GroupBy::initialize()
{
  log(LoggerI::INFO, 0, "initialize");
  // Schedule my timer
  _timeCallback = delayCB(_seconds, _runTimerCB);

  return 0;
}


string GroupBy::getFieldStr(std::vector<int> fields, TuplePtr p)
{
  ostringstream fieldStr;
  for (unsigned int k = 0; k < fields.size(); k++) {
    ValuePtr key = (*p)[fields[k]];
    fieldStr << key->toString();
    if (k != (fields.size() - 1)) {
      fieldStr << ":";
    }
  }
  return fieldStr.str();
}

void GroupBy::recomputeAllAggs()
{

  _aggValues.clear();

  if (_numAdded == 0) { return; }

  // go through all tuples we have seen so far and recompute aggregates stored in aggValues
  // next time, enumerate the store itself
  for (_multiIterator = _tuples.begin(); _multiIterator != _tuples.end(); _multiIterator++) {
    bool changed = false;
    TuplePtr nextTuple = _multiIterator->second;    
    string groupByStr = getFieldStr(_groupByFields, nextTuple);    

    // the initial fields
    TuplePtr newAggTuple = Tuple::mk();
    newAggTuple->append(Val_Str::mk(_newTableName));
    for (unsigned int k = 0; k < _groupByFields.size(); k++) {
      newAggTuple->append((*nextTuple)[_groupByFields[k]]);
    }

    _iterator = _aggValues.find(groupByStr);
    if (_iterator != _aggValues.end()) { // already exist an aggregate value
      TuplePtr currentAgg = _iterator->second;
 
      // check which fields contribute to the "best agg"
      for (unsigned int k = 0; k < _aggFields.size(); k++) {
	ValuePtr origVal = (*currentAgg)[k + 1 + _groupByFields.size()];
	ValuePtr newVal = (*nextTuple)[_aggFields[k]];

	if (_aggTypes[k] == MIN_AGG) {
	  if (newVal->compareTo(origVal) < 0) {
	    // new value is smaller
	    origVal = newVal;
	    changed = true;
	  }
	}
	
	if (_aggTypes[k] == MAX_AGG) {
	  if (newVal->compareTo(origVal) > 0) {
	    // new value is bigger
	  origVal = newVal;
	  changed = true;
	  }
	}
	newAggTuple->append(origVal);
      }    
    } else {
      // the first time we see a tuple for this group
      for (unsigned int k = 0; k < _aggFields.size(); k++) {
	newAggTuple->append((*nextTuple)[_aggFields[k]]);
      }      
      changed = true;
    }
    newAggTuple->freeze();
    _aggValues.erase(groupByStr);
    _aggValues.insert(std::make_pair(groupByStr, newAggTuple));      

    if (changed == true) {
      _bestTuples.erase(groupByStr);
      _bestTuples.insert(std::make_pair(groupByStr, nextTuple));
    } 
  }
  _numAdded = 0;
}


int GroupBy::push(int port, TuplePtr p, b_cbv cb)
{  
  string indexStr = getFieldStr(_primaryFields, p);
  string groupByStr = getFieldStr(_groupByFields, p);

  // store the tuple. Replace by primary key if necessary. 
  for (_multiIterator = _tuples.lower_bound(groupByStr); _multiIterator != _tuples.upper_bound(groupByStr); _multiIterator++) {
    TuplePtr t = _multiIterator->second;    
    if (getFieldStr(_primaryFields, t) == indexStr) {
      // update an existing tuple
      _tuples.erase(_multiIterator);
    }
  }
  _tuples.insert(std::make_pair(groupByStr, p)); // store the tuple
  _numAdded += 1;
  return 1;
}


// periodically, push changes upsteam if possible until no more pending, 
// or upstream unable to accept more tuples
void GroupBy::runTimer()
{
  // remove the timer id
  _timeCallback = 0;

  recomputeAllAggs();

  // Attempt to push it
  for (_iterator = _aggValues.begin(); _iterator != _aggValues.end(); _iterator++) {
    TuplePtr t = _iterator->second;
    string groupByStr = getFieldStr(_groupByFields, t);    

    // check whether this tuple has been sent already previously (suppress to prevent sending redundant)
    TupleMap::iterator previousSent = _lastSentTuples.find(groupByStr);
    if (previousSent != _lastSentTuples.end()) {
      if (previousSent->second->compareTo(t) == 0) {
	continue;
      }
    }

    int result = 0;
    if (_aggregateSelections == false) {
      result = output(0)->push(t, _wakeupCB);
    } else {
      // return the actual tuple itself
      result = output(0)->push(_bestTuples.find(groupByStr)->second, _wakeupCB);
    }

    _lastSentTuples.erase(groupByStr);
    _lastSentTuples.insert(std::make_pair(groupByStr, t)); 

    if (result == 0) {
      // We have been pushed back.  Don't reschedule wakeup
      log(LoggerI::INFO, 0, "runTimer: sleeping");
      return;
    } 
  }

  // Reschedule me into the future
  //log(LoggerI::INFO, 0, "runTimer: rescheduling");
  _timeCallback = delayCB(_seconds,
			  _runTimerCB);  
}

void GroupBy::wakeup()
{
  // I'd better not be already scheduled
  assert(_timeCallback == 0);

  log(LoggerI::INFO, 0, "wakeup");

  // Okey dokey.  Reschedule me into the future
  _timeCallback = delayCB(_seconds,
                          _runTimerCB);
}


// output tuples seen
void GroupBy::dumpTuples(string str)
{
  std::cout << "Key " << str << ": ";
  for (_multiIterator = _tuples.lower_bound(str); _multiIterator != _tuples.upper_bound(str); _multiIterator++) {
    TuplePtr t = _multiIterator->second;    
    std::cout << t->toString() << ", ";
  }
  std::cout << "\n";
}

// output aggs
void GroupBy::dumpAggs(string str)
{
  std::cout << "Grouping Key " << str << ": ";
  for (_iterator = _aggValues.lower_bound(str); _iterator != _aggValues.upper_bound(str); _iterator++) {
    TuplePtr t = _iterator->second;    
    std::cout << t->toString() << "\n";
  }
}
