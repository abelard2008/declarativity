// -*- c-basic-offset: 2; related-file-name: "elementSpec.h" -*-
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
 */

#include "elementSpec.h"

ElementSpec::ElementSpec(ElementPtr e)
  : _element(e),
    _inputs(),
    _outputs()
{
  initializePorts();
}

std::vector< ElementSpec::UniGroupPtr > ElementSpec::_scratchUniGroups =
std::vector< ElementSpec::UniGroupPtr >(256);


/**
 * For flow code initialization, for every new number, create a new
 * unification link, adding input/output ports as necessary.
 *
 * xyxz/zy UniLink({0,2},{}), UniLink({1},{1}), UniLink({3},{0})
 */
void ElementSpec::initializePorts()
{
  // Empty out the set of unification structures
  _scratchUniGroups.clear();

  // Start an iterator through the personality and flow specs
  const char * personalityPointer = _element->processing();
  const char * flowPointer = _element->flow_code();

  // Create the ports from the appropriate specs
  //////////////////////////////////////////////

  // Should I be advancing the pointers?
  bool pStop;
  bool fStop;

  // What is the default personality 
  Element::Processing pCurrent = Element::AGNOSTIC;
  
  // What is the default flow code?  Dash for no association.
  char fCurrent = Element::NO_FLOW_ASSOCIATION;

  // Fill in the input port vector
  int ninputs = _element->ninputs();
  for (int i = 0;
       i < ninputs;
       i++) {
    // Is this the end of the input descriptors?
    pStop = (*personalityPointer == '/');
    fStop = (*flowPointer == '/');

    // Should I change the current personality?
    if (!pStop) {
      pCurrent = (Element::Processing) *personalityPointer;
      personalityPointer++;
    }

    // Should I change the current flow code?
    if (!fStop) {
      fCurrent = *flowPointer;
      flowPointer++;
    }

    // Create the port
    PortPtr port(new Port(pCurrent));
    _inputs.push_back(port);

    // Create or update the unification group
    if (fCurrent != Element::NO_FLOW_ASSOCIATION) {
      // Do we already have a unification group for this one?
      UniGroupPtr u = _scratchUniGroups[fCurrent]; 
      if (u == 0) {
        // Create a new one
        u.reset(new UniGroup());
        _scratchUniGroups[fCurrent] = u;
      }
      // Add the input into the group
      u->inputs.push_back(i);
      port->uniGroup(u);
    }
  }

  // I have all input ports set.  Proceed with the outputs

  // Skip the slashes.  There'd better be some
  assert((personalityPointer != 0) &&
         (flowPointer != 0) &&
         (*personalityPointer == '/') &&
         (*flowPointer == '/'));
  personalityPointer++;
  flowPointer++;

  // And start with defaults again
  pCurrent = Element::AGNOSTIC;
  fCurrent = Element::NO_FLOW_ASSOCIATION;

  // Fill in the output port vector
  int noutputs = _element->noutputs();
  for (int i = 0;
       i < noutputs;
       i++) {
    // Is this the end of the input descriptors?
    pStop = (*personalityPointer == 0);
    fStop = (*flowPointer == 0);

    // Should I change the current personality?
    if (!pStop) {
      pCurrent = (Element::Processing) (*personalityPointer);
      personalityPointer++;
    }

    // Should I change the current flow code?
    if (!fStop) {
      fCurrent = *flowPointer;
      flowPointer++;
    }

    // Create the port
    PortPtr port(new Port(pCurrent));
    _outputs.push_back(port);

    // Create or update the unification group
    if (fCurrent != Element::NO_FLOW_ASSOCIATION) {
      // Do we already have a unification group for this one?
      UniGroupPtr u = _scratchUniGroups[fCurrent]; 
      if (u == 0) {
        // Create a new one
        u.reset(new UniGroup());
        _scratchUniGroups[fCurrent] = u;
      }
      // Add the input into the group
      u->outputs.push_back(i);
      port->uniGroup(u);
    }
  }

  // Now migrate all unigroups out of the scratch space and into this
  // element spec
  for (int i = 0;
       i < 256;
       i++) {
    if (_scratchUniGroups[i] != 0) {
      _uniGroups.push_back(_scratchUniGroups[i]);
      _scratchUniGroups[i].reset();
    }
  }
}

const ElementPtr ElementSpec::element()
{
  return _element;
}

ElementSpec::PortPtr ElementSpec::input(int pno)
{
  return _inputs[pno];
}

ElementSpec::PortPtr ElementSpec::output(int pno)
{
  return _outputs[pno];
}

ElementSpec::Port::Port(Element::Processing personality)
  : _processing(personality)
{
  assert(ElementSpec::processingCodeString(_processing)[0] != 'I');
}
 
Element::Processing ElementSpec::Port::personality() const
{
  return _processing;
}

void ElementSpec::Port::personality(Element::Processing p)
{
  _processing = p;
  assert(ElementSpec::processingCodeString(_processing)[0] != 'I');
}


ElementSpec::UniGroupPtr ElementSpec::Port::uniGroup() const
{
  return _uniGroup;
}

void ElementSpec::Port::uniGroup(UniGroupPtr u)
{
  assert(_uniGroup == 0);
  _uniGroup = u;
}

string ElementSpec::toString() const
{
  ostringstream sb;
  sb << "<" << _element->class_name() << "(" << _element->name()
     << "/" << _element->ID() << "):";
  int ninputs = _inputs.size();
  if (ninputs > 0) {
    sb << "IN[ " ;
    for (int i = 0;
         i < ninputs;
         i++) {
      sb << i << "/" << (_inputs[i]->toString()) << " ";
    }
    sb << "] ";
  }
  int noutputs = _outputs.size();
  if (noutputs > 0) {
    sb << "OUT[ " ;
    for (int i = 0;
         i < noutputs;
         i++) {
      sb << i << "/" << (_outputs[i]->toString()) << " ";
    }
    sb << "]";
  }
  sb << ">";
  return sb.str();
}

string ElementSpec::Port::toString() const
{
  ostringstream sb;
  sb << "{" << ElementSpec::processingCodeString(_processing) << "}";
  return sb.str();
}

ElementSpec::UnificationResult ElementSpec::Port::unify(Element::Processing p)
{
  if (_processing == Element::AGNOSTIC) {
    _processing = p;
    return PROGRESS;
  } else if (_processing == p) {
    return UNCHANGED;
  } else {
    return CONFLICT;
  }
}

ElementSpec::UnificationResult ElementSpec::unifyInput(int portNumber)
{
  assert(portNumber < _element->ninputs());
  UnificationResult entireResult = UNCHANGED;
  ElementSpec::PortPtr port = _inputs[portNumber];
  Element::Processing personality = port->personality();
  if (personality != Element::AGNOSTIC) {
    // This port's unification group
    UniGroupPtr u = port->uniGroup();
    // Only unify if there's a unification group associated
    if (u != 0) {
      // First other inputs
      for (int i = 0;
           i < portNumber;
           i++) {
        ElementSpec::PortPtr otherPort = _inputs[u->inputs[i]];
        UnificationResult result = otherPort->unify(personality);
        if (result == CONFLICT) {
          return CONFLICT;
        } else if (result == PROGRESS) {
          entireResult = PROGRESS;
        }
      }
      for (uint i = portNumber + 1;
           i < u->inputs.size();
           i++) {
        ElementSpec::PortPtr otherPort = _inputs[u->inputs[i]];
        UnificationResult result = otherPort->unify(personality);
        if (result == CONFLICT) {
          return CONFLICT;
        } else if (result == PROGRESS) {
          entireResult = PROGRESS;
        }
      }

      // Then the outputs
      for (uint i = 0;
           i < u->outputs.size();
           i++) {
        ElementSpec::PortPtr otherPort = _outputs[u->outputs[i]];
        UnificationResult result = otherPort->unify(personality);
        if (result == CONFLICT) {
          return CONFLICT;
        } else if (result == PROGRESS) {
          entireResult = PROGRESS;
        }
      }
    }
  }
  return entireResult;
}

ElementSpec::UnificationResult ElementSpec::unifyOutput(int portNumber)
{
  assert(portNumber < _element->noutputs());
  UnificationResult entireResult = UNCHANGED;
  ElementSpec::PortPtr port = _outputs[portNumber];
  Element::Processing personality = port->personality();
  if (personality != Element::AGNOSTIC) {
    // This port's unification group
    UniGroupPtr u = port->uniGroup();
    // Only unify if there's a unification group associated
    if (u != 0) {
      // First other outputs
      for (int i = 0;
           i < portNumber;
           i++) {
        ElementSpec::PortPtr otherPort = _outputs[u->outputs[i]];
        UnificationResult result = otherPort->unify(personality);
        if (result == CONFLICT) {
          return CONFLICT;
        } else if (result == PROGRESS) {
          entireResult = PROGRESS;
        }
      }
      for (uint i = portNumber + 1;
           i < u->outputs.size();
           i++) {
        ElementSpec::PortPtr otherPort = _outputs[u->outputs[i]];
        UnificationResult result = otherPort->unify(personality);
        if (result == CONFLICT) {
          return CONFLICT;
        } else if (result == PROGRESS) {
          entireResult = PROGRESS;
        }
      }

      // Then the inputs
      for (uint i = 0;
           i < u->inputs.size();
           i++) {
        ElementSpec::PortPtr otherPort = _inputs[u->inputs[i]];
        UnificationResult result = otherPort->unify(personality);
        if (result == CONFLICT) {
          return CONFLICT;
        } else if (result == PROGRESS) {
          entireResult = PROGRESS;
        }
      }
    }
  }
  return entireResult;
}

ElementPtr ElementSpec::Port::counterpart() const
{
  return _counterpart;
}

int ElementSpec::Port::counterpart(ElementPtr element)
{
  if (_counterpart == 0) {
    _counterpart = element;
    return 0;
  } else {
    return 1;
  }
}


