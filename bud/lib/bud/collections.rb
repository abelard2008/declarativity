class Bud
  ######## the collection types
  class BudCollection
    include Enumerable

    attr_accessor :schema, :keys, :cols
    attr_reader :name, :bud_instance, :storage, :delta, :new_delta

    # each collection is partitioned into 4:
    # - pending holds tuples deferred til the next tick
    # - storage holds the "normal" tuples 
    # - delta holds the delta for rhs's of rules during semi-naive
    # - new_delta will hold the lhs tuples currently being produced during s-n
    def initialize(name, keys, cols, b_class)
      @name = name
      @schema = keys+cols
      @keys = keys
      init_storage
      init_pending
      init_deltas
      @bud_instance = b_class
      raise BudError, "schema for #{name} contains duplicate names" if schema.uniq.length < schema.length
      schema_accessors
    end

    def clone_empty
      retval = self.class.new(name, keys, schema - keys, bud_instance)
      retval.storage = []
      retval.pending = []
      return retval
    end

    def cols
      schema - keys
    end

    def tick
      @storage = @pending
      @pending = {}
      @delta = {}
      @delta_new = {}
      self
    end

    # define methods to turn 'table.col' into a [table,col] pair
    # e.g. to support somethin like
    #    j = join link, path, {link.to => path.from}
    def schema_accessors
      s = @schema
      m = Module.new do
        s.each_with_index do |c, i|
          define_method c.to_sym do
            [@name, i, c]
          end
        end
      end
      self.extend m
    end

    # define methods to access tuple attributes by column name
    # paa: inverted loop to add test, fix leak
    def tuple_accessors(t)
      @schema.each_with_index do |c, i|
        unless t.respond_to? c.to_sym
          m = Module.new do
            define_method c.to_sym do
              t[i]
            end
          end
          t.extend m
        end
      end
      return t
    end

    def null_tuple
      return tuple_accessors(@schema.map{|c| nil})
    end

    # by default, all tuples in any rhs are in storage or delta
    # tuples in new_delta will get transitioned to delta in the next
    # iteration of the evaluator (but within the current time tick)
    def each(&block)
      each_from([@storage, @delta], &block)
    end

    def each_from(bufs, &block)
      bufs.each do |b|
        b.each_key do |k|
          raise(BudError, "nil storage key") if k.nil?
          raise(BudError, "nil entry(#{@name}) for #{k.inspect}") if b[k].nil?
          yield b[k]
        end
      end
    end

    def each_storage(&block)
      each_from([@storage], &block)
    end

    def each_pending(&block)
      each_from([@pending], &block)
    end

    def each_delta(&block)
      each_from([@delta], &block)
    end

    def each_new_delta(&block)
      each_from([@new_delta], &block)
    end

    def init_storage
      @storage = {}
    end

    def init_pending
      @pending = {}
    end

    def init_deltas
      @delta = {}
      @new_delta = {}
    end

    def include?(o)
      return false if o.nil? or o.length == 0
      keycols = keys.map{|k| o[schema.index(k)]}
      if @storage[keycols]
        return (o == @storage[keycols]) 
      else
        return false
      end
    end

    def do_insert(o, store)
      return if o.nil? or o.length == 0
      keycols = keys.map{|k| o[schema.index(k)]}
      if store.include?(keycols) and o != store[keycols]
        raise KeyConstraintError, "Key conflict inserting [#{keycols.inspect}][#{o.inspect}] into #{name}: existing tuple [#{keycols.inspect}][#{store[keycols].inspect}]"
      end
      store[keycols] = tuple_accessors(o)
      return o
    end

    def insert(o)
      # puts "insert: #{o.inspect} into #{name}"
      do_insert(o, @storage)
    end

    alias << insert

    def pending_insert(o)
      # puts "pending_insert: #{o.inspect} into #{name}"
      do_insert(o, @pending)
    end

    def merge(o, buf=@new_delta)
      raise BudError, "Attempt to merge non-enumerable type into BloomCollection: #{o.inspect}" unless o.respond_to? 'each'
      delta = o.map {|i| self.do_insert(i, buf) unless self.include?(i) or @delta.include?(i)}
      if self.schema.empty? and o.respond_to?(:schema) and not o.schema.empty?
        self.schema = o.schema
      end
      return self
    end
    
    alias <= merge
    
    def pending_merge(o)
      delta = o.map {|i| self.pending_insert(i)}
      if self.schema.empty? and o.respond_to?(:schema) and not o.schema.empty?
        self.schema = o.schema
      end
      return @pending
    end

    superator "<+" do |o|
      pending_merge o
    end

    # move all deltas and new_deltas into storage
    def install_deltas
      @delta.each do |k, t| 
        do_insert(t, @storage)
      end
      @new_delta.each do |k,t| 
        do_insert(t, @storage)
      end
      @delta = {}
      @new_delta = {}
    end

    # move deltas to storage, and new_deltas to deltas.
    def tick_deltas
      @delta.each do |k, t| 
        do_insert(t, @storage)
      end
      @delta = @new_delta
      # @new_delta.each do |k,t| 
      #   do_insert(t, @delta)
      # end
      @new_delta = {}
    end

    def [](key)
      @storage[key]
    end

    def method_missing(sym, *args, &block)
      @storage.send sym, *args, &block
    end

    ######## aggs

    def argagg(aggname, gbkeys, col)
      agg = bud_instance.send(aggname, nil)[0]
      raise BudError, "#{aggname} not declared exemplary" unless agg.class <= Bud::ArgExemplary
      keynames = gbkeys.map {|k| k[2]}
      colnum = col[1]
      retval = BudScratch.new('temp', @schema, [], bud_instance)
      tups = self.inject({}) do |memo,p|
        pkeys = keynames.map{|n| p.send(n.to_sym)}
        if memo[pkeys].nil?
          memo[pkeys] = {:agg=>agg.send(:init, p[colnum]), :tups => [p]}
        else
          newval = agg.send(:trans, memo[pkeys][:agg], p[colnum])
          if memo[pkeys][:agg] == newval
            if agg.send(:tie, memo[pkeys][:agg], p[colnum])
              memo[pkeys][:tups] << p
            end
          else
            memo[pkeys] = {:agg=>newval, :tups=>[p]}
          end
        end
        memo
      end

      finals = []
      outs = tups.each_value do |t|
        ties = t[:tups].map do |tie|
          finals << tie
        end
      end
      retval.merge(finals)
    end

    def argmin(gbkeys, col)
      argagg(:min, gbkeys, col)
    end

    def argmax(gbkeys, col)
      argagg(:max, gbkeys, col)
    end

    def group(keys, *aggpairs)
      keys = [] if keys.nil?
      keynames = keys.map {|k| k[2]}
      aggcolsdups = aggpairs.map{|ap| ap[0].class.name.split("::").last}
      aggcols = []
      aggcolsdups.each_with_index do |n,i|
        aggcols << ((aggcolsdups.select{|ca| ca==n}.length > 1) ? "#{n.downcase}_#{i}" : n)
      end
      retval = BudScratch.new('temp', keynames, aggcols, bud_instance)
      tups = self.inject({}) do |memo,p|
        pkeys = keynames.map{|n| p.send(n.to_sym)}
        memo[pkeys] = [] if memo[pkeys].nil?
        aggpairs.each_with_index do |ap, i|
          agg = ap[0]
          colnum = ap[1].nil? ? nil : ap[1][1]
          colval = colnum.nil? ? nil : p[colnum]
          if memo[pkeys][i].nil?
            memo[pkeys][i] = agg.send(:init, colval)
          else
            memo[pkeys][i] = agg.send(:trans, memo[pkeys][i], colval)
          end
        end
        memo
      end

      result = tups.inject([]) do |memo,t|
        finals = []
        aggpairs.each_with_index do |ap, i|
          finals << ap[0].send(:final, t[1][i])
        end
        memo << t[0] + finals
      end
      # merge directly into result.storage, so that the temp tuples get picked up
      # by the lhs of the rule
      retval.merge(result, retval.storage)
    end

    def dump
      puts '(empty)' if (@storage.length == 0)
      @storage.sort.each do |t|
        puts t.inspect unless cols.empty?
        puts t[0].inspect if cols.empty?
      end
      true
    end

    alias reduce inject
  end

  class BudScratch < BudCollection
  end
  
  class BudSerializer < BudCollection
    def initialize(name, keys, cols, b_class)
      @dq = {}
      super
    end
    def tick
      @dq.each_key {|k| @storage.delete k}
    end

    def each
      @storage.keys.sort.each do |k|
        tup = (@storage[k] == true) ? k : @storage[k]
        yield tup
        @dq[k] = true
        return
      end
    end
  end

  class BudChannel < BudCollection
    attr_accessor :locspec, :connections

    def initialize(name, keys, cols, b_class, locspec_arg)
      super(name, keys, cols, b_class)
      @locspec = locspec_arg
      @connections = {}
    end

    def split_locspec(l)
      lsplit = l.split(':')
      lsplit[1] = lsplit[1].to_i
      return lsplit
    end

    def clone_empty
      retval = super
      retval.locspec = locspec
      retval.connections = @connections.clone
      return retval
    end

    def establish_connection(l)
      @connections[l] = EventMachine::connect l[0], l[1], BudServer, @bud_instance
    end

    def tick
      # tuples inserted during bootstrap (@budtime==0) need to get sent in the next tick
      # so only clear @pending if @budtime > 0
      if @bud_instance.budtime > 0
        @storage = {}
        # never turn pending outbounds into real tuples
        @pending = {}
      end
    end

    def flush
      ip = @bud_instance.ip
      port = @bud_instance.port
      each_pending do |t|
        if @locspec.nil?
          the_locspec = [ip, port.to_i]
        else
          begin
            the_locspec = split_locspec(t[@locspec])
          rescue
            puts "bad locspec #{@locspec} for #{@name}"
          end
        end
        establish_connection(the_locspec) if @connections[the_locspec].nil?
        @connections[the_locspec].send_data [@name, t].to_msgpack
        @pending.delete t
      end
    end

    superator "<~" do |o|
      pending_merge o
    end

    superator "<+" do |o|
      raise BudError, "Illegal use of <+ with async collection on left"
    end
  end

  class BudTerminal < BudCollection
    def initialize(name, keys, cols, b_class, prompt=false)
      super(name, keys, cols, b_class)

      ip = b_class.ip
      port = b_class.port
      @connection = nil

      # XXX: Ugly hack. Rather than sending terminal data to EM via TCP,
      # we should add the terminal file descriptor to the EM event loop.
      @reader = Thread.new() do
        begin
          while true
            str = name.to_s + " > "
            STDOUT.print(str) if prompt
            s = STDIN.gets
            s = s.chomp if s
            tup = tuple_accessors([s])
            @connection ||= EventMachine::connect ip, port, BudServer, @bud_instance
            @connection.send_data [name, tup].to_msgpack
          end
        rescue
          print "terminal reader thread failed with #{$!}\ncaller: #{caller.inspect}"
          exit
        end
      end
    end

    def flush
      @pending.each do |p|
        STDOUT.puts p[0]
      end
      @pending = {}
    end

    def tick
      @storage = {}
      @pending = {}
    end

    def merge(o)
      raise BudError, "no synchronous accumulation into terminal; use <~"
    end

    def <=(o)
      merge(o)
    end

    superator "<~" do |o|
      pending_merge(o)
    end
  end

  class BudPeriodic < BudCollection
  end

  class BudTable < BudCollection
    def initialize(name, keys, cols, bud_instance, conflict)
      super(name, keys, cols, bud_instance)
      @conflict = conflict
      init_to_delete
    end

    def clone_empty
      retval = self.class.new(name, keys, schema - keys, bud_instance, @conflict)
      retval.init_storage
      retval.init_pending
      retval.init_to_delete
      return retval
    end

    def tick
      @to_delete.each_key {|t| @storage.delete t}
      @storage.merge! @pending
      @to_delete = {}
      @pending = {}
      #      self
    end

    def init_to_delete
      @to_delete = {}
    end

    superator "<-" do |o|
      # delta =
      o.map {|i| self.do_insert(i, @to_delete)}
    end
  end

  class BudJoin < BudCollection
    attr_accessor :rels, :origrels

    def initialize(rellist, b_class, preds=nil)
      @schema = []
      otherpreds = nil
      @origrels = rellist
      @bud_instance = b_class

      # extract predicates on rellist[0] and let the rest recurse
      unless preds.nil?
        @localpreds = preds.reject { |p| p[0][0] != rellist[0].name and p[1][0] != rellist[0].name }
        @localpreds.each do |p|
          if p[1][0] == rellist[0].name
            @localpreds.delete(p)
            @localpreds << [p[1], p[0]]
          end
        end
        otherpreds = preds.reject { |p| p[0][0] == rellist[0].name or p[1][0] == rellist[0].name}
        otherpreds = nil if otherpreds.empty?
      end
      if rellist.length == 2 and not otherpreds.nil?
        raise BudError, "join predicates don't match tables being joined: #{otherpreds.inspect}"
      end

      # recurse to form a tree of binary BudJoins
      @rels = [rellist[0]]
      @rels << (rellist.length == 2 ? rellist[1] : BudJoin.new(rellist[1..rellist.length-1], @bud_instance, otherpreds))

      # now derive schema: combo of rels[0] and rels[1]
      if @rels[0].schema.empty? or @rels[1].schema.empty?
        @schema = []
      else
        dups = @rels[0].schema & @rels[1].schema
        bothschema = @rels[0].schema + @rels[1].schema
        @schema = bothschema.to_enum(:each_with_index).map do |c,i| 
          if dups.include?(c) then 
            c + '_' + i.to_s else c 
          end
        end
      end
    end

    def do_insert(o,store)
      raise BudError, "no insertion into joins"
    end

    def each(mode=:both, &block)
      mode = :storage if @bud_instance.stratum_first_iter
      if mode == :storage
        methods = [:storage]
      else
        methods = [:delta, :storage]
      end

      methods.each do |collection1|
        methods.each do |collection2|
          next if (mode == :delta and collection1 == :storage and collection2 == :storage)
          if @localpreds.nil? or @localpreds.empty?
            nestloop_join(collection1, collection2, &block)
          else
            hash_join(collection1, collection2, &block)
          end
        end
      end
    end

    def each_storage(&block)
      return each(:storage, &block)
    end
    
    # this needs to be made more efficient!
    def each_delta(&block)
      return each(:delta, &block)
    end

    def test_locals(r, s, *skips)
      retval = true
      if (@localpreds and skips and @localpreds.length > skips.length)
        # check remainder of the predicates
        @localpreds.each do |pred|
          next if skips.include? pred
          r_offset, s_index, s_offset = join_offsets(pred)
          if r[r_offset] != s[s_index][s_offset]
            retval = false
            break
          end
        end
      end
      return retval
    end

    def nestloop_join(collection1, collection2, &block)
      @rels[0].send(('each_' + collection1.to_s).to_sym) do |r|
        @rels[1].send(('each_' + collection2.to_s).to_sym) do |s|
          s = [s] if origrels.length == 2
          yield([r] + s) if test_locals(r, s)
        end
      end
    end
    
    def join_offsets(pred)
      build_entry = pred[1]
      build_name, build_offset = build_entry[0], build_entry[1]
      probe_entry = pred[0]
      probe_name, probe_offset = probe_entry[0], probe_entry[1]

      # determine which subtuple of s contains the table referenced in RHS of pred
      # note that s doesn't contain the first entry in rels, which is r
      index = 0
      origrels[1..origrels.length].each_with_index do |t,i|
        if t.name == pred[1][0]
          index = i
          break
        end
      end

      return probe_offset, index, build_offset
    end

    def hash_join(collection1, collection2, &block)
      # hash join on first predicate!
      ht = {}

      probe_offset, build_tup, build_offset = join_offsets(@localpreds.first)

      # build the hashtable on s!
      rels[1].send(('each_' + collection2.to_s).to_sym) do |s|
        s = [s] if origrels.length == 2
        attrval = s[build_tup][build_offset]
        ht[attrval] ||= []
        ht[attrval] << s
      end

      # probe the hashtable!
      rels[0].send(('each_' + collection1.to_s).to_sym) do |r|
        next if ht[r[probe_offset]].nil?
        ht[r[probe_offset]].each do |s|
          retval = [r] + s
          yield(retval) if test_locals(r, s, @localpreds.first)
        end
      end
    end
  end

  class BudLeftJoin < BudJoin
    def initialize(rellist, b_class, preds=nil)
      raise(BudError, "Left Join only defined for two relations") unless rellist.length == 2
      super(rellist, b_class, preds)
      @origpreds = preds
    end

    def each(&block)
      super(&block)
      # previous line finds all the matches.
      # now its time to ``preserve'' the outer tuples with no matches.
      # this is totally inefficient: we should fold the identification of non-matches
      # into the join algorithms.  Another day.
      # our trick: for each tuple of the outer, generate a singleton relation
      # and join with inner.  If result is empty, preserve tuple.
      @rels[0].each do |r|
        t = @origrels[0].clone_empty
        t.insert(r)
        j = BudJoin.new([t,@origrels[1]], @bud_instance, @origpreds)
        next if j.any?
        nulltup = @origrels[1].null_tuple
        yield [r, nulltup]
      end
    end
  end

  class BudReadOnly < BudScratch
    superator "<+" do |o|
      raise BudError, "Illegal use of <+ with read-only collection on left"
    end
    def merge
      raise BudError, "Illegale use of <= with read-only collection on left"
    end
  end

  class BudFileReader < BudReadOnly
    def initialize(name, filename, delimiter, b_class)
      super(name, ['lineno'], ['text'], b_class)
      @filename = filename
      @storage = {}
      File.open(@filename).each_with_index { |line, i|
        @storage[[i]] = tuple_accessors([i, line.strip])
      }
    end

    def tick
      self
    end
  end
end
