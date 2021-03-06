// -*- c-basic-offset: 2; related-file-name: "element.C" -*-
/*
 * This file is distributed under the terms in the attached INTEL-LICENSE file.
 * If you do not find these files, copies can be found by writing to:
 * Intel Research Berkeley, 2150 Shattuck Avenue, Suite 1300,
 * Berkeley, CA, 94704.  Attention:  Intel License Inquiry.
 * 
 */

#ifndef __SimpleNetSim_H__
#define __SimpleNetSim_H__

#include <sys/time.h>
#include <deque>
#include "element.h"

class SimpleNetSim : public Element {
public:

  SimpleNetSim(str name="SimpleNetSim", uint32_t min_delay=0, uint32_t max_delay=0, double drop_prob=0.);
  const char *class_name() const	{ return "SimpleNetSim";};
  const char *processing() const	{ return "l/l"; };
  const char *flow_code() const		{ return "x/x"; };

  TuplePtr pull(int port, cbv cb);

  void tuple_ready(TupleRef t);

private:
  cbv _out_cb;

  const uint32_t min_delay_; 	// minimum delay in ms
  const uint32_t max_delay_; 	// maximum delay in ms
  const double   drop_prob_;	// fraction of packets dropped

  std::deque <TupleRef> ready_q_;
};

#endif /* __SimpleNetSim_H_ */
