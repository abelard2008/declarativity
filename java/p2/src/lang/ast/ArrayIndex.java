package lang.ast;

public class ArrayIndex extends Expression {
	
	private Expression array;
	
	private Integer index;
	
	public ArrayIndex(Expression array, Integer index) {
		this.array = array;
		this.index = index;
	}
	
	@Override
	public Class type() {
		return array.type().getComponentType();
	}

}
