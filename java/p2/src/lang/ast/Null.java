package lang.ast;

public class Null extends Value {
	public static Null NULLV = new Null(null);
	
	public Null(Object value) {
		super(null);
	}
}
