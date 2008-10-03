package p2.lang.plan;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import p2.types.basic.Tuple;
import p2.types.exception.P2RuntimeException;
import p2.types.function.TupleFunction;

public class StaticMethodCall extends Expression {
	
	private Class type;
	
	private Field field;
	
	private Method method;
	
	private List<Expression> arguments;

	public StaticMethodCall(Class type, Method method, List<Expression> arguments) {
		this.type = type;
		this.method = method;
		this.arguments = arguments;
	}
	
	public StaticMethodCall(Field field, Method method, List<Expression> arguments) {
		this.field = field;
		this.method = method;
		this.arguments = arguments;
	}
	
	@Override
	public String toString() {
		String name;
		if (field != null) {
			name = this.field.getName() + "." + method.getName();
		}
		else {
			name = type.getName() + "." + method.getName();
		}
		
		if (arguments.size() == 0) {
			return name + "()";
		}
		name += "(" + arguments.get(0).toString();
		for (int i = 1; i < arguments.size(); i++) {
			name += ", " + arguments.get(i);
		}
		return name + ")";
	}

	@Override
	public Class type() {
		return method.getReturnType();
	}

	@Override
	public Set<Variable> variables() {
		Set<Variable> variables = new HashSet<Variable>();
		for (Expression arg : arguments) {
			variables.addAll(arg.variables());
		}
		return variables;
	}

	@Override
	public TupleFunction function() {
		final List<TupleFunction> argFunctions = new ArrayList<TupleFunction>();
		for (Expression argument : this.arguments) {
			argFunctions.add(argument.function());
		}
		
		return new TupleFunction() {
			public Object evaluate(Tuple tuple) throws P2RuntimeException {
				Object[] arguments = new Object[StaticMethodCall.this.arguments.size()];
				int index = 0;
				for (TupleFunction argFunction : argFunctions) {
					arguments[index++] = argFunction.evaluate(tuple);
				}
				try {
					// System.err.println("STATIC METHOD CALL " + StaticMethodCall.this.method.getName());
					return StaticMethodCall.this.method.invoke(null, arguments);
				} catch (Exception e) {
					e.printStackTrace();
					throw new P2RuntimeException(e.toString());
				}
			}

			public Class returnType() {
				return type();
			}
		};
	}

}
