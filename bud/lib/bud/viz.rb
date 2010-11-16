require 'rubygems'
require 'graphviz'


class Viz 
  def initialize(strata, mapping, tableinfo, cycle, depanalysis=nil)
    @graph = GraphViz.new(:G, :type => :digraph, :label => "")
    @graph.node[:fontname] = "Times-Roman"
    @graph.edge[:fontname] = "Times-Roman"
    @tiers = []

    #strata.each {|s| print "STR: #{s.inspect}\n" } 
    #mapping.each {|s| print "MAPP: #{s.inspect}\n" } 
    #tableinfo.each {|s| print "TI: #{s.inspect}\n" } 

    # array: strata
    # disabled for now.
    #(0..strata.first[0]+1).each do |s|
    #  @tiers[s] = @graph.subgraph("cluster" + s.to_s(), {:color => "black", :style => "dotted, rounded"})
    #end
    @depanalysis = depanalysis

    # map: table -> stratum
    @t2s = {}
    mapping.each do |m|
      @t2s[m[0]] = m[1].to_i
    end


    @tabinf = {}
    tableinfo.each do |ti|
      print "pop on #{ti[0].to_s}\n"
     # @tabinf[ti[0].to_s] = ti[1].class
      @tabinf[ti[0].to_s] = ti[1]
    end

    @redcycle = {}
    cycle.each do |c|
      print "ELEM of cyc: #{c.inspect}\n"
      if c[2] and c[3]
        print "I AM IN A REDCYCLE: #{c.inspect}\n"
        if !@redcycle[c[0]]
          @redcycle[c[0]] = []
        end
        @redcycle[c[0]] << c[1]
      end      
    end
    
    @nodes = {}
    @edges = {}
    @labels = {}
  end
  
  def safe_t2s(tab)
    if @t2s[tab]
      @t2s[tab]
    else  
      words = tab.split(",")
      maxs = 0
      words.each do |w|
        if @t2s[w] and @t2s[w] > maxs
          #print "INC MAX: #{w} to #{@t2s[w]}\n"
          maxs = @t2s[w]
        end
      end
      return maxs
    end
  end

  def name_bag(predicate, bag)
    if bag[predicate]
      return bag
    else
      bag[predicate] = true
      res = bag
      @redcycle[predicate].each do |rp|
        res = name_bag(rp, res)      
      end
    end

    return res
  end

  def nice_str(str)
    
    
  end

  def name_of(predicate)
    # consider doing this in overlog.
    if @redcycle[predicate]
      print "REDDY: #{predicate}\n"
      via = @redcycle[predicate]
      bag = name_bag(predicate, {})
      str = bag.keys.sort.join(", ")
      print "RETURNING BAG: #{str}\n"
      return str
    else
      return predicate
    end 
  end

  def process(depends)

    # collapsing NEG/+ cycles.
    # we want to create a function from any predicate to (cycle_name or bottom)
    # bottom if the predicate is not in a NEG/+ cycle.  otherwise,
    # its name is "CYC" + concat(sort(predicate names))

    depends.each do |d|
      print "DEP: #{d.inspect}\n"
      head = d[1]
      body = d[3]
      if !@tabinf[head] or !@tabinf[body]
        #next
      end

      # hack attack
      if body == "count" or head == "localtick" or head == "stdio"
        next
      end

      head = name_of(head)
      body = name_of(body)
      addonce(head, (head != d[1]))
      addonce(body, (body != d[3]))
      addedge(body, head, d[2], d[3], (head != d[1]))
    end
    #print "done processing\n"
  end

  def addonce(node, negcluster)
    if !@nodes[node]
      #print "ST is #{safe_t2s(node)} (with tiers #{@tiers.length})\n"
      #@nodes[node] = @tiers[safe_t2s(node)].add_node(node)
      @nodes[node] = @graph.add_node(node)
    end
    if negcluster
      # cleaning 
      res = node
      node.split(", ").each_with_index do |p, i|
        #print "ITEM #{p} at #{i}\n"
        if i == 0
          res = p
        elsif i % 4 == 0
          res = res + ",\n" + p
        else
          res = res + ", " + p
        end
      end
      @nodes[node].label = res
      @nodes[node].color = "red"
      @nodes[node].shape = "octagon"
    #elsif @tabinf[node] and (@tabinf[node] == Bud::BudChannel or @tabinf[node] == Bud::BudScratch)
    elsif @tabinf[node] and (@tabinf[node] == Bud::BudTable)
      @nodes[node].shape = "rect"
    end
  end

  def addedge(body, head, op, nm, negcluster)
    print "EKey IS #{body.to_s} + #{head.to_s}\n"
    return if body.nil? or head.nil?
    ekey = body + head
    if !@edges[ekey] 
      #print "ADD edge #{ekey}\n"
      @edges[ekey] = @graph.add_edge(@nodes[body], @nodes[head])
      @labels[ekey] = {}
    end

    

    if op == '<+'
      @labels[ekey]['+'] = true
    elsif op == "<~"
      @edges[ekey].style = 'dashed'
    elsif op == "<-"
      #@labels[ekey] = @labels[ekey] + 'NEG(del)'
      @labels[ekey]['¬'] = true
      @labels[ekey]['+'] = true
    end
    if nm == 1
      # hm, nonmono
      #@labels[ekey] = @labels[ekey] + 'NEG'
      @labels[ekey]['¬'] = true
    end
  
    if (safe_t2s(head) != safe_t2s(body)) or negcluster
      @edges[ekey].arrowhead = 'veeodot'
    end

  end

  def finish(name)
    @labels.each_key do |k|
      @edges[k].label = @labels[k].keys.join(" ")
    end

    addonce("S", false)
    addonce("T", false)

    @nodes["S"].color = "blue"
    @nodes["T"].color = "blue"
    @nodes["S"].shape = "diamond"
    @nodes["T"].shape = "diamond"

    @tabinf.each_pair do |k, v|
      unless @nodes[name_of(k.to_s)] or k.to_s =~ /_tbl/ or k.to_s == "tickler"
        addonce(k.to_s, false)
      end
    end

    @depanalysis.source.each {|s| addedge("S", s.pred, false, false, false) }
    @depanalysis.sink.each {|s| addedge(s.pred, "T", false, false, false) }

    @depanalysis.underspecified.each do |u|
      unless u.other.nil?
        addonce("??", false)
        addedge("??", u.other, false, false, false)
        addedge(u.pred, "??", false, false, false)
        @nodes["??"].color = "red"
        @nodes["??"].shape = "diamond"
      end
    end

    #@graph.output(:dot => "#{name}.dot")    
    @graph.output(:pdf => "#{name}.pdf")
  end

  
end
