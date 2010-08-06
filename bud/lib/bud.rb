require 'enumerator'
require 'msgpack'
require 'eventmachine'
require 'socket'
require 'superators'
require 'bud/aggs'
require 'bud/collections'
require 'bud/errors'
require 'bud/events'

class Bud
  attr_reader :strata, :budtime, :inbound
  attr_accessor :connections
  attr_reader :tables # for debugging; remove me later

  def initialize(ip, port)
    @tables = {}
    @strata = []
    @channels = {}
    @budtime = 0
    @ip = ip
    @port = port.to_i
    @connections = {}
    @inbound = []

    @periodics = table :periodics, ['name'], ['ident', 'duration']
    @vars = table :vars, ['name'], ['value']
    @tmpvars = scratch :tmpvars, ['name'], ['value']
  end  


  ######## methods for controlling execution
  def run
    begin 
      EventMachine::run {
        EventMachine::start_server(@ip, @port, Server) { |con|
          con.bud = self # pass this Bud object into the connection
        }
        puts "running bud server on #{@ip}, #{@port}"
        tick
      }
    end
  end

  def tick
    @budtime += 1
    # reset any schema stuff that isn't already there
    # state to be defined by the user program
    state

    receive_inbound

    # load the rules as a closure (will contain persistent tuples and new inbounds)
    # declaration to be provided by user program
    declaration

    @strata.each { |strat| stratum_fixpoint(strat) }
    @channels.each { |c| @tables[c[0]].flush }
    reset_periodics
  end

  # handle any inbound tuples off the wire and then clear
  def receive_inbound
    @inbound.each do |msg|
      tables[msg[0].to_sym] << msg[1]
    end
    @inbound = []
  end

  def stratum_fixpoint(strat)
    cnts = Hash.new
    @tables.each_key do |k| 
      #      define_method(k.to_sym) { @tables[k] }
      eval "#{k.to_s} = @tables[#{k}]"
    end
    begin
      cnts = {}
      @tables.each_key{|k| cnts[k] = @tables[k].length}
      strat.call
    end while cnts.inject(0){|sum,t| sum + (@tables[t[0]].length - t[1])} > 0
  end

  def reset_periodics
    @periodics.each do |p| 
      if @tables[p.name].length > 0 then
        set_timer(p.name, p.ident, p.duration) 
        @tables[p.name] = scratch p.name, ['ident'], ['time']
      end
    end
  end


  ######## methods for registering collection types
  def check_table(name, keys=[], cols=[])
    # rule out tablenames that used reserved words
    reserved = defined?(name)
    if reserved == "method" and not @tables[name] then
      # first time registering table, check for method name reserved
      raise BudError, "symbol :#{name} reserved, cannot be used as table name"
    end

    # tick previously-defined tables and tick
    if @tables[name] then
      # check for consistent redefinition, and "tick" the table
      if @tables[name].keys != keys or @tables[name].cols != cols then
        raise BudError, "create :#{name}, keys = #{keys.inspect}, cols = #{cols.inspect} \n \
        table :#{name} already defined as #{@tables[name].keys.inspect} #{@tables[name].cols.inspect}"
      end
      @tables[name].tick
      return @tables[name]
    else 
      self.class.send :define_method, name do 
        @tables[name]
      end 
      return nil
    end
  end
  
  def table(name, keys=[], cols=[])
    check_table(name, keys, cols)
    @tables[name] ||= BudTable.new(name, keys, cols, self)
  end

  def scratch(name, keys=[], cols=[])
    check_table(name, keys, cols)
    @tables[name] ||= BudScratch.new(name, keys, cols, self)
  end

  def channel(name, locspec, keys=[], cols=[])
    check_table(name, keys, cols)
    @channels[name] = locspec
    @tables[name] ||= BudChannel.new(name, keys, cols, locspec, self)
  end
  
  def file_reader(name, filename, delimiter='\n')
    check_table(name, ['lineno'], ['text'])
    @tables[name] ||= BudFileReader.new(name, filename, delimiter, self)
  end

  def periodic(name, duration)
    t = check_table(name, ['ident'], ['time'])
    @tables[name] ||= BudPeriodic.new(name, ['ident'], ['time'], self)
    unless @periodics.has_key? [name]
      retval = [name, gen_id, duration]
      @periodics << retval
      set_timer(name, retval[1], duration)
    else
      retval = @periodics.find([name]).first
    end
    return retval
  end

  ## methods to define vars and tmpvars.  This code still quite tentative
  def regvar(name, collection)
    # rule out varnames that used reserved words
    reserved = defined?(name)
    if reserved == "method" and not collection[name] then
      # first time registering var, check for method name reserved
      raise BudError, "symbol :#{name} reserved, cannot be used as variable name"
    end
    self.class.send :define_method, name do 
      collection[name]
    end
    setter = (name.to_s + '=').to_sym
    self.class.send :define_method, setter do |val|
      curval = collection[name]
      raise BudError, "#{name} is frozen with value #{curval}" unless curval.nil?
      collection.delete(val)
      collection << [name,val]
      # collection <- [name]
      # collection <+ [name,val]
    end
  end
  
  def var(name)
    regvar(name, @vars)
  end
  
  def tmpvar(name)
    regvar(name, @tmpvars)
  end

  ####### Joins
  def join(rels, *preds)
    # decompose each pred into a binary pred
    newpreds = []
    preds.each do |p|
      p.each_with_index do |c, i|
        newpreds << [p[i], p[i+1]] unless p[i+1].nil?
      end
    end
    BudJoin.new(rels, newpreds)
  end
  
  def natjoin(rels)
    # for all pairs of relations, add predicates on matching column names
    preds = []
    rels.each do |r|
      rels.each do |s|
        matches = r.schema & s.schema
        matches.each do |c| 
          preds << [self.send(r.name).send(c), self.send(s.name).send(c)] unless r.name.to_s >= s.name.to_s
        end
      end
    end
    preds.uniq!
    join(rels, *preds)
  end
  
  ######## ids and timers
  def gen_id
    Time.new.to_i.to_s << rand.to_s
  end

  def set_timer(name, id, secs)
    EventMachine::Timer.new(secs) do
      @tables[name] <+ [[id, Time.new.to_s]]
      tick
    end
  end

  alias rules lambda
end

