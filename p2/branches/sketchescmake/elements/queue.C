// -*- c-basic-offset: 2; related-file-name: "queue.h" -*-
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

#include "queue.h"
#include "tuple.h"
#include<iostream>
#include "loop.h"

Queue::Queue(string name, unsigned int queueSize)
  : Element(name, 1, 1),
    _pullCB(0),
    _pushCB(0)
{
  _size = queueSize;
}

Queue::~Queue()
{
}

/* push. When receive, put to queue. If have pending, wake up.*/
int Queue::push(int port, TuplePtr p, b_cbv cb)
{
  _q.push(p);  
  ostringstream oss;

  oss << "Push " << p->toString() << ", queuesize=" << _q.size();
  log(Reporting::INFO, 0,oss.str());
  if (_pullCB) {
    // is there a pending callback? If so, wake it up
    _pullCB();
    _pullCB = 0;
  } else {
      log(Reporting::INFO, 0, "No pending pull callbacks");
  }

  // have we reached the max size? If so, we have to wait
  if (_q.size() == _size) {
    oss.clear();
    oss << "Queue has reach max size, queuesize=" << _q.size();
    log(Reporting::INFO, 0, oss.str());
    _pushCB = cb;
    return 0;
  }

  return 1;
}


/* pull. When pull, drain the queue. Do nothing if queue is empty but register callback. */
TuplePtr Queue::pull(int port, b_cbv cb)
{
  if (_q.size() == 0) { 
    log(Reporting::INFO, 0, "Queue is empty during pull");
    _pullCB = cb;
    return TuplePtr(); 
  }
  TuplePtr p = _q.front();
  _q.pop();

  if (_pushCB) {
    _pushCB();
    _pushCB = 0;
  }

  ostringstream oss;
  oss << "Pull succeed " << p->toString() << ", queuesize=" << _q.size();
  log(Reporting::INFO, 0, oss.str());
  return p;
}



