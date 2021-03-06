package bfs.telemetry;

import jol.core.JolSystem;
import jol.core.Runtime;
import jol.types.basic.BasicTupleSet;
import jol.types.basic.Tuple;
import jol.types.basic.TupleSet;
import jol.types.exception.JolRuntimeException;
import jol.types.exception.UpdateException;
import jol.types.table.Table;
import jol.types.table.TableName;
import jol.types.table.Table.Callback;

import bfs.Conf;

public class Telemetry {
    public static void main(String[] args) throws JolRuntimeException, UpdateException {
    	if (args.length != 1)
    		usage();

		JolSystem system = Runtime.create(Runtime.DEBUG_ALL, System.err, 12345);
        Telemetry dn = new Telemetry(system);
        dn.startSink(Conf.getLogSink());
		system.start();
    }

    private static void usage() {
        System.err.println("Usage: bfs.Telemetry dir_root");
        System.exit(1);
    }

    private JolSystem system;
	private String sink;
	private String identifier;

    public Telemetry(JolSystem s) {
		this.system = s;
		this.sink = sink;
		this.identifier = identifier;
    }


    public void startSink(String sink) throws JolRuntimeException, UpdateException {

        Callback copyCallback = new Callback() {
            @Override
            public void deletion(TupleSet tuples) {}

            @Override
            public void insertion(TupleSet tuples) {
                for (Tuple t : tuples) {
                    System.out.println("YO\n");
                    //Integer chunkId = (Integer) t.value(2);
                }
            }
        };

        
        this.system.install("telemetry", ClassLoader.getSystemResource("telemetry/telemetry.olg"));

        this.system.evaluate();
        TableName tblName = new TableName("telemetry", "identity");
        TupleSet ident = new BasicTupleSet();
		ident.add(new Tuple("non-leaf", sink));
        this.system.schedule("telemetry", tblName, ident, null);
        this.system.evaluate();

        Table table = this.system.catalog().table(new TableName("telemetry", "messages"));
        table.register(copyCallback);
	}

	public void startSource(String sink, String identifier) throws JolRuntimeException {

        this.system.install("telemetry", ClassLoader.getSystemResource("telemetry/telemetry.olg"));
        this.system.evaluate();

        /* Identify the data directory */
        TableName tblName = new TableName("telemetry", "identity");
        TupleSet ident = new BasicTupleSet();
		ident.add(new Tuple(identifier, sink));
        this.system.schedule("telemetry", tblName, ident, null);

    }

    public void shutdown() {
        this.system.shutdown();
    }

}
