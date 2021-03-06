require "lib/types/exception/update_exception"
require 'lib/types/basic/tuple'
require 'csv'
require 'fileutils'
require 'tmpdir'

class Table
  GLOBALSCOPE = "global"
  INFINITY = 1.0/0

  attr_reader name
  
  def initialize(name, type, key, attributeTypes)
    @name = name
    @table_type = type
    @attributeTypes = attributeTypes
    @key = key
    @callbacks = Array.new    
  end

  def Table.init(context)
    catalog = Catalog.new(context)
    catalog.index = IndexTable.new(context)
    catalog.register(catalog.index)
    catalog.register(catalog)
    catalog.register(OperatorTable.new(context))

    return catalog
  end
  # THIS IS IN PREDICATE, NOT TABLE!  
  # class Event
  #   NONE = 0
  #   INSERT = 1
  #   DELETE = 2
  # end

  module TableType
    TABLE = 0
    EVENT = 1
    FUNCTION = 2
  end

  def to_s
    value = @name.to_s + ", " + @attributeTypes.to_s + 
    ", keys(" + @key.to_s + "), {" +
    @attributeTypes.to_s + "}\n"
    if not tuples.nil?
      tuples.each do |t|
        value += t.to_s + "\n"
      end
    end
    return value
  end

  def cardinality
    raise "No subclass definition for cardinality"
  end

  def size
    cardinality
  end

  attr_reader :table_type, :name, :lifetime, :key
  
  def types
    @attributeTypes
  end
  
  def tuples
    raise "subclass method for Table.tuples not defined"
  end
  

  # register a new callback on table updates
  def register_callback(callback)
    @callbacks << callback
  end

  def unregister(callback)
    @callbacks.delete(callback)
  end

  def drop
    tuples = catalog.primary.lookup_vals(name)
    retval = (catalog.delete(tuples.tups).size > 0)
    tuples = catalog.primary.lookup_vals(name)
    unless tuples.nil? or tuples.size == 0
      raise "Table.drop failed" 
    end
  end

  # def Table.find_table(catalog, name)
  #   raise "Catalog missing" if catalog.nil?
  #   tables = catalog.primary.lookup_vals(name)
  #   return nil if tables.nil? or tables.size == 0
  #   return tables.tups[0].values[Catalog::Field::OBJECT] if tables.size == 1
  #   throw tables.size.to_s + " tables named " + name.to_s + " defined!"
  # end
  # 
  def <=>(o)
    return (name <=> (o.name))
  end


  def primary
    raise "subclass method for Table.primary not defined"
  end

  def secondary
    raise "subclass method for Table.secondary not defined"
  end

  def insert(newtuples, conflicts)
    delta = TupleSet.new(name, nil)
#    require 'ruby-debug'; debugger if name.name == 'dependency'
    newtuples.each do |t|
 #     t = t.clone
      
      oldvals = nil
			if (!conflicts.nil? and !primary.nil?) then
					oldvals = primary.lookupByKey(primary.key.project(t))
			end
      
      if insert_tup(t)
        delta << t
        conflicts.addAll(oldvals) unless oldvals.nil?
        insertion = TupleSet.new(name)
        insertion << t
        @callbacks.each {|c| c.insertion(insertion)}
      end
    end

    return delta
  end
  
  def clear
    @tuples.clear
    @callbacks.each {|c| c.clear}
  end
  
  def delete(deltuples)
    # require 'ruby-debug'; debugger if tuples.nil?
    delta = TupleSet.new(name)
    deltuples.each do |t|
#      t = t.clone
      delta << t unless delete_tup(t).nil?
    end

    if delta.size > 0
      @callbacks.each {|c| c.deletion(delta)}
    end
 
    # this test is actually broken -- don't lookup on primary, need to lookup on all fields
    # delta.each do |t|
    #    matches = primary.lookup(t)
    #    unless matches.nil? or matches.size == 0
    #      require 'ruby-debug'; debugger
    #      raise "deleted tuple still in primary index" 
    #    end
    #  end
    return delta
  end


  def force(tuple)
    insertion = TupleSet.new(name, nil)
    insertion << tuple
    conflicts = TupleSet.new(name, nil)
    insert(insertion, conflicts)
  end

#  protected
  def insert_tup(tuple)
    raise "subclass method for AbstractTable.insert(tuple) not defined"
  end

  def delete_tup(tuple)
    raise "subclass method for AbstractTable.delete_tup(tuple) not defined"
  end
  
  def dump_to_tmp_csv
    dir = "/tmp/lincoln"+Process.pid.to_s
    Dir.mkdir(dir) unless File.exist?(dir) 
    outfile = File.open(dir + "/" + name.to_s+".csv", "w")
    CSV::Writer.generate(outfile, ',') do |csv|
      csv << tuples.tups[0].schema.variables unless (tuples.tups[0].nil? or tuples.tups[0].schema.nil?)
      tuples.each  {|t| csv << t.values}
    end
  end
end
