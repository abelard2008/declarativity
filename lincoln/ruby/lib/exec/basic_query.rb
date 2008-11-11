require 'lib/exec/query'
require 'lib/types/exception/dataflow_runtime_exception'
class BasicQuery < Query

  def initialize(context, program, rule, isPublic, isDelete, event, head, body)
    @aggregation = nil
    require 'ruby-debug'; debugger if body.nil?
    @body        = body
    super(context, program, rule, isPublic, isDelete, event, head)
  end

  def to_s
    String   query = "Basic Query Rule " + rule + 
    ": input " + input.to_s
#    require 'ruby-debug'; debugger
    @body.each do |oper| 
      query += " -> " + oper.to_s
    end
    query += " -> " + output().to_s
    return query
  end

  def evaluate(inval)
    if (@input.name != inval.name) then
      raise DataflowRuntimeException, "Query expects input " + @input.name.to_s + 
      " but got input tuples " + inval.name.to_s
    end

    tuples = TupleSet.new(@input.name)
    inval.each do |tuple| 
      tuple = tuple.clone
      tuple.schema = @input.schema.clone
      tuples << (tuple)
    end

    @body.each do |oper| 
      tuples = oper.evaluate(tuples)
    end

    return tuples
  end
end
