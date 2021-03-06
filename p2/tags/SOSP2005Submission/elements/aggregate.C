
/*
 * @(#)$Id$
 * 
 * This file is distributed under the terms in the attached INTEL-LICENSE file.
 * If you do not find these files, copies can be found by writing to:
 * Intel Research Berkeley, 2150 Shattuck Avenue, Suite 1300,
 * Berkeley, CA, 94704.  Attention:  Intel License Inquiry.
 *
 */

#include "aggregate.h"

Aggregate::Aggregate(str name,
                     Table::MultAggregate aggregate)
  : Element(name, 0, 1),
    _aggregate(aggregate),
    _latest(NULL),
    _pullCallback(cbv_null),
    _pending(false)
{
  // Place myself as a listener on the aggregate
  _aggregate->addListener(wrap(this, &Aggregate::listener));
}

void
Aggregate::listener(TupleRef t)
{
  if (_latest == NULL) {
    _latest = t;
    _pending = true;
  } else {
    if (_latest->compareTo(t) != 0) {
      // This is fresh and different
      _latest = t;
      _pending = true;
    } else {
      // Same old same old. Do nothing.  Don't reset pending, though, in
      // case the previous update is still pending.
      return;
    }
  }

  // If there's a pull callback, call it
  if (_pullCallback != cbv_null) {
    log(LoggerI::INFO, 0, "listener: wakeup puller");
    _pullCallback();
    _pullCallback = cbv_null;
  }
}

TuplePtr
Aggregate::pull(int port, cbv cb) 
{
  // Is this the right port?
  assert(port == 0);

  // Do I have a pending update?
  if (!_pending) {
    // Nope, no pending update.  Deal with underruns.
    if (_pullCallback == cbv_null) {
      // Accept the callback
      log(LoggerI::INFO, 0, "pull: raincheck");
      _pullCallback = cb;
    } else {
      // I already have a pull callback
      log(LoggerI::INFO, 0, "pull: callback underrun");
    }
    return 0;
  } else {
    // I'd better have no callback pending and definitely a value
    assert(_pullCallback == cbv_null);
    assert(_latest != NULL);

    // No longer pending
    _pending = false;

    // Return the latest
    return _latest;
  }
}
