
/*
 * @(#)$Id$
 *
 *
 * This file is distributed under the terms in the attached LICENSE file.
 * If you do not find this file, copies can be found by writing to:
 * Intel Research Berkeley, 2150 Shattuck Avenue, Suite 1300,
 * Berkeley, CA, 94704.  Attention:  Intel License Inquiry.
 * Or
 * UC Berkeley EECS Computer Science Division, 387 Soda Hall #1776, 
 * Berkeley, CA,  94707. Attention: P2 Group.
 * 
 *
 */

#if HAVE_CONFIG_H
#include <config.h>
#endif /* HAVE_CONFIG_H */
#include <iostream>

#include "tuple.h"
#include "queue.h"
#include "print.h"
#include "timedPushSource.h"
#include "timedPullSink.h"
#include "plumber.h"
#include "slot.h"
#include "loop.h"

/** Test queue */

void testQueue()
{
  std::cout << "\n[Test Queue]\n";
  
  PlumberPtr plumber(new Plumber());
  Plumber::DataflowPtr conf(new Plumber::Dataflow("test"));
  
  ElementSpecPtr timedPushSourceSpec = conf->addElement(ElementPtr(new TimedPushSource("source", 1)));
  ElementSpecPtr sourcePrintS = conf->addElement(ElementPtr(new Print("AfterSource")));
  ElementSpecPtr queueSpec = conf->addElement(ElementPtr(new Queue("queue", 5)));
  ElementSpecPtr sinkPrintS = conf->addElement(ElementPtr(new Print("BeforeSink")));
  ElementSpecPtr sinkS = conf->addElement(ElementPtr(new TimedPullSink("sink", 2)));
  
  conf->hookUp(timedPushSourceSpec, 0, sourcePrintS ,0);
  conf->hookUp(sourcePrintS, 0, queueSpec, 0);
  conf->hookUp(queueSpec, 0, sinkPrintS, 0);
  conf->hookUp(sinkPrintS, 0, sinkS, 0);
  
  if (plumber->install(conf) == 0) {
    std::cout << "Correctly initialized configuration.\n";
  } else {
    std::cout << "** Failed to initialize correct spec\n";
  }
  
}

int main(int argc, char **argv)
{
  std::cout << "\nTest Queue Start\n";
  
  eventLoopInitialize();
  testQueue();
  eventLoop();
  std::cout << "\nTest Queue End\n";
  return 0;
}
  

