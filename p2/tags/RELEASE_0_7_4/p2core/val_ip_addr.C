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

#include "val_ip_addr.h"
#include "val_str.h"
#include "math.h"
#include <loggerI.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <arpa/inet.h>
#include <reporting.h>

const opr::Oper* Val_IP_ADDR::oper_ = new opr::OperCompare< Val_IP_ADDR> ();

//
// Marshalling and unmarshallng
//
void Val_IP_ADDR::xdr_marshal_subtype( XDR *x )
{
  exit(-1);
  return;
}

ValuePtr Val_IP_ADDR::xdr_unmarshal( XDR *x )
{
  exit(-1);
  ValuePtr v;
  return v;
}

string Val_IP_ADDR::toConfString() const
{
  ostringstream conf;
  conf << "Val_IP_ADDR(" << _s << ")";
  return conf.str();
}

//
// Casting
//
string Val_IP_ADDR::cast(ValuePtr v) {
  if(v->typeCode() == Value::IP_ADDR){
    return (static_cast<Val_IP_ADDR *>(v.get()))->_s;
  }
  else if(v->typeCode() == Value::STR){
    return Val_Str::cast(v);
  }
  else{
    throw Value::TypeError(v->typeCode(), Value::IP_ADDR );
  }
}

/**
 * Returns the suio constructed from the ip-address string. Also checks if the string
 * is in correct format, i.e. xx.xx.xx.xx:xx. 
 **/
FdbufPtr Val_IP_ADDR::getAddress()
{
  FdbufPtr x(new Fdbuf());
  struct sockaddr_in saddr;

  char * theAtSign = strchr(_s.c_str(), ':');
  
  if (theAtSign == NULL) {
    // Couldn't find the correct format
    TELL_WARN << "The IP Address is not in correct format:" << toString() << "\n";
    exit(-1);
    //return 0;
  }
  
  string theAddress(_s.c_str(), theAtSign - _s.c_str());
  string thePort(theAtSign + 1);
  int port = atoi(thePort.c_str());
  bzero(&saddr, sizeof(saddr));
  saddr.sin_port = htons(port);
  inet_pton(AF_INET, _s.c_str(), &saddr.sin_addr);
  x->push_bytes((char*) &saddr, sizeof(saddr));
  return x;
  
}

int Val_IP_ADDR::compareTo(ValuePtr other) const
{
  if (other->typeCode() != Value::IP_ADDR) {
    if (Value::IP_ADDR < other->typeCode()) {
      return -1;
    } else if (Value::IP_ADDR > other->typeCode()) {
      return 1;
    }
  }
  return toString().compare(other->toString());
}

/*
 * End of file
 */
