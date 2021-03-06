package bfs;

import jol.core.JolSystem;
import jol.types.basic.Tuple;
import jol.types.basic.TupleSet;
import jol.types.exception.JolRuntimeException;
import jol.types.exception.UpdateException;
import jol.types.table.Table;
import jol.types.table.TableName;
import jol.types.table.Table.Callback;

public class OlgAssertion {
	OlgAssertion(JolSystem s, boolean enable) throws UpdateException,
			JolRuntimeException {
		// Register a callback to listen for responses
		Callback responseCallback = new Callback() {
			@Override
			public void deletion(TupleSet tuples) {
			}

			@Override
			public void insertion(TupleSet tuples) {
				for (Tuple t : tuples) {
					String message = (String) t.value(1);

					System.out.println("EXCEPTION!\n");
					throw new RuntimeException("assertion failed: " + message);
				}
			}
		};

		s.install("bfs", ClassLoader.getSystemResource("bfs/olgAssertion.olg"));
		s.evaluate();

		if (enable) {
			Table table = s.catalog().table(
					new TableName("olg_assertion", "die"));
			table.register(responseCallback);
		}
	}
}
