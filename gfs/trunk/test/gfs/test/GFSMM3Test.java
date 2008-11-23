package gfs.test;

import gfs.test.TestCommon;

import org.junit.Test;

public class GFSMM3Test extends TestCommon {

  @Test
  public void test3() {
    try { 
      startMany("5500","5502","5503");
      
      shellCreate("foo");
      /* this time, kill the primary */
      this.masters.get(0).stop();

      /* these ops should timeout to the secondary but eventually work */
      shellCreate("bar");
      shellCreate("bas");

      assertTrue(shellLs("foo","bar","bas"));
  
      java.lang.System.out.println("OK, good then\n");
      shutdown();

    } catch (Exception e) {
      java.lang.System.out.println("something went wrong: "+e);
      java.lang.System.exit(1);
    }
  }


  public static void main(String[] args) throws Exception {
    GFSMM3Test t = new GFSMM3Test();
    t.test3();
  }

}
