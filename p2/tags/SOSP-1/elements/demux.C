// -*- c-basic-offset: 2; related-file-name: "demux.h" -*-
/*
 * @(#)$Id$
 * 
 * This file is distributed under the terms in the attached INTEL-LICENSE file.
 * If you do not find these files, copies can be found by writing to:
 * Intel Research Berkeley, 2150 Shattuck Avenue, Suite 1300,
 * Berkeley, CA, 94704.  Attention:  Intel License Inquiry.
 * 
 */

#include "demux.h"

Demux::Demux(str name,
             ref< vec< ValueRef > > demuxKeys,
             unsigned inputFieldNo)
  : Element(name, 1, demuxKeys->size() + 1),
    _push_cb(cbv_null),
    _demuxKeys(demuxKeys),
    _block_flags(),
    _block_flag_count(0),
    _inputFieldNo(inputFieldNo)
{
  // Clean out the block flags
  _block_flags.zsetsize(noutputs());
}

void Demux::unblock(int output)
{
  assert((output >= 0) &&
         (output <= noutputs()));
  
  // Unset a blocked output
  if (_block_flags[output]) {
    log(LoggerI::INFO, -1, "unblock");

    _block_flags[output] = false;
    _block_flag_count--;
    assert(_block_flag_count >= 0);
  }

  // If I have a push callback, call it and remove it
  if (_push_cb != cbv_null) {
    log(LoggerI::INFO, -1, "unblock: propagating aggregate unblock");
    _push_cb();
    _push_cb = cbv_null;
  }
}

int Demux::push(int port, TupleRef p, cbv cb)
{
  assert(p != 0);
  assert(port == 0);
  assert(p->size() > 0);

  // Can I take more?
  if (_block_flag_count == noutputs()) {
    // Drop it and hold on to the callback if I don't have it already
    if (_push_cb == cbv_null) {
      _push_cb = cb;
    } else {
      log(LoggerI::WARN, -1, "push: Callback overrun");
    }
    log(LoggerI::WARN, -1, "push: Overrun");
    return 0;
  }

  // Extract the first field of the tuple
  ValueRef first = (*p)[_inputFieldNo];

  // XXX Slow version for now.  Use a hash table eventually

  // Which of the demux keys does it match?
  for (int i = 0;
       i < noutputs() - 1;
       i++) {
    ValueRef key = (*_demuxKeys)[i];

    // The match must be exact.  No type conversions allowed.
    if (key->equals(first)) {
      // We found our output. Is it blocked?
      if (_block_flags[i]) {
        // No can do. Drop the tuple and return 0 if all outputs are
        // blocked
        log(LoggerI::WARN, -1, "push: Matched blocked output");

        // Of course, our input is not blocked, or we wouldn't be here,
        // yes?
        assert(_block_flag_count < noutputs());
        return 1;
      } else {
        // Send it with the appropriate callback
        int result = output(i)->push(p, wrap(this, &Demux::unblock, i));

        // If it can take no more
        if (result == 0) {
          // update the flags
          _block_flags[i] = true;
          _block_flag_count++;

          // If I just blocked all of my outputs, push back my input
          if (_block_flag_count == noutputs()) {
            assert(_push_cb == cbv_null);
            _push_cb = cb;
            log(LoggerI::WARN, -1, "push: Blocking input");
            return 0;
          } else {
            // I can still take more
            return 1;
          }
        } else {
          // Not all outputs are blocked so I can keep on truckin'
          return 1;
        }
      }
    }
  }


  // The input matched none of the keys.  Send it to the default output
  // (the last)
  if (_block_flags[noutputs() - 1]) {
    // No can do. Drop the tuple and return 0 if all outputs are
    // blocked
    log(LoggerI::WARN, -1, "push: Default output blocked");
    
    // Of course, our input is not blocked, or we wouldn't be here,
    // yes?
    assert(_block_flag_count < noutputs());
    return 1;
  } else {
    // Send it with the appropriate callback
    int result = output(noutputs() - 1)->push(p, wrap(this, &Demux::unblock, noutputs() - 1));
    
    // If it can take no more
    if (result == 0) {
      // update the flags
      _block_flags[noutputs() - 1] = true;
      _block_flag_count++;
      
      // If I just blocked all of my outputs, push back my input
      if (_block_flag_count == noutputs()) {
        assert(_push_cb == cbv_null);
        _push_cb = cb;
        log(LoggerI::WARN, -1, "push: Blocking input");
        return 0;
      } else {
        // I can still take more
        return 1;
      }
    } else {
      // Not all outputs are blocked so I can keep on truckin'
      return 1;
    }
  }
}

