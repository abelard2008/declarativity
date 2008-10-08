package jol.types.table;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jol.types.basic.Tuple;
import jol.types.basic.TupleSet;
import jol.types.exception.BadKeyException;
import jol.core.Runtime;

public class HashIndex extends Index {
	
	private Map<Tuple, TupleSet> map;

	public HashIndex(Runtime context, Table table, Key key, Type type) {
		super(context, table, key, type);
		map = new HashMap<Tuple, TupleSet>();
		for (Tuple tuple : table.tuples()) {
			insert(tuple);
		}
	}
	
	@Override
	public String toString() {
		String out = "Index " + table().name() + "\n";
		if (map != null) {
			out += map.toString() + "\n";
		}
		return out;
	}
	
	@Override
	public void clear() {
		this.map.clear();
	}

	@Override
	protected void insert(Tuple t) {
		Tuple key = key().project(t);
		if (this.map.containsKey(key)) {
			this.map.get(key).add(t);
		}
		else {
			TupleSet tuples = new TupleSet(table().name());
			tuples.add(t);
			this.map.put(key, tuples);
		}
	}

	@Override
	public TupleSet lookup(Tuple t) {
		Tuple key = key().project(t);
		return this.map.containsKey(key) ? 
				this.map.get(key) : new TupleSet(table().name());
	}
	
	@Override
	public TupleSet lookup(Key key, Tuple t) {
		Tuple k = key.project(t);
		return this.map.containsKey(k) ? 
				this.map.get(k) : new TupleSet(table().name());
	}

	@Override
	protected void remove(Tuple t) {
		Tuple key = key().project(t);
		
		if (this.map.containsKey(key)) {
			this.map.get(key).remove(t);
		}
	}

	@Override
	public Iterator<Tuple> iterator() {
		Set<Tuple> tuples = new HashSet<Tuple>();
		for (TupleSet set : this.map.values()) {
			tuples.addAll(set);
		}
		return tuples.iterator();
	}

	@Override
	public TupleSet lookup(Comparable... values) throws BadKeyException {
		if (values.length != key().size()) {
			throw new BadKeyException("Value length does not match key size!");
		}
		
		List<Comparable> keyValues = new ArrayList<Comparable>();
		for (Comparable value : values) {
			keyValues.add(value);
		}
		Tuple key = new Tuple(keyValues);
		return this.map.get(key);
	}
}
