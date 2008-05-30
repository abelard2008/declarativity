package p2.lang.plan;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import p2.exec.BasicQuery;
import p2.exec.Query;
import p2.lang.plan.Predicate.Field;
import p2.types.basic.Schema;
import p2.types.basic.Tuple;
import p2.types.basic.TupleSet;
import p2.types.basic.TypeList;
import p2.types.exception.P2RuntimeException;
import p2.types.exception.PlannerException;
import p2.types.exception.UpdateException;
import p2.types.function.TupleFunction;
import p2.types.operator.EventFilter;
import p2.types.operator.Operator;
import p2.types.operator.Projection;
import p2.types.table.Aggregation;
import p2.types.table.HashIndex;
import p2.types.table.Index;
import p2.types.table.Key;
import p2.types.table.ObjectTable;
import p2.types.table.Table;
import p2.types.table.TableName;
import p2.core.Periodic;

public class Rule extends Clause {
	
	public static class RuleTable extends ObjectTable {
		public static final Key PRIMARY_KEY = new Key(0,1);
		
		public enum Field {PROGRAM, RULENAME, DELETION, OBJECT};
		public static final Class[] SCHEMA =  {
			String.class,             // Program name
			String.class,             // Rule name
			java.lang.Boolean.class,  // deletion rule?
			Rule.class                // Rule object
		};

		public RuleTable() {
			super(new TableName(GLOBALSCOPE, "rule"), PRIMARY_KEY, new TypeList(SCHEMA));
			Key programKey = new Key(Field.PROGRAM.ordinal());
			Index index = new HashIndex(this, programKey, Index.Type.SECONDARY);
			this.secondary.put(programKey, index);
		}
		
		@Override
		protected boolean insert(Tuple tuple) throws UpdateException {
			Rule object = (Rule) tuple.value(Field.OBJECT.ordinal());
			if (object == null) {
				throw new UpdateException("Predicate object null");
			}
			object.program   = (String) tuple.value(Field.PROGRAM.ordinal());
			object.name      = (String) tuple.value(Field.RULENAME.ordinal());
			object.deletion  = (java.lang.Boolean) tuple.value(Field.DELETION.ordinal());
			return super.insert(tuple);
		}
		
		@Override
		protected boolean delete(Tuple tuple) throws UpdateException {
			return super.delete(tuple);
		}
	}
	
	private String program;
	
	private String name;
	
	private java.lang.Boolean deletion;
	
	private Predicate head;
	
	private List<Term> body;
	
	private boolean aggregation;
	
	public Rule(xtc.tree.Location location, String name, java.lang.Boolean deletion, Predicate head, List<Term> body) {
		super(location);
		this.name = name;
		this.deletion = deletion;
		this.head = head;
		this.body = body;
		this.aggregation = false;
		for (Expression arg : head) {
			if (arg instanceof Aggregate) {
				/* assertion: only 1 aggregate. */
				assert(this.aggregation == false);
				this.aggregation = true;
			}
		}
	}
	
	@Override
	public String toString() {
		String value = name + (deletion ? " delete " : " ") + head + " :- \n";
		for (int i = 0; i < body.size(); i++) {
			value += "\t" + body.get(i);
			if (i + 1 < body.size()) {
				value += ",\n";
			}
			else {
				value += ";\n";
			}
		}
		return value;
	}
	
	public int compareTo(Clause o) {
		if (o instanceof Rule) {
			Rule other = (Rule) o;
			String otherName = other.program + ":" + other.name;
			String myName    = this.program + ":" + this.name;
			return otherName.compareTo(myName);
		}
		return -1;
	}
	
	public Predicate head() {
		return this.head;
	}
	
	public List<Term> body() {
		return this.body;
	}
	
	@Override
	public void set(String program) throws UpdateException {
		this.head.set(program, this.name, 0);
		for (int i = 0; i < this.body.size(); i++) {
			this.body.get(i).set(program, this.name, i+1);
		}
		
		Program.rule.force(new Tuple(program, name, deletion, this));
	}

	public List<Query> query(TupleSet periodics) throws PlannerException {
		/* First search for an event predicate. */
		Predicate event   = null;
		Function function = null;
		for (Term term : body) {
			if (term instanceof Predicate) {
				Predicate pred = (Predicate) term;

				Table table = Table.table(pred.name());
				if (table.type() == Table.Type.EVENT ||
				    pred.event() != Table.Event.NONE) {
					if (event != null) {
						throw new PlannerException("Multiple event predicates in rule " + name);
					}
					/* Plan a query with this event predicate as input. */
					event = pred;
				}
			}
			else if (term instanceof Function) {
				function = (Function) term;
				event = function.predicate();
				event.event(Table.Event.INSERT);
				
			}
		}
		
		List<Query> queries = new ArrayList<Query>();
		if (event != null) {
			List<Operator> operators = new ArrayList<Operator>();
			
			if (event.name().name.equals("periodic") && ! event.name().scope.equals(Table.GLOBALSCOPE)) {
				Long period = (Long) ((Value) event.argument(Periodic.Field.PERIOD.ordinal())).value();
				Long ttl    = (Long) ((Value) event.argument(Periodic.Field.TTL.ordinal())).value();
				Long count  = (Long) ((Value) event.argument(Periodic.Field.COUNT.ordinal())).value();
				List<Comparable> values = new ArrayList<Comparable>();
				values.add(event.identifier());
				for (int i = 1; i < event.arguments(); i++) {
					values.add(((Value<Comparable>) event.argument(i)).value());
				}
				periodics.add(new Tuple(values));
				
				final String identifier = event.identifier();
				TupleFunction<java.lang.Boolean> periodicFilter = new TupleFunction<java.lang.Boolean>() {
					public java.lang.Boolean evaluate(Tuple tuple)
							throws P2RuntimeException {
						return identifier.equals((String)tuple.value(Periodic.Field.IDENTIFIER.ordinal()));
					}
					public Class returnType() {
						return java.lang.Boolean.class;
					}
				};
				EventFilter efilter = new EventFilter(event, periodicFilter);
				operators.add(efilter);
			}
			else {
				EventFilter efilter = new EventFilter(event);
				if (efilter.filters() > 0) {
					operators.add(efilter);
				}
			}
			
			if (function != null) {
				operators.add(function.operator());
				for (Term term : body) {
					if (!term.equals(function)) {
						operators.add(term.operator());
					}
				}
			}
			else {
				for (Term term : body) {
					if (!term.equals(event)) {
						operators.add(term.operator());
					}
				}
			}
			
			queries.add(new BasicQuery(program, name, deletion, event, new Projection(this.head), operators));
		}
		else {
			/* Perform delta rewrite. */
			Set<TableName> eventPredicates = new HashSet<TableName>();
			for (Term term1 : body) {
				if (!(term1 instanceof Predicate)) {
					continue;
				}
				else {
					Predicate pred = (Predicate) term1;
					if (pred.notin() || eventPredicates.contains(pred.name())) {
						continue;
					}
					eventPredicates.add(pred.name());
				}
				
				Predicate delta = (Predicate) term1;
				List<Operator> operators = new ArrayList<Operator>();
				EventFilter efilter = new EventFilter(delta);
				if (efilter.filters() > 0) {
					operators.add(efilter);
				}
				
				for (Term term2 : body) {
					if (!term2.equals(delta)) {
						operators.add(term2.operator());
					}
				}
				
				queries.add(new BasicQuery(program, name, deletion, delta, 
							               new Projection(this.head), operators));
			}
			
		}
		return queries;
	}
	
}
