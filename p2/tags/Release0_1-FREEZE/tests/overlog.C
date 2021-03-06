// -*- c-basic-offset: 2; related-file-name: "" -*-
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
 * DESCRIPTION: Test harness for datalog....
 *
 */

#include <iostream>
#include <fstream>
#include <string>
#include <time.h>

#include "ol_lexer.h"
#include "ol_context.h"
#include "plmb_confgen.h"
#include "udp.h"
#include "dot.h"

extern int ol_parser_debug;

//class boost::c_regex_traits<char>;

int main(int argc, char **argv)
{
  std::cout << "OVERLOG\n";
  boost::shared_ptr< OL_Context > ctxt(new OL_Context());
  bool route = false;
  bool builtin = false;
  string filename("");

  for( int i=1; i<argc; i++) { 
    std::string arg(argv[i]);
    
    if (arg == "-d") { 
      ol_parser_debug = 1;
    } else if (arg == "-h") { 
      std::cerr << "Usage: " << argv[0] << "{option|filename}+\n"
		<< "\t-c: canonical form (used for builtin tests)\n"
		<< "\t-d: turn on parser debugging\n"
		<< "\t-r: try to instantiate a plumber config\n"
                << "\t-g: produce a DOT graph spec\n"
		<< "\t-h: print this help text\n"
		<< "\t- : read from stdin\n";
      exit(0);
    } else if (arg == "-c") { 
      builtin = true;
    } else if (arg == "-r") {
      route = true;
      builtin = false;
    } else if (arg == "-") {
      ctxt->parse_stream(&std::cin);
    } else if (arg == "-g") {
      filename = argv[i+1];
      std::ifstream istr(filename.c_str());
      std::ofstream ostr("overlog.dot");
      ctxt->parse_stream(&istr);
      Plumber::ConfigurationPtr conf(new Plumber::Configuration());
      Plmb_ConfGen gen(ctxt.get(), conf, false, false, true, filename);
      gen.createTables("127.0.0.1:10000");
      
      boost::shared_ptr< Udp > udp(new Udp("Udp", 10000));
      gen.configurePlumber(udp, "127.0.0.1:10000");
      toDot(&ostr, conf);
      exit (0);
    } else { 
      filename = argv[i];
      std::ifstream istr(argv[i]);
      ctxt->parse_stream(&istr);
    }
  }

  if (builtin) {
    std::cerr << ctxt->toString();
    std::cerr.flush();
    std::cout << "OK\n";
    std::cout.flush();
  } 

  std::cout << "Finish parsing (functors / tableInfos) " << ctxt->getRules()->size() 
	    << " " << ctxt->getTableInfos()->size() << "\n";

  std::cout << ctxt->toString() << "\n";


  if (route) {
    // test a configuration of a plumber
    Plumber::ConfigurationPtr conf(new Plumber::Configuration());
    Plmb_ConfGen gen(ctxt.get(), conf, false, false, true, filename);
    gen.createTables("127.0.0.1:10000");
    
    boost::shared_ptr< Udp > udp(new Udp("Udp", 10000));
    gen.configurePlumber(udp, "127.0.0.1:10000");
    
    LoggerI::Level level = LoggerI::NONE;
    PlumberPtr plumber(new Plumber(conf, level));
    if (plumber->initialize(plumber) == 0) {
      std::cout << "Correctly initialized network of reachability flows.\n";
    } else {
      std::cout << "** Failed to initialize correct spec\n";
    }
  } 

  std::cout << "Done.\n";

  return 0;
}
