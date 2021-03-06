// -*- c-basic-offset: 2; related-file-name: "" -*-
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
 * DESCRIPTION: A runner of arbitrary OverLog code.  It only
 * fills in the env table with the local host.
 */

#if HAVE_CONFIG_H
#include <config.h>
#endif /* HAVE_CONFIG_H */
#include <iostream>
#include <stdlib.h>
#include <sys/types.h>
#include <sys/wait.h>

#include "tuple.h"
#include "plumber.h"
#include "val_int32.h"
#include "val_uint32.h"
#include "val_str.h"
#include "val_id.h"

#include "ol_lexer.h"
#include "ol_context.h"
#include "plmb_confgen.h"



bool DEBUG = false;
bool CC = false;


/**
   My usage string
*/
static char * USAGE = "Usage:\n\t runOverLog <overLogFile> <loggingLevel> <seed> <myipaddr:port> <startDelay> <{key=value;}>\n";



/**
 * Fill in the environment table
 */
void
initializeBaseTables(boost::shared_ptr< OL_Context> ctxt,
                     boost::shared_ptr<Plmb_ConfGen> plumberConfigGenerator, 
                     string localAddress,
                     string environment)
{
  // Put in my own address
  TablePtr envTable = plumberConfigGenerator->getTableByName(localAddress, "env");
  TuplePtr tuple = Tuple::mk();
  ValuePtr envName = Val_Str::mk("env");
  tuple->append(envName);
  ValuePtr myAddress = Val_Str::mk(localAddress);
  tuple->append(myAddress);
  tuple->append(Val_Str::mk("hostname"));
  tuple->append(myAddress);
  tuple->freeze();
  envTable->insert(tuple);

  // Now rest of the environment
  warn << "Environment is " << environment << "\n";
  const char * current = environment.c_str();
  while (strlen(current) > 0) {
    // Find a semicolon
    char * theSemi = strchr(current, ';');
    if (theSemi == NULL) {
      fatal << "Malformed environment string. No semicolon\n";
      exit(-1);
    }

    *theSemi = 0;

    // Find an =
    char * theEqual = strchr(current, '=');
    if (theEqual == NULL) {
      fatal << "Malformed environment entry [" << current << "]. No equals sign\n";
      exit(-1);
    }
    
    // Register the part from current to = and from = to semicolon
    *theEqual = 0;
    string attribute(current);
    *theEqual = '=';

    string value(theEqual + 1);
    warn << "[" << attribute << "=" << value << "]\n";

    TuplePtr tuple = Tuple::mk();
    tuple->append(envName);
    tuple->append(myAddress);
    tuple->append(Val_Str::mk(attribute));
    tuple->append(Val_Str::mk(value));
    tuple->freeze();
    envTable->insert(tuple);

    // Move after semicolon
    *theSemi = ';';
    current = theSemi+1;
  }
}



/**
   Put together the dataflow.
*/
void
startOverLogDataflow(LoggerI::Level level,
                     boost::shared_ptr< OL_Context> ctxt,
                     string overLogFile, 
                     string localAddress,
                     int port,
                     double delay,
                     string environment)
{
  eventLoopInitialize();
  // create dataflow for translated OverLog
  Plumber::ConfigurationPtr conf(new Plumber::Configuration());
  boost::shared_ptr< Plmb_ConfGen > 
    plumberConfigGenerator(new Plmb_ConfGen(ctxt.get(), conf, false, DEBUG, CC, overLogFile));

  plumberConfigGenerator->createTables(localAddress);

  boost::shared_ptr< Udp > udp(new Udp(localAddress, port));
  plumberConfigGenerator->clear();
  plumberConfigGenerator->configurePlumber(udp, localAddress);

  initializeBaseTables(ctxt, plumberConfigGenerator, localAddress, environment);
   
  PlumberPtr plumber(new Plumber(conf, level));
  if (plumber->initialize(plumber) == 0) {
    warn << "Correctly initialized network of chord lookup flows.\n";
  } else {
    warn << "** Failed to initialize correct spec\n";
    return;
  }

  // Activate the plumber
  plumber->activate();

  // Run the plumber
  eventLoop();
}


/**
 * Builds a dataflow graph from an OverLog specification
 */
void testOverLog(LoggerI::Level level,
                 string myAddress,
                 int port,    // extracted from myAddress for convenience
                 string filename,
                 double delay,
                 string environment)
{
  boost::shared_ptr< OL_Context > ctxt(new OL_Context());

  string processed(filename+".processed");

  // Run the OverLog through the preprocessor
  pid_t pid = fork();
  if (pid == -1) {
    fatal << "Cannot fork a preprocessor\n";
    exit(-1);
  } else if (pid == 0) {
    // I am the preprocessor
    execlp("cpp", "cpp", "-P", filename.c_str(), processed.c_str(),
           (char*) NULL);

    // If I'm here, I failed
    fatal << "Preprocessor execution failed\n";
    exit(-1);
  } else {
    // I am the child
    wait(NULL);
  }

  // Parse the preprocessed file
  std::ifstream istr(processed.c_str());
  if (!istr.is_open()) {
    // Failed to open the file
    std::cerr << "Could not open file " << filename << "\n";
    exit(-1);
  }
  ctxt->parse_stream(&istr);
  
  startOverLogDataflow(level, ctxt, filename, myAddress, port, delay, environment);
}






/**
 */
int
main(int argc, char **argv)
{
  if (argc < 7) {
    fatal << USAGE;
    exit(-1);
  }

  string overLogFile(argv[1]);
  std::cout << "Running from translated file \"" << overLogFile << "\"\n";

  string levelName(argv[2]);
  LoggerI::Level level = LoggerI::levelFromName[levelName];

  int seed = atoi(argv[3]);
  srandom(seed);
  string myAddress(argv[4]);
  
  const char * theString = argv[4];
  std::cout << theString << "\n";
  char * theColon = strchr(theString, ':');
  if (theColon == NULL) {
    // Couldn't find the correct format
    std::cerr << "Incorrect own address format\n";
    fatal << USAGE;
    exit(-1);
  }
  string thePort(theColon + 1);
  int port = atoi(thePort.c_str());

  // How long should I wait before I begin?
  double delay = atof(argv[5]);

  string environment(argv[6]);

  testOverLog(level,
              myAddress,
              port,
              overLogFile, 
              delay,
              environment);
  return 0;
}

/**
 * End of File
 */
