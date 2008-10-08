package jol.types.table;

import java.util.Hashtable;

import jol.core.Runtime;
import jol.types.basic.Tuple;
import jol.types.basic.TupleSet;
import jol.types.basic.TypeList;
import jol.types.exception.UpdateException;

public abstract class Function extends Table {

	public Function(String name, TypeList types) {
		super(new TableName(GLOBALSCOPE, name), Type.FUNCTION, null, types);
	}
	
	@Override 
	public abstract TupleSet insert(TupleSet tuples, TupleSet conflicts) throws UpdateException;

	@Override
	protected final boolean insert(Tuple t) throws UpdateException {
		throw new UpdateException("Can't remove tuples from a table function");
	}
	
	@Override
	protected final boolean delete(Tuple t) throws UpdateException {
		throw new UpdateException("Can't remove tuples from a table function");
	}
	
	@Override
	public Index primary() {
		return null;
	}

	@Override
	public Hashtable<Key, Index> secondary() {
		return null;
	}

	@Override
	public TupleSet tuples() {
		return null;
	}
	
	public Integer cardinality() {
		return 0;
	}

}
