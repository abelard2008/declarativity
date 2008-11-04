package gfs;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.SynchronousQueue;
import java.util.Random;

import jol.core.Runtime;
import jol.core.System;
import jol.types.basic.Tuple;
import jol.types.basic.TupleSet;
import jol.types.basic.ValueList;
import jol.types.exception.JolRuntimeException;
import jol.types.exception.UpdateException;
import jol.types.table.Table;
import jol.types.table.TableName;
import jol.types.table.Table.Callback;

public class Shell {
    private System system;
    private String selfAddress;
    private Random rand;
    private SynchronousQueue responseQueue;

    /*
     * TODO:
     *  (1) connect to an instance of JOL
     *  (2) parse command-line argument into command + arguments
     *  (3) inject the appropriate inserts into JOL; wait for the results to come back
     *  (4) return results to stdout
     */
    public static void main(String[] args) throws Exception {
        Shell shell = new Shell();
        List<String> argList = new LinkedList<String>(Arrays.asList(args));

        if (argList.size() == 0)
            shell.usage();

        String op = argList.remove(0);
        if (op.equals("cat"))
            shell.doConcatenate(argList);
        else if (op.equals("create"))
            shell.doCreateFile(argList);
        else if (op.equals("ls"))
            shell.doListDirs(argList);
        else if (op.equals("mkdir"))
            shell.doCreateDir(argList);
        else
            shell.usage();

        shell.shutdown();
    }

    Shell() throws JolRuntimeException, UpdateException {
        this.rand = new Random();
        this.responseQueue = new SynchronousQueue();

        this.system = Runtime.create(5501);
        this.system.catalog().register(new MasterRequestTable((Runtime) this.system));
        this.system.catalog().register(new SelfTable((Runtime) this.system));

        this.system.install("gfs", ClassLoader.getSystemResource("gfs/gfs.olg"));
        this.system.evaluate();

        /* Identify which address the local node is at */
        this.selfAddress = "tcp:localhost:5501";
        TupleSet self = new TupleSet();
        self.add(new Tuple(this.selfAddress));
        this.system.schedule("gfs", SelfTable.TABLENAME, self, null);
        this.system.evaluate();
        this.system.start();
    }

    /*
     * XXX: consider parallel evaluation
     */
    private void doConcatenate(List<String> args) throws UpdateException, InterruptedException, JolRuntimeException {
        if (args.size() == 0)
            usage();

        for (String file : args)
            doCatFile(file);
    }

    private void doCatFile(String file) throws UpdateException, InterruptedException, JolRuntimeException {
        final int requestId = generateId();

        // Register callback to listen for responses
        Callback responseCallback = new Callback() {
            @Override
            public void deletion(TupleSet tuples) {}

            @Override
            public void insertion(TupleSet tuples) {
                for (Tuple t : tuples) {
                    Integer tupRequestId = (Integer) t.value(1);

                    if (tupRequestId.intValue() == requestId) {
                        Object fileContent = t.value(2);
                        try {
                            responseQueue.put(fileContent);
                            break;
                        }
                        catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
            }
        };
        Table responseTbl = this.system.catalog().table(new TableName("gfs", "cat_response"));
        responseTbl.register(responseCallback);

        // Create and insert the request tuple
        TableName tblName = new TableName("gfs", "cat_request");
        TupleSet req = new TupleSet(tblName);
        req.add(new Tuple(this.selfAddress, requestId, file));
        this.system.schedule("gfs", tblName, req, null);

        // Wait for the response
        String content = (String) this.responseQueue.take();
        responseTbl.unregister(responseCallback);

        java.lang.System.out.println("File name: " + file);
        java.lang.System.out.println("Content: " + content);
        java.lang.System.out.println("=============");
    }

    private int generateId() {
        return rand.nextInt();
    }

    private void doCreateDir(List<String> args) {
        ;
    }

    private void doCreateFile(List<String> args) {
        ;
    }

    private void doListDirs(List<String> args) throws UpdateException, InterruptedException, JolRuntimeException {
        if (args.size() != 0)
            usage();

        final int requestId = generateId();

        // Register a callback to listen for responses
        Callback responseCallback = new Callback() {
                @Override
                public void deletion(TupleSet tuples) {}

                @Override
                    public void insertion(TupleSet tuples) {
                    for (Tuple t : tuples) {
                        Integer tupRequestId = (Integer) t.value(1);

                        if (tupRequestId.intValue() == requestId) {
                            Object lsContent = t.value(2);
                            try {
                                responseQueue.put(lsContent);
                                break;
                            } catch (InterruptedException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    }
                }
            };

        Table responseTbl = this.system.catalog().table(new TableName("gfs", "ls_response"));
        responseTbl.register(responseCallback);

        // Create and insert the request tuple
        TableName tblName = new TableName("gfs", "ls_request");
        TupleSet req = new TupleSet(tblName);
        req.add(new Tuple(this.selfAddress, requestId));
        this.system.schedule("gfs", tblName, req, null);

        ValueList lsContent = (ValueList) this.responseQueue.take();
        responseTbl.unregister(responseCallback);

        java.lang.System.out.println("ls:");
        int i = 1;
        for (Object o : lsContent) {
            java.lang.System.out.println("  " + i + ". " + o.toString());
            i++;
        }
    }

    private void shutdown() {
        this.system.shutdown();
    }

    private void usage() {
        java.lang.System.err.println("Usage: java gfs.Shell op_name args");
        java.lang.System.err.println("Where op_name = {cat,create,ls,mkdir}");

        shutdown();
        java.lang.System.exit(0);
    }
}
