package lang.ast;

public class Watch extends Clause {
	
	private String name;
	
	private String modifier;

	public Watch(String name, String modifier) {
		this.name = name;
		this.modifier = modifier;
	}
}
