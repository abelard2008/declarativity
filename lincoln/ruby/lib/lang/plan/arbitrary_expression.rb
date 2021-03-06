
class ArbitraryExpression < Expression

  def initialize(expr,variables)
    @expr = expr
    @variables = Array.new
    # this whole bit is horribly hacktastic
    sides = expr.split(".")
#    # require 'ruby-debug'; debugger if expr == "TableName.new(\"compiler\", \"config\")"
    case sides.size 
    when 0
    when 1 
      # good
    when 2
      if variables.size != 1
        #raise("object.method can only be bound over one variable (object)")
      end
      @obj = sides[0]
      wholemeth = sides[1].split("(")
      @method = wholemeth[0]
    else	
      raise("only one object.method per expression allowed")
    end

    variables.each do |v|
      # @variables << v
      if v.class == Variable then
        unless v.name == ""
          @expr = @expr.gsub(/\b#{v.name}/,'v'+v.name)
          @variables << v
        end
      else 
        @variables << v
      end
    end
  end

  def expr_type
    return nil
  end

  def to_s
    return "(" + @expr + ")"
  end

  def variables
    @variables
  end

  def wrap_for_eval(s)
    if s.class <= String
      if s =~ /[0-9.]+/ 
        val = s
      else
        val = "\"" + s + "\""
      end
#      print "WRAP #{s} => #{val}"
      return val
    elsif s.class <= TableName
      val = "\"" + s.to_s + "\""
    else
      val = s
    end
    return val
  end
  
  def function
    elam = lambda do |t|
     #print "EXPR #{@expr}\n"
      subexpr = ''
      unless @method.nil? 
        # require 'ruby-debug'; debugger if !defined? @variables[0].name
        # require 'ruby-debug'; debugger if !defined? t.schema
        if t.schema.contains(@variables[0])
         value = t.name_value(@variables[0].name)
        else
          # this is not an attribute of the tuple.  Hopefully this is a Ruby class name
          # Put a try/catch thingy around me.
          value = eval(@variables[0].name)
        end
        # if value.class == Program then
        if @variables.size > 1 then
          args = []
          @variables[1..@variables.size-1].each do |v|
            if v.class == Value
#              args << wrap_for_eval(v.value)
              args << v.value
            elsif v.class == Variable
#              args << wrap_for_eval(t.name_value(v.name))
              args << t.name_value(v.name)
            end
          end
#          # require 'ruby-debug'; debugger if value.nil?
          return value.send(@method,*args)		
        else
          # FIX ME
          ####foo = @method.gsub(/[()]/,"")
#          # require 'ruby-debug'; debugger if value.nil?
          return value.send(@method)
         #### return value.send(foo)
        end
        # end
      end
      @variables.each_with_index do |v, i|	
        if v.class == Variable
          if t.schema.contains(v) then
            # substitution is stupid: how many times are we gonna parse this thing??
            # instead, take advantage of rubiismo:
            value = wrap_for_eval(t.name_value(v.name))
#            value = t.name_value(v.name)
            # workaround
            ##subexpr = subexpr + "v"+v.name + " = \""+value.to_s+"\"\n"
#            # require 'ruby-debug'; debugger unless value.class == TrueClass
            # require 'ruby-debug'; debugger if value.to_s == ''
            subexpr = subexpr + "v"+v.name + " = "+value.to_s+"\n"
          elsif defined?(@variables[i+1]) and @variables[i+1].class == Value
            # unbound variables had better belong to assignments
            # which show up as a Variable followed by a Value
            value = wrap_for_eval(@variables[i+1].value)
#            value = @variables[i+1].value
            subexpr = subexpr + "v"+v.name + " = \""+value.to_s+"\"\n"
#            print "SUBEXPR: #{subexpr}\n"
          end
        elsif (v.class == Value)
          next
        else
          raise "unable to resolve a PrimaryExpression"
        end
      end
      subexpr = subexpr + @expr
      begin
#        # require 'ruby-debug'; debugger
#        print "EVAL: #{subexpr}\n"
        retval = eval(subexpr)
      rescue => detail
        outstr = "failed to eval \'#{subexpr}\'"
#        puts detail.backtrace.join("\n")
        raise NameError, "failed to eval " + outstr
      end
      return retval
    end

    tlam = lambda do
      return expr_type
    end

    tmpClass = Class.new(TupleFunction)

    tmpClass.send :define_method, :evaluate do |tuple|
      return elam.call(tuple)
    end
    tmpClass.send :define_method, :returnType do 
      return tlam.call
    end
    retval = tmpClass.new
    return retval
  end
end
