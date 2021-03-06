# require 'lib/lang/plan/program'
# require 'lib/lang/plan/rule'
# require 'lib/lang/plan/predicate'
# require 'lib/lang/plan/value'
# require 'lib/lang/plan/native_expression'
# require 'lib/lang/plan/selection_term'
# require 'lib/types/table/table'
# require 'lib/core/system'
# require "test/unit"
# require "rubygems"

require 'lib/lang/compiler'
require 'lib/lang/plan/program'
require "lib/types/table/table"
require "lib/lang/plan/selection_term"
require 'lib/lang/plan/value'
require 'lib/lang/plan/predicate'
require 'lib/core/driver'
require "test/unit"
require 'lib/lang/plan/native_expression'
require 'lib/lang/plan/arbitrary_expression'


class TestProgram < Test::Unit::TestCase
  def default_test
    runtime = Runtime.new

    p = Program.new(runtime, "testprog", "jmh")

    ####
    # set up a table link(from, to, cost, annotation)
    ####
    v1 = Variable.new("From", Integer, 0,nil)
    v2 = Variable.new("To", Integer, 1,nil)
    v3 = Variable.new("Cost", Float, 2,nil)
    v4 = Variable.new("Annotation", String, 3,nil)
    t1 = Tuple.new(1,2,0.5, "first")
    t2 = Tuple.new(2,3,1.0, "second")
    schema1 = Schema.new("schema1", [v1,v2,v3,v4])
    t1.schema = schema1
    t2.schema = schema1
    tn = TableName.new(nil, "link")
    ts = TupleSet.new(tn, t1, t2)
    table = BasicTable.new(runtime, tn, Key.new(1,2), [Integer,Integer,Float,String])
    p.definition(table)
    runtime.catalog.register(table)
    
    ####
    # try a simple projection on one relation: 
    #   path(From, To, Cost) :- link(From, To, Cost, Annotation).
    ####
    link = Predicate.new(false, tn, Predicate::Event::NONE, [v1, v2, v3, v4])
    link.set(runtime, 'testprog', 'r1', 1)
    path = Predicate.new(false, TableName.new(nil,"path"), Predicate::Event::NONE, [v1, v2, v3])
    path.set(runtime, 'testprog', 'r1', 0)
    body = [link]
    r = Rule.new(1, 'r1', true, false,  path, body)
    r.set(runtime, 'testprog')
    assert_equal(r.to_s, "public r1 ::path(From:0, To:1, Cost:2) :- \n\t::link(From:0, To:1, Cost:2, Annotation:3);\n\t;\n") 
    p.plan
    assert_equal(p.get_queries(tn)[0].rule.to_s, "r1")
    result = p.get_queries(tn)[0].evaluate(ts)
    assert_equal(result.tups.length, 2)
    assert_equal(result.tups[0].values, [1,2,0.5])
    assert_equal(result.tups[1].values, [2,3,1.0])

    ####
    # simple selection:
    #   path(From, To, Cost) :- link(From, To, Cost, Annotation), To == 2.
    # 
    # We won't add this rule to the program p, we'll just invoke it directly.
    ####

    #bool = Boolean.new("==", v2, Value.new(2))
    # ArbitraryExpressions can replace all expressions... but it means we parse twice.
    bool = ArbitraryExpression.new("To == 2",[v2])

    selterm = SelectionTerm.new(bool)
    body = [link, selterm]
    
    r = Rule.new(1, 'r2', true, false,  path, body)
    result = r.query(runtime,nil)[0].evaluate(ts)
    assert_equal(result.size, 1)
    assert_equal(result.tups[0].values, [1,2,0.5])
    
    ####
    # self-join rule with arithmetic in the head
    #    path(To, To2, Cost+Cost2) :- link(From, To, Cost, Annotation), 
    #                            link(To, To2, Cost2, Note2).
    ####
    # first insert tuple t1 into link
    table.insert(TupleSet.new(tn, t1),nil)
    
    tn2 = TableName.new(nil, "path")
    table2 = BasicTable.new(runtime, tn2, Key.new(1,2), [Integer,Integer,Float])
    v5 = Variable.new("To", Integer, 0,nil)
    v6 = Variable.new("To2", Integer, 1,nil)
    v7 = Variable.new("Cost2", Float, 2,nil)
    v8 = Variable.new("Note2", String, 3,nil)
    link2 = Predicate.new(false, TableName.new(nil,"link"), Predicate::Event::NONE, [v5, v6, v7, v8])
    link2.set(runtime, 'testprog', 'l2', 1)

    body = [link, link2]
    
    e = NativeExpression.new("+", v3, v7)
    # this one would work fine, were it not for idiosyncratic differences in pretty-printing.
    #e = ArbitraryExpression.new("Cost + Cost2",[v3,v7])

    path3 = Predicate.new(false, TableName.new(nil,"path"), Predicate::Event::NONE, [v1, v6, e])
    path3.set(runtime, 'testprog', 'p3', 0)
    
    r3 = Rule.new(1, 'r3', true, false, path3, body)
    r3.set(runtime, 'testprog')
    assert_equal(r3.to_s, "public r3 ::path(From:0, To2:1, (Cost:2 + Cost2:2)) :- \n\t::link(From:0, To:1, Cost:2, Annotation:3),\n\t::link(To:0, To2:1, Cost2:2, Note2:3);\n\t;\n")

    
    p.plan
    
    # There should now be 3 query objects associated with p:
    #   one delta-rewritten version of r1, 
    #   and 2 delta-rewritten versions of r3.
    # Run the 2nd version of r3 with a delta containing tuple t2, and 
    #   it should find t1
    queries = p.get_queries(tn)
    assert_equal(queries.length, 3)
    assert_equal(queries[2].rule.to_s, "r3")
    result = queries[2].evaluate(TupleSet.new(tn, t2))
    assert_equal(result.size, 1)
    assert_equal(result.tups[0].values, [1, 3, 1.5])
  end
end
