require 'msgpack'
require 'eventmachine'
require 'socket'
require 'superators'
require 'parse_tree'
require 'parse_tree_extensions'
require 'anise'
require 'bud/aggs'
require 'bud/collections'
require 'bud/errors'
require 'bud/server'
require 'bud/strat'
require 'bud/static_analysis'
require 'bud/bud_meta'
require 'bud/viz'
require 'bud/state'

class Bud
  attr_reader :strata, :budtime, :inbound
  attr_accessor :connections
  attr_reader :tables, :ip, :port # for  ging; remove me later
  attr_accessor :each_counter
  attr_reader :stratum_first_iter

  include BudState
  include Anise
  annotator :declare

#  def input
#    true
#  end

  def initialize(ip, port, options = nil)
    @tables = {}
    @table_meta = []
    @strata = []
    @rewritten_strata = []
    @provides = {}
    @demands = {}
    @channels = {}
    @budtime = 0
    @each_counter = {}
    @ip = ip
    @port = port.to_i
    @ip_port = "#{@ip}:#{@port}"
    @connections = {}
    @inbound = []
    @declarations = []
    @options = options.nil? ? {} : options
    self.class.ancestors.each do |anc|
      @declarations += anc.annotation.map{|a| a[0] if a[1].keys.include? :declare}.compact if anc.methods.include? 'annotation'
    end
    #self.class.annotation.map {|a| puts "another annotation: #{a.inspect}" }
    @declarations.uniq!

    @periodics = table :periodics_tbl, ['name'], ['ident', 'duration']
    @vars = table :vars_tbl, ['name'], ['value']
    @tmpvars = scratch :tmpvars_tbl, ['name'], ['value']

    state

    bootstrap
    # make sure that new_delta tuples from bootstrap rules are transitioned into 
    # storage before first tick.
    tables.each{|name,coll| coll.install_deltas}

    # meta stuff.  parse the AST of the current (sub)class,
    # get dependency info, and determine stratification order.
    unless self.class <= Stratification or self.class <= DepAnalysis
      safe_rewrite
      provenance_extend if @options['provenance']
    end
  end

  ########### give empty defaults for these
  def state
    #channel :tickler, 0, ['server']
  end
  def declaration
  end
  def bootstrap
  end

  ########### metaprogramming support for ruby and for rule rewriting
  # helper to define instance methods
  def singleton_class
    class << self; self; end
  end

  def safe_rewrite
    if @options["enforce_rewrite"]
      @rewritten_strata = meta_rewrite
    elsif !@options["disable_rewrite"]
      begin
        @rewritten_strata = meta_rewrite
      rescue
        puts "Running original (#{self.class}) code: couldn't rewrite stratified ruby (#{$!})"
      end
    else
      puts "No rewriting performed"
    end
  end

  ######## methods for controlling execution
  def run_bg
    @t = Thread.new() do
      # PAA, towards better error messages
      begin
        run
      rescue
        print "background thread failed with #{$!}\ncaller: #{caller.inspect}"
        exit
      end
    end
    # for now
    @t.abort_on_exception = true

    # Block for EM to start up before returning
    EventMachine::next_tick {
      # no-op
    }
  end

  def stop_bg
    EventMachine::next_tick {
      EventMachine::stop_event_loop
    }
    # Block until the background thread has actually exited
    @t.join
  end


  # We proceed in time ticks, a la Dedalus.
  # Within each tick there may be multiple strata.
  # Within each stratum we do multiple semi-naive iterations.
  def run
    begin
      EventMachine::run {
        EventMachine::start_server(@ip, @port, BudServer, self)
        # initialize periodics
        @periodics.each do |p|
          set_timer(p.name, p.ident, p.duration)
        end
        builtin_state
        tick
      }
    end
  end

  def builtin_state
    channel  :localtick, ['col1']
    terminal :stdio
  end

  def tick
    # reset any schema stuff that isn't already there
    # state to be defined by the user program
    # rethink this.
    unless @options['provenance']
      state
      builtin_state
    end

    receive_inbound
    @tables.each do |name,coll| 
      coll.tick_deltas
    end

    # load the rules as a closure (will contain persistent tuples and new inbounds)
    # declaration is gathered from "declare def" blocks
    @strata = []
    declaration
    if @rewritten_strata.length > 0
      @rewritten_strata.each_with_index do |rs, i|
        # FIX: move to compilation
        str = rs.nil? ? "" : rs
        block = lambda { eval(str) }
        @strata << block
      end
    elsif @declarations.length > 0
      # the old way...
      @declarations.each do |d|
        @strata << self.method(d).to_proc
      end
    end
    @strata.each { |strat| stratum_fixpoint(strat) }
    @channels.each { |c| @tables[c[0]].flush }
    reset_periodics
    @budtime += 1
    return @budtime
  end

  # handle any inbound tuples off the wire and then clear
  def receive_inbound
    @inbound.each do |msg|
      tables[msg[0].to_sym] << msg[1]
    end
    @inbound = []
  end

  def stratum_fixpoint(strat)
    # This routine uses semi-naive evaluation to compute 
    # a fixpoint of the rules in strat.
    # We *almost* have semi-naive evaluation working.
    # at end of each iteration of this loop we transition:
    # - delta tuples move into storage
    # - new_delta moves to delta
    # - new_delta is set to empty
    # (see BudCollection for a description of the 4 partitions 
    #  of tuples within a collection.)
    # This scheme does semi-naive eval for Join.map
    # because the join.each code understands
    # the diff between storage and delta.
    # But calling map on a non-join collection goes through both
    # storage and delta.

    # XXX
    # To use deltas for all Collections (not just Join), we would
    # need Collection.each to understand that on iteration 1 of a 
    # fixpoint, it should use storage for all predicates, but
    # on iterations 2..n of a fixpoint, it should use
    # deltas for predicates that appear in lhs in this stratum,
    # and use storage for predicates that appear in lower strata.

    # In semi-naive, the first iteration should join up tables
    # on their storage fields; subsequent iterations do the
    # delta-joins only.  The stratum_first_iter field here distinguishes
    # these cases.
    @stratum_first_iter = true
    begin
      strat.call
      @stratum_first_iter = false
      # this is overkill.
      # should call tick_deltas only on predicates in this stratum
      # and then should appropriately deal with deltas in subsequent strata.
      @tables.each{|name,coll| coll.tick_deltas}
    end while not @tables.all?{|name,coll| coll.new_delta.empty? and coll.delta.empty?}    
  end

  def reset_periodics
    @periodics.each do |p|
      if @tables[p.name].length > 0 then
        set_timer(p.name, p.ident, p.duration)
        @tables[p.name] = scratch p.name, @tables[p.name].keys, @tables[p.name].cols
      end
    end
  end


  ####### Joins
  def decomp_preds(*preds)
    # decompose each pred into a binary pred
    newpreds = []
    preds.each do |p|
      p.each_with_index do |c, i|
        newpreds << [p[i], p[i+1]] unless p[i+1].nil?
      end
    end
    newpreds
  end

  def join(rels, *preds)
    BudJoin.new(rels, self, decomp_preds(*preds))
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

  def leftjoin(rels, *preds)
    BudLeftJoin.new(rels, self, decomp_preds(*preds))
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

  def tickle
    EventMachine::connect(@ip, @port) do |c|
      c.send_data(" ")
    end
  end

  alias rules lambda
end

