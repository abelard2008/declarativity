package p2.lang.plan;

import p2.lang.plan.Rule.RuleTable.Field;
import p2.types.basic.Tuple;
import p2.types.basic.TypeList;
import p2.types.exception.UpdateException;
import p2.types.table.HashIndex;
import p2.types.table.Index;
import p2.types.table.Key;
import p2.types.table.ObjectTable;

public class Watch extends Clause {
	
	public static class WatchTable extends ObjectTable {
		public static final Key PRIMARY_KEY = new Key();

		public enum Field {PROGRAM, TUPLENAME, MODIFIER, OBJECT};
		public static final Class[] SCHEMA =  {
			String.class,    // Program name
			String.class,    // Tuple name
			String.class,    // Modifier
			Watch.class      // Object
		};

		public WatchTable() {
			super("watch", PRIMARY_KEY, new TypeList(SCHEMA));
			Key programKey = new Key(Field.PROGRAM.ordinal());
			Index index = new HashIndex(this, programKey, Index.Type.SECONDARY);
			this.secondary.put(programKey, index);
		}

		@Override
		protected boolean insert(Tuple tuple) throws UpdateException {
			String program = (String) tuple.value(Field.PROGRAM.ordinal());
			Watch object = (Watch) tuple.value(Field.OBJECT.ordinal());
			object.program = program;
			return super.insert(tuple);
		}

		@Override
		protected boolean remove(Tuple tuple) throws UpdateException {
			return super.remove(tuple);
		}
	}
	
	private String program;
	
	private String name;
	
	private String modifier;

	public Watch(xtc.tree.Location location, String name, String modifier) {
		super(location);
		this.name = name;
		this.modifier = modifier;
	}
	
	public String toString() {
		return "watch(" + name + ", " + modifier + ").";
	}

	@Override
	public void set(String program) throws UpdateException {
		Program.watch.force(new Tuple(Program.watch.name(), program, name, modifier, this));
	}
}
