#Licensed to the Apache Software Foundation (ASF) under one
#or more contributor license agreements.  See the NOTICE file
#distributed with this work for additional information
#regarding copyright ownership.  The ASF licenses this file
#to you under the Apache License, Version 2.0 (the
#"License"); you may not use this file except in compliance
#with the License.  You may obtain a copy of the License at

#     http://www.apache.org/licenses/LICENSE-2.0

#Unless required by applicable law or agreed to in writing, software
#distributed under the License is distributed on an "AS IS" BASIS,
#WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#See the License for the specific language governing permissions and
#limitations under the License.
import unittest, os, sys, re, threading, time

import logging

myDirectory    = os.path.realpath(sys.argv[0])
rootDirectory   = re.sub("/testing/.*", "", myDirectory)

sys.path.append(rootDirectory)

from testing.lib import BaseTestSuite

excludes = ['test_MINITEST1', 'test_MINITEST2']
 
from hodlib.GridServices import *
from hodlib.Common.desc import ServiceDesc
from hodlib.RingMaster.ringMaster import _LogMasterSources

# All test-case classes should have the naming convention test_.*
class test_MINITEST1(unittest.TestCase):
  def setUp(self):
    pass

  # All testMethods have to have their names start with 'test'
  def testSuccess(self):
    pass
    
  def testFailure(self):
    pass

  def tearDown(self):
    pass

class test_MINITEST2(unittest.TestCase):
  def setUp(self):
    pass

  # All testMethods have to have their names start with 'test'
  def testSuccess(self):
    pass
    
  def testFailure(self):
    pass

  def tearDown(self):
    pass

class test_GetCommand(unittest.TestCase):
  def setUp(self):
    self.config = {
       'hod': {}, 
      'resource_manager': {
                            'id': 'torque', 
                            'batch-home': '/home/y/'
                          }, 
       'ringmaster': {
                      'max-connect' : 2,
                      'max-master-failures' : 5
                     }, 
       'hodring': {
                  }, 
       'gridservice-mapred': { 
                              'id': 'mapred' 
                             } ,
       'gridservice-hdfs': { 
                              'id': 'hdfs' 
                            }, 
       'servicedesc' : {} ,
       'nodepooldesc': {} , 
       }

    hdfsDesc = self.config['servicedesc']['hdfs'] = ServiceDesc(self.config['gridservice-hdfs'])
    mrDesc = self.config['servicedesc']['mapred'] = ServiceDesc(self.config['gridservice-mapred'])

    # API : serviceObj = service(desc, workDirs, reqNodes, version)
    self.hdfs = Hdfs(hdfsDesc, [], 0, 17)
    self.hdfsExternal = HdfsExternal(hdfsDesc, [], 17)
    self.mr = MapReduce(mrDesc, [],1, 17)
    self.mrExternal = MapReduceExternal(mrDesc, [], 17)
    
    self.log = logging.getLogger()
    pass

  # All testMethods have to have their names start with 'test'
  def testBothInternal(self):
    self.serviceDict = {}
    self.serviceDict[self.hdfs.getName()] = self.hdfs
    self.serviceDict[self.mr.getName()] = self.mr
    self.rpcSet = _LogMasterSources(self.serviceDict, self.config, None, self.log, None)

    cmdList = self.rpcSet.getCommand('localhost')
    self.assertEquals(cmdList.__len__(), 2)
    self.assertEquals(cmdList[0].dict['argv'][0], 'namenode')
    self.assertEquals(cmdList[1].dict['argv'][0], 'namenode')
    pass
    
  def tearDown(self):
    pass

class RingmasterRPCsTestSuite(BaseTestSuite):
  def __init__(self):
    # suite setup
    BaseTestSuite.__init__(self, __name__, excludes)
    pass
  
  def cleanUp(self):
    # suite tearDown
    pass

def RunRingmasterRPCsTests():
  # modulename_suite
  suite = RingmasterRPCsTestSuite()
  testResult = suite.runTests()
  suite.cleanUp() 
  return testResult

if __name__ == "__main__":
  RunRingmasterRPCsTests()
