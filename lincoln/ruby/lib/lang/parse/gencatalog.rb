#!/usr/bin/ruby

# convert SQL-DDL-style "create table" statements to ruby ObjectTable classes with equivalent definitions.
# the "--compiler" flag generates CompilerCatalogTable classes.

# e.g.:
#    ruby gencatalog.rb -c global < global.sql > schema.rb
#    ruby gencatalog.rb -c bootstrap < bootstrap.sql >> schema.rb
#    ruby gencatalog.rb -c compiler < compiler.sql >> schema.rb


require "rubygems"
require "treetop"
require 'tree_walker.rb'
require 'ddl.rb'
require 'getoptlong'

$current = Hash.new
$tables = Hash.new
$types = Hash.new
$keys = Hash.new

class Visit < TreeWalker::Handler
	def semantic(text,obj)
		$current[self.token] = text
	end
end

class VTable < Visit
	def semantic(text,obj)
		$tables[text] = Array.new
		$types[text] = Array.new
		$keys[text] = Array.new
		$position = 0
		super(text,obj)
	end
end

class VCol < Visit
	def semantic(text,obj)
		#print "current of "+$current["tablename"]+"\n"
		$tables[$current["tablename"]] << text.delete('+')
		$position += 1
		super(text,obj)
	end 
end

class VKey < Visit
  def semantic(text,obj)
    $keys[$current["tablename"]] << $position-1
    super(text,obj)
  end
end

class VType < Visit
	def semantic(text,obj)
		super(text,obj)
		$types[$current["tablename"]] << text
	end
end

class VNum < Visit
	def semantic(text,obj)
		super(text,obj)
		$keys[$current["tablename"]] << text
	end
		
end

def printusage
  print "gencatalog: generates object definitions from table statements."
  print "Usage: ruby gencatalog.rb -c [classname]"
  print "POSIX options                   GNU long options"
  print "     -c                             --class"
  print "Examples:"
  print "ruby gencatalog.rb -c Bootstrap < bootstrap.sql > schema.rb"
  print "ruby gencatalog.rb --class Compiler < compiler.sql >> schema.rb"
end

opts = GetoptLong.new(
  [ "--class", "-c", GetoptLong::REQUIRED_ARGUMENT]
)

classname = ""

opts.each do |opt, arg|
#  puts "Option: #{opt}, arg #{arg.inspect}"
  if (opt == "--class" or opt == "-c")
    classname = arg.to_s
  end
end

if classname == "" then
  printusage
  exit
end

prog = ''
while line = STDIN.gets
	prog = prog + line
end


parser = DdlParser.new
tree = parser.parse(prog)
if !tree
      puts 'failure'
     raise RuntimeError.new(parser.failure_reason)
end

sky = TreeWalker.new(tree)

v = Visit.new(nil)

sky.add_handler("tablename",VTable.new(nil),1)
sky.add_handler("key_colname",VCol.new(nil),1)
sky.add_handler("key_modifier",VKey.new(nil),1)
sky.add_handler("dtype",VType.new(nil),1)


sky.add_handler("num",VNum.new(nil),1)

sky.walk("n")

print "require 'lib/types/table/object_table'\n"
print "require 'lib/lang/parse/compiler_mixins'\n"
print "class " + classname.capitalize + "CatalogTable < ObjectTable\n"
print "  @@classes = Hash.new\n"
scope = classname.upcase + "SCOPE"
print "  #{scope} = '#{classname}'\n"
print "  def " + classname.capitalize + "CatalogTable.classes\n"
print "    @@classes.keys\n"
print "  end\n"
print "end\n\n"

$tables.sort.each do |table, arr|
  tableCap = table[0..0].capitalize + table[1..table.length]
  mixin = tableCap+"TableMixin"
	print "class "+tableCap+"Table < " + classname.capitalize + "CatalogTable\n"
  print "  include "+mixin+" if defined? "+mixin+"\n"
	if ($keys[table].size > 0) then
		print "  @@PRIMARY_KEY = Key.new("+$keys[table].join(",")+")\n"
	else
		# print "  @@PRIMARY_KEY = Key.new("+(0..arr.size-1).to_a.join(",")+")\n"
		print "  @@PRIMARY_KEY = Key.new\n"
	end

	print "  class Field\n"
	(0..arr.size-1).each do |i|
		print "    "+arr[i].upcase+"="+i.to_s+"\n"
	end
	print "  end\n"

  tableStr = table[0..0].downcase + table[1..table.length]
	print "  @@SCHEMA = ["+$types[table].join(",")+"]\n"
	print "  @@TABLENAME = TableName.new(#{scope}, \"#{tableStr}\")\n"
  print "  @@classes[self] = 1"
  
	print "\n  def initialize(context)\n"
        print "    super(context, @@TABLENAME, @@PRIMARY_KEY,  TypeList.new(@@SCHEMA))\n"
        print "    if defined? "+mixin+" and "+mixin+".methods.include? 'initialize_mixin'\n       initialize_mixin(context) \n    end\n"
  # print "    programKey = Key.new(Field::" + arr[0].upcase+")\n"
  # print "    index = HashIndex.new(self, programKey, Index::Type::SECONDARY)\n"
  # print "    @secondary[programKey] = index\n"
	print "  end\n"

  print "\n  def field(name)\n"
  print "\n    eval('Field::'+name)\n"
  print "\n  end"
  
  print "\n  def scope\n"
  print "\n    #{scope}\n"
  print "\n  end"
  
  print "\n  def " + tableCap + "Table.pkey\n"
  print "\n    @@PRIMARY_KEY\n"
  print "\n  end"

  print "\n  def " + tableCap + "Table.schema\n"
  print "\n    @@SCHEMA\n"
  print "\n  end"
  
	print "\n  def schema_of\n"
	(0..arr.size-1).each do |i|
		print "    "+ arr[i]+" = Variable.new(\""+arr[i]+"\","+$types[table][i]+", "+i.to_s+",nil)\n"
	end
	print "    return Schema.new(\""+tableCap+"\",["+arr.join(",")+"])\n"
	print "  end\n"

  print "\n  def " + tableCap + "Table.table_name"
  print "\n    @@TABLENAME"
  print "\n  end\n"
  
	print "end\n\n"
end
